package truehordes.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The zombies the mod holds, with stable IDs and three indexes: by chunk (arrivals, respawn), by the game's
 * persistent ID (duplicates) and a count per 256x256 cell (population targets). Main thread only (the core is
 * synchronized for the dedicated server's shutdown thread).
 */
public final class Store {
    private final Map<Long, ZombieRecord> byId = new LinkedHashMap<>();
    private final Map<Long, List<ZombieRecord>> byChunk = new HashMap<>();
    private final Map<Integer, ZombieRecord> byPersistentId = new HashMap<>();
    private final Map<Long, int[]> perCell = new HashMap<>();
    private long nextId = 1;
    private Listener listener = Listener.NONE;

    /** Told about every add, removal and move (the off-screen simulation keeps its grid and schedule from this). */
    public interface Listener {
        Listener NONE = new Listener() {
            @Override public void added(ZombieRecord r) {}
            @Override public void removed(ZombieRecord r) {}
            @Override public void moved(ZombieRecord r, float oldX, float oldY) {}
        };

        void added(ZombieRecord r);

        void removed(ZombieRecord r);

        void moved(ZombieRecord r, float oldX, float oldY);
    }

    public void setListener(Listener l) { listener = l == null ? Listener.NONE : l; }

    public ZombieRecord add(float x, float y, float z, byte dir, int outfitId, int stateFlags, int persistentId,
                            int targetX, int targetY, byte[] modData, boolean building) {
        ZombieRecord r = new ZombieRecord(nextId++, x, y, z, dir, outfitId, stateFlags, persistentId, targetX, targetY,
            modData, building);
        index(r);
        listener.added(r);
        return r;
    }

    /** Re-adds a loaded record with its saved ID. */
    public void restore(ZombieRecord r) {
        if (byId.containsKey(r.id)) throw new IllegalStateException("duplicate record id " + r.id);
        index(r);
        if (r.id >= nextId) nextId = r.id + 1;
        listener.added(r);
    }

    private void index(ZombieRecord r) {
        byId.put(r.id, r);
        byChunk.computeIfAbsent(key(r.chunkX(), r.chunkY()), k -> new ArrayList<>()).add(r);
        if (r.persistentId != 0) byPersistentId.put(r.persistentId, r);
        cellCount(r, 1);
    }

    private void unindexPosition(ZombieRecord r) {
        long k = key(r.chunkX(), r.chunkY());
        List<ZombieRecord> list = byChunk.get(k);
        if (list != null) {
            list.remove(r);
            if (list.isEmpty()) byChunk.remove(k);
        }
        cellCount(r, -1);
    }

    private void cellCount(ZombieRecord r, int delta) {
        long k = key(Math.floorDiv(r.chunkX(), 32), Math.floorDiv(r.chunkY(), 32));
        int[] c = perCell.computeIfAbsent(k, x -> new int[1]);
        c[0] += delta;
        if (c[0] == 0) perCell.remove(k);
    }

    private static long key(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    public ZombieRecord get(long id) { return byId.get(id); }

    public boolean remove(long id) {
        ZombieRecord r = byId.remove(id);
        if (r == null) return false;
        unindexPosition(r);
        if (r.persistentId != 0 && byPersistentId.get(r.persistentId) == r) byPersistentId.remove(r.persistentId);
        listener.removed(r);
        return true;
    }

    /** The records in a chunk (a copy). */
    public List<ZombieRecord> inChunk(int chunkX, int chunkY) {
        List<ZombieRecord> list = byChunk.get(key(chunkX, chunkY));
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public ZombieRecord byPersistentId(int persistentId) {
        return persistentId == 0 ? null : byPersistentId.get(persistentId);
    }

    /** Moves a record and keeps the indexes right. */
    public void moved(ZombieRecord r, float x, float y) {
        float ox = r.x, oy = r.y;
        int ocx = r.chunkX(), ocy = r.chunkY();
        r.x = x;
        r.y = y;
        if (ocx != r.chunkX() || ocy != r.chunkY()) {
            float nx = r.x, ny = r.y;
            r.x = ox;
            r.y = oy;
            unindexPosition(r);
            r.x = nx;
            r.y = ny;
            byChunk.computeIfAbsent(key(r.chunkX(), r.chunkY()), k -> new ArrayList<>()).add(r);
            cellCount(r, 1);
        }
        listener.moved(r, ox, oy);
    }

    /** Records held in a 256x256 cell. */
    public int countInCell(int cellX, int cellY) {
        int[] c = perCell.get(key(cellX, cellY));
        return c == null ? 0 : c[0];
    }

    public int size() { return byId.size(); }

    public long nextId() { return nextId; }

    /** Raises the ID counter (from a save file); never lowers it. */
    public void setNextId(long n) { nextId = Math.max(nextId, n); }

    public Collection<ZombieRecord> all() { return Collections.unmodifiableCollection(byId.values()); }

    public void clear() {
        byId.clear();
        byChunk.clear();
        byPersistentId.clear();
        perCell.clear();
        nextId = 1;
    }

    /** Records inside [minX,maxX) x [minY,maxY): as points if there are at most maxPoints, else counts per cell. */
    public Snapshot snapshot(float minX, float minY, float maxX, float maxY, int maxPoints) {
        List<ZombieRecord> in = new ArrayList<>();
        for (ZombieRecord e : byId.values()) {
            if (e.x >= minX && e.x < maxX && e.y >= minY && e.y < maxY) in.add(e);
        }
        return snapshotOf(in, minX, minY, maxX, maxY, maxPoints);
    }

    /** The same, for records already picked from the rectangle (e.g. through the simulation's spatial grid). */
    public static Snapshot snapshotOf(List<ZombieRecord> in, float minX, float minY, float maxX, float maxY,
                                      int maxPoints) {
        int limit = Math.max(1, maxPoints);
        if (in.size() <= limit) {
            // Points: x, y, kind (0 wanderer, 1 straggler).
            float[] d = new float[in.size() * 3];
            for (int i = 0; i < in.size(); i++) {
                ZombieRecord e = in.get(i);
                d[i * 3] = e.x;
                d[i * 3 + 1] = e.y;
                d[i * 3 + 2] = e.straggler ? 1 : 0;
            }
            return new Snapshot(false, 0, d, in.size(), in.size(), 1);
        }
        int cell = 8;
        double w = Math.max(1, maxX - minX);
        double h = Math.max(1, maxY - minY);
        while ((Math.ceil(w / cell) + 1) * (Math.ceil(h / cell) + 1) > limit) cell *= 2;
        Map<Long, int[]> counts = new HashMap<>();
        for (ZombieRecord e : in) {
            int cx = (int) Math.floor(e.x / cell);
            int cy = (int) Math.floor(e.y / cell);
            long key = ((long) cx << 32) ^ (cy & 0xffffffffL);
            counts.computeIfAbsent(key, k -> new int[] {cx, cy, 0})[2]++;
        }
        float[] d = new float[counts.size() * 3];
        int i = 0;
        int max = 0;
        for (int[] c : counts.values()) {
            d[i++] = (float) c[0] * cell;
            d[i++] = (float) c[1] * cell;
            d[i++] = c[2];
            max = Math.max(max, c[2]);
        }
        return new Snapshot(true, cell, d, counts.size(), in.size(), max);
    }
}
