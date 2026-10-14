package truehordes.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import truehordes.store.ZombieRecord;

/**
 * Walkers bucketed per 8x8-tile chunk so encounters only look at nearby chunks. A horde sits in every chunk its spread
 * circle covers. Not thread-safe: used under the core's lock.
 */
public final class SpatialGrid {
    private final Map<Long, List<ZombieRecord>> wanderers = new HashMap<>();
    private final Map<Long, List<Horde>> hordes = new HashMap<>();
    private int wandererCount;
    private int hordeEntries;
    private int stamp;

    static long key(int cx, int cy) { return ((long) cx << 32) | (cy & 0xFFFFFFFFL); }

    static int chunk(float tile) { return Math.floorDiv((int) Math.floor(tile), 8); }

    public void putWanderer(ZombieRecord r) {
        wanderers.computeIfAbsent(key(r.chunkX(), r.chunkY()), k -> new ArrayList<>()).add(r);
        wandererCount++;
    }

    /** The record already moved (Store.moved); (oldX, oldY) is where it was. */
    public void moveWanderer(ZombieRecord r, float oldX, float oldY) {
        int ocx = chunk(oldX), ocy = chunk(oldY);
        if (ocx == r.chunkX() && ocy == r.chunkY()) return;
        if (removeFrom(wanderers, key(ocx, ocy), r)) {
            wanderers.computeIfAbsent(key(r.chunkX(), r.chunkY()), k -> new ArrayList<>()).add(r);
        }
    }

    public void removeWanderer(ZombieRecord r) {
        if (removeFrom(wanderers, key(r.chunkX(), r.chunkY()), r)) wandererCount--;
    }

    public void putHorde(Horde h) { forCircle(h.x, h.y, h.spread(), k -> addHorde(k, h)); }

    /** The horde already moved or changed size; (oldX, oldY, oldSpread) is the circle it covered. */
    public void moveHorde(Horde h, float oldX, float oldY, float oldSpread) {
        forCircle(oldX, oldY, oldSpread, k -> { if (removeFrom(hordes, k, h)) hordeEntries--; });
        putHorde(h);
    }

    public void removeHorde(Horde h) {
        forCircle(h.x, h.y, h.spread(), k -> { if (removeFrom(hordes, k, h)) hordeEntries--; });
    }

    private void addHorde(long k, Horde h) {
        hordes.computeIfAbsent(k, x -> new ArrayList<>()).add(h);
        hordeEntries++;
    }

    /**
     * Wanderers within {@code radius} of (x, y) and hordes whose circle comes within {@code radius}. Each horde is
     * reported once.
     */
    public void near(float x, float y, float radius, Consumer<ZombieRecord> wandererOut, Consumer<Horde> hordeOut) {
        int s = ++stamp;
        float r2 = radius * radius;
        int x0 = chunk(x - radius), x1 = chunk(x + radius), y0 = chunk(y - radius), y1 = chunk(y + radius);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                long k = key(cx, cy);
                List<ZombieRecord> ws = wanderers.get(k);
                if (ws != null) {
                    for (int i = 0; i < ws.size(); i++) {
                        ZombieRecord w = ws.get(i);
                        float dx = w.x() - x, dy = w.y() - y;
                        if (dx * dx + dy * dy <= r2) wandererOut.accept(w);
                    }
                }
                List<Horde> hs = hordes.get(k);
                if (hs != null) {
                    for (int i = 0; i < hs.size(); i++) {
                        Horde h = hs.get(i);
                        if (h.seenStamp == s) continue;
                        h.seenStamp = s;
                        float dx = h.x - x, dy = h.y - y;
                        float reach = radius + h.spread();
                        if (dx * dx + dy * dy <= reach * reach) hordeOut.accept(h);
                    }
                }
            }
        }
    }

    public int wandererCount() { return wandererCount; }

    public int hordeEntries() { return hordeEntries; }

    public void clear() {
        wanderers.clear();
        hordes.clear();
        wandererCount = 0;
        hordeEntries = 0;
    }

    private static <T> boolean removeFrom(Map<Long, List<T>> m, long k, T v) {
        List<T> l = m.get(k);
        if (l == null) return false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) == v) {
                int last = l.size() - 1;
                l.set(i, l.get(last));
                l.remove(last);
                if (l.isEmpty()) m.remove(k);
                return true;
            }
        }
        return false;
    }

    private static void forCircle(float x, float y, float r, java.util.function.LongConsumer out) {
        int x0 = chunk(x - r), x1 = chunk(x + r), y0 = chunk(y - r), y1 = chunk(y + r);
        for (int cx = x0; cx <= x1; cx++) for (int cy = y0; cy <= y1; cy++) out.accept(key(cx, cy));
    }
}
