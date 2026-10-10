package truehordes.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry of simulated entities with stable IDs. Main thread only. */
public final class Store {
    private final Map<Long, Entity> byId = new LinkedHashMap<>();
    private long nextId = 1;

    public Entity create(byte kind, float x, float y, byte z) {
        Entity e = new Entity(nextId++, kind, x, y, z);
        byId.put(e.id, e);
        return e;
    }

    /** Re-adds a loaded entity with its saved ID. */
    public void restore(Entity e) {
        if (byId.putIfAbsent(e.id, e) != null) throw new IllegalStateException("duplicate entity id " + e.id);
        if (e.id >= nextId) nextId = e.id + 1;
    }

    public Entity get(long id) { return byId.get(id); }

    public boolean remove(long id) { return byId.remove(id) != null; }

    public int removeKind(byte kind) {
        int before = byId.size();
        byId.values().removeIf(e -> e.kind == kind);
        return before - byId.size();
    }

    public int size() { return byId.size(); }

    public long nextId() { return nextId; }

    /** Raises the ID counter (from a save file); never lowers it. */
    public void setNextId(long n) { nextId = Math.max(nextId, n); }

    public Collection<Entity> all() { return Collections.unmodifiableCollection(byId.values()); }

    public void clear() {
        byId.clear();
        nextId = 1;
    }

    /** Entities inside [minX,maxX) x [minY,maxY): as points if there are at most maxPoints, else counts per cell. */
    public Snapshot snapshot(float minX, float minY, float maxX, float maxY, int maxPoints) {
        int limit = Math.max(1, maxPoints);
        List<Entity> in = new ArrayList<>();
        for (Entity e : byId.values()) {
            if (e.x >= minX && e.x < maxX && e.y >= minY && e.y < maxY) in.add(e);
        }
        if (in.size() <= limit) {
            float[] d = new float[in.size() * 2];
            for (int i = 0; i < in.size(); i++) {
                d[i * 2] = in.get(i).x;
                d[i * 2 + 1] = in.get(i).y;
            }
            return new Snapshot(false, 0, d, in.size(), in.size(), 1);
        }
        int cell = 8;
        double w = Math.max(1, maxX - minX);
        double h = Math.max(1, maxY - minY);
        while ((Math.ceil(w / cell) + 1) * (Math.ceil(h / cell) + 1) > limit) cell *= 2;
        Map<Long, int[]> counts = new HashMap<>();
        for (Entity e : in) {
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
