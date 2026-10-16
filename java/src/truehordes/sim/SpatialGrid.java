package truehordes.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import truehordes.store.ZombieRecord;

/**
 * Walkers bucketed so encounters only look nearby: wanderers per 8x8-tile chunk, hordes (few, but wide) per 64x64-tile
 * block, in every block their spread circle covers. Not thread-safe: used under the core's lock.
 */
public final class SpatialGrid implements truehordes.store.Store.Listener {
    private final Map<Long, List<ZombieRecord>> wanderers = new HashMap<>();
    private final Map<Long, List<Horde>> hordes = new HashMap<>();
    /**
     * Wanderers per 64-tile block, and how many per 512-tile region: a huge circle (a loud gunshot) visits only the
     * populated regions and blocks and reads their zombies directly.
     */
    private final Map<Long, List<ZombieRecord>> blocks = new HashMap<>();
    private final Map<Long, int[]> regions = new HashMap<>();
    static final int REGION_BLOCKS = 8;
    private int wandererCount;
    private int hordeEntries;
    private int stamp;

    static long key(int cx, int cy) { return ((long) cx << 32) | (cy & 0xFFFFFFFFL); }

    /** The chunk key the main thread uses (x in the high 32 bits, y in the low). */
    public static long keyOf(int cx, int cy) { return key(cx, cy); }

    static int chunk(float tile) { return Math.floorDiv((int) Math.floor(tile), 8); }

    static final int BLOCK = 64;

    static int block(float tile) { return Math.floorDiv((int) Math.floor(tile), BLOCK); }

    @Override public void added(ZombieRecord r) { putWanderer(r); }

    @Override public void removed(ZombieRecord r) { removeWanderer(r); }

    @Override public void moved(ZombieRecord r, float oldX, float oldY) { moveWanderer(r, oldX, oldY); }

    public void putWanderer(ZombieRecord r) {
        wanderers.computeIfAbsent(key(r.chunkX(), r.chunkY()), k -> new ArrayList<>()).add(r);
        wandererCount++;
        addToBlock(r, r.chunkX(), r.chunkY());
    }

    private static int blockOfChunk(int c) { return Math.floorDiv(c, BLOCK / 8); }

    private void addToBlock(ZombieRecord r, int cx, int cy) {
        int bx = blockOfChunk(cx), by = blockOfChunk(cy);
        blocks.computeIfAbsent(key(bx, by), k -> new ArrayList<>()).add(r);
        regions.computeIfAbsent(key(Math.floorDiv(bx, REGION_BLOCKS), Math.floorDiv(by, REGION_BLOCKS)), k -> new int[1])[0]++;
    }

    private void removeFromBlock(ZombieRecord r, int cx, int cy) {
        int bx = blockOfChunk(cx), by = blockOfChunk(cy);
        if (!removeFrom(blocks, key(bx, by), r)) return;
        long rk = key(Math.floorDiv(bx, REGION_BLOCKS), Math.floorDiv(by, REGION_BLOCKS));
        int[] n = regions.get(rk);
        if (n != null && --n[0] <= 0) regions.remove(rk);
    }

    /** The record already moved (Store.moved); (oldX, oldY) is where it was. */
    public void moveWanderer(ZombieRecord r, float oldX, float oldY) {
        int ocx = chunk(oldX), ocy = chunk(oldY);
        if (ocx == r.chunkX() && ocy == r.chunkY()) return;
        if (removeFrom(wanderers, key(ocx, ocy), r)) {
            wanderers.computeIfAbsent(key(r.chunkX(), r.chunkY()), k -> new ArrayList<>()).add(r);
            if (blockOfChunk(ocx) != blockOfChunk(r.chunkX()) || blockOfChunk(ocy) != blockOfChunk(r.chunkY())) {
                removeFromBlock(r, ocx, ocy);
                addToBlock(r, r.chunkX(), r.chunkY());
            }
        }
    }

    public void removeWanderer(ZombieRecord r) {
        if (removeFrom(wanderers, key(r.chunkX(), r.chunkY()), r)) {
            wandererCount--;
            removeFromBlock(r, r.chunkX(), r.chunkY());
        }
    }

    public void putHorde(Horde h) { forCircle(h.x, h.y, h.spread(), k -> addHorde(k, h)); }

    /** The horde already moved or changed size; (oldX, oldY, oldSpread) is the circle it covered. */
    public void moveHorde(Horde h, float oldX, float oldY, float oldSpread) {
        forCircle(oldX, oldY, oldSpread, k -> { if (removeFrom(hordes, k, h)) hordeEntries--; });
        putHorde(h);
    }

    public void removeHorde(Horde h) { removeHorde(h, h.x, h.y, h.spread()); }

    /** Removes a horde that covered the circle (x, y, spread) when it was last put or moved. */
    public void removeHorde(Horde h, float x, float y, float spread) {
        forCircle(x, y, spread, k -> { if (removeFrom(hordes, k, h)) hordeEntries--; });
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
        nearWanderers(x, y, radius, wandererOut);
        nearHordes(x, y, radius, hordeOut);
    }

    /** Wanderers within {@code radius} of (x, y). */
    public void nearWanderers(float x, float y, float radius, Consumer<ZombieRecord> out) {
        float r2 = radius * radius;
        int x0 = chunk(x - radius), x1 = chunk(x + radius), y0 = chunk(y - radius), y1 = chunk(y + radius);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                List<ZombieRecord> ws = wanderers.get(key(cx, cy));
                if (ws == null) continue;
                for (int i = 0; i < ws.size(); i++) {
                    ZombieRecord w = ws.get(i);
                    float dx = w.x() - x, dy = w.y() - y;
                    if (dx * dx + dy * dy <= r2) out.accept(w);
                }
            }
        }
    }

    /**
     * Wanderers inside [minX,maxX) x [minY,maxY): walks the chunks of the rectangle, or every occupied chunk when that
     * is fewer (a zoomed-out debug map covers millions of chunks but only thousands hold anyone).
     */
    public void wanderersIn(float minX, float minY, float maxX, float maxY, Consumer<ZombieRecord> out) {
        int x0 = chunk(minX), x1 = chunk(maxX), y0 = chunk(minY), y1 = chunk(maxY);
        long area = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
        int bx0 = blockOfChunk(x0), bx1 = blockOfChunk(x1), by0 = blockOfChunk(y0), by1 = blockOfChunk(y1);
        long blockArea = (long) (bx1 - bx0 + 1) * (by1 - by0 + 1);
        if (blockArea > blocks.size()) {
            // Wider than the populated map (a zoomed-out debug map): every populated block.
            for (List<ZombieRecord> ws : blocks.values()) addIn(ws, minX, minY, maxX, maxY, out);
        } else if (area > 4096) {
            // A big rectangle (a loud gunshot): only the populated 512-tile regions, then their blocks' zombies.
            // (Walking every occupied chunk instead scanned the whole population once zombies crowded onto roads.)
            for (int rx = Math.floorDiv(bx0, REGION_BLOCKS); rx <= Math.floorDiv(bx1, REGION_BLOCKS); rx++) {
                for (int ry = Math.floorDiv(by0, REGION_BLOCKS); ry <= Math.floorDiv(by1, REGION_BLOCKS); ry++) {
                    if (!regions.containsKey(key(rx, ry))) continue;
                    for (int bx = Math.max(bx0, rx * REGION_BLOCKS); bx <= Math.min(bx1, rx * REGION_BLOCKS + REGION_BLOCKS - 1); bx++) {
                        for (int by = Math.max(by0, ry * REGION_BLOCKS); by <= Math.min(by1, ry * REGION_BLOCKS + REGION_BLOCKS - 1); by++) {
                            List<ZombieRecord> ws = blocks.get(key(bx, by));
                            if (ws != null) addIn(ws, minX, minY, maxX, maxY, out);
                        }
                    }
                }
            }
        } else {
            for (int cx = x0; cx <= x1; cx++) {
                for (int cy = y0; cy <= y1; cy++) {
                    List<ZombieRecord> ws = wanderers.get(key(cx, cy));
                    if (ws != null) addIn(ws, minX, minY, maxX, maxY, out);
                }
            }
        }
    }

    private static void addIn(List<ZombieRecord> ws, float minX, float minY, float maxX, float maxY,
                              Consumer<ZombieRecord> out) {
        for (int i = 0; i < ws.size(); i++) {
            ZombieRecord w = ws.get(i);
            if (w.x() >= minX && w.x() < maxX && w.y() >= minY && w.y() < maxY) out.accept(w);
        }
    }

    /** Hordes whose circle comes within {@code radius} of (x, y), each once. */
    public void nearHordes(float x, float y, float radius, Consumer<Horde> out) {
        if (hordeEntries == 0) return;
        int s = ++stamp;
        int x0 = block(x - radius), x1 = block(x + radius), y0 = block(y - radius), y1 = block(y + radius);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                List<Horde> hs = hordes.get(key(bx, by));
                if (hs == null) continue;
                for (int i = 0; i < hs.size(); i++) {
                    Horde h = hs.get(i);
                    if (h.seenStamp == s) continue;
                    h.seenStamp = s;
                    float dx = h.x - x, dy = h.y - y;
                    float reach = radius + h.spread();
                    if (dx * dx + dy * dy <= reach * reach) out.accept(h);
                }
            }
        }
    }

    public int wandererCount() { return wandererCount; }

    public int hordeEntries() { return hordeEntries; }

    public void clear() {
        wanderers.clear();
        hordes.clear();
        blocks.clear();
        regions.clear();
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
        int x0 = block(x - r), x1 = block(x + r), y0 = block(y - r), y1 = block(y + r);
        for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) out.accept(key(bx, by));
    }
}
