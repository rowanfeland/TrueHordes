package truehordes.world;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import truehordes.util.Log;

/**
 * The mod's knowledge of the map for one loaded world. start() builds the coarse grid on a daemon thread; tick() (main
 * thread) publishes it; live changes from the game go into an overlay of whole chunks (arrays replaced, never changed
 * in place) and into the published grid. Queries never throw; anything unknown or outside the map answers blocked.
 */
public final class World {
    public enum State { OFF, BUILDING, READY, FAILED }

    /** Largest area tileSample() will scan, in tiles per side. */
    static final int MAX_TILE_SAMPLE_SIDE = 256;
    private static final int MAX_SAMPLE_CELLS = 40_000;

    private volatile State state = State.OFF;
    private volatile Object buildToken;
    private volatile Grid grid;
    private volatile Grid built;
    private volatile BuildReport report;
    private volatile BuildReport pendingReport;
    private volatile TileCache tiles;
    private final Map<Long, byte[]> live = new ConcurrentHashMap<>();
    private volatile int tileChanges;

    public State state() { return state; }

    public boolean ready() { return grid != null; }

    public synchronized void start(WorldSource src) {
        stop();
        Object token = new Object();
        buildToken = token;
        tiles = new TileCache(src);
        state = State.BUILDING;
        Thread t = new Thread(() -> runBuild(src, token), "TrueHordes-World");
        t.setDaemon(true);
        t.start();
    }

    private void runBuild(WorldSource src, Object token) {
        try {
            BuildReport r = new BuildReport();
            Grid g = GridBuilder.build(src, () -> buildToken != token, r);
            synchronized (this) {
                if (g == null || buildToken != token) return;
                pendingReport = r;
                built = g;
            }
        } catch (Throwable t) {
            synchronized (this) {
                if (buildToken != token) return;
                state = State.FAILED;
            }
            Log.error("world map build failed", t);
        }
    }

    /** Main thread: publishes a finished build and applies live changes received meanwhile. */
    public synchronized void tick() {
        Grid g = built;
        if (g == null) return;
        built = null;
        for (Map.Entry<Long, byte[]> e : live.entrySet()) {
            recompute(g, (int) (e.getKey() >> 32), (int) (long) e.getKey(), e.getValue());
        }
        report = pendingReport;
        grid = g;
        state = State.READY;
        Log.info(report());
    }

    public synchronized void stop() {
        buildToken = null;
        grid = null;
        built = null;
        report = null;
        pendingReport = null;
        tiles = null;
        live.clear();
        tileChanges = 0;
        state = State.OFF;
    }

    /** Tests: waits until a build is waiting to be published (or already is). */
    public boolean awaitBuilt(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (built != null || grid != null) return true;
            if (state == State.FAILED) return false;
            Thread.sleep(5);
        }
        return false;
    }

    // ---- coarse queries (tile coordinates) ----

    public Terrain terrainAt(int x, int y) {
        Grid g = grid;
        return g == null ? Terrain.UNKNOWN : g.terrain(Math.floorDiv(x, 8), Math.floorDiv(y, 8));
    }

    public float costAt(int x, int y) {
        Grid g = grid;
        return g == null ? Float.POSITIVE_INFINITY : g.cost(Math.floorDiv(x, 8), Math.floorDiv(y, 8));
    }

    public boolean blockedAt(int x, int y) {
        Grid g = grid;
        return g == null || g.blocked(Math.floorDiv(x, 8), Math.floorDiv(y, 8));
    }

    /** The map's extent in tiles {minX, minY, maxX, maxY} (exclusive max), or null before the map is built. */
    public int[] tileBounds() {
        Grid g = grid;
        if (g == null) return null;
        return new int[] {g.originX * 8, g.originY * 8, (g.originX + g.width) * 8, (g.originY + g.height) * 8};
    }

    /** The chunk has map data (not void, not past the map's edge). False before the map is built. */
    public boolean chunkInMap(int chunkX, int chunkY) {
        Grid g = grid;
        return g != null && g.info(chunkX, chunkY) != ChunkInfo.UNLOADED;
    }

    public Terrain chunkTerrain(int chunkX, int chunkY) {
        Grid g = grid;
        return g == null ? Terrain.UNKNOWN : g.terrain(chunkX, chunkY);
    }

    public boolean chunkBlocked(int chunkX, int chunkY) {
        Grid g = grid;
        return g == null || g.blocked(chunkX, chunkY);
    }

    public boolean canCross(int chunkX, int chunkY, Dir d) {
        Grid g = grid;
        return g != null && g.canCross(chunkX, chunkY, d);
    }

    // ---- tile queries ----

    public int tileBits(int x, int y) {
        if (grid == null) return CollisionBits.NO_DATA;
        int cx = Math.floorDiv(x, 8), cy = Math.floorDiv(y, 8);
        byte[] chunk = live.get(WorldSource.key(cx, cy));
        if (chunk != null) return chunk[Math.floorMod(y, 8) * 8 + Math.floorMod(x, 8)] & 0xFF;
        TileCache tc = tiles;
        if (tc == null) return CollisionBits.NO_DATA;
        byte[] cell = tc.cell(Math.floorDiv(x, 256), Math.floorDiv(y, 256));
        return cell[Math.floorMod(y, 256) * 256 + Math.floorMod(x, 256)] & 0xFF;
    }

    public boolean walkable(int x, int y) { return CollisionBits.walkable(tileBits(x, y)); }

    /** Neighbouring tiles only (diagonals need both orthogonal routes clear). */
    public boolean canStep(int x1, int y1, int x2, int y2) {
        int dx = x2 - x1, dy = y2 - y1;
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || (dx == 0 && dy == 0)) return false;
        if (dx == 0 || dy == 0) return step(x1, y1, dx, dy);
        return step(x1, y1, dx, 0) && step(x1 + dx, y1, 0, dy) && step(x1, y1, 0, dy) && step(x1, y1 + dy, dx, 0);
    }

    private boolean step(int x, int y, int dx, int dy) {
        int from = tileBits(x, y), to = tileBits(x + dx, y + dy);
        if (!CollisionBits.walkable(from) || !CollisionBits.walkable(to)) return false;
        if (dx == 1) return (to & CollisionBits.WALL_W) == 0;
        if (dx == -1) return (from & CollisionBits.WALL_W) == 0;
        if (dy == -1) return (from & CollisionBits.WALL_N) == 0;
        return (to & CollisionBits.WALL_N) == 0;
    }

    // ---- live changes (main thread) ----

    /** An area loaded around a player: the game's own 64 tile bits for that chunk (index ty * 8 + tx). */
    public void liveChunk(int chunkX, int chunkY, byte[] bits64) {
        if (state == State.OFF || bits64 == null || bits64.length != 64) return;
        byte[] copy = bits64.clone();
        live.put(WorldSource.key(chunkX, chunkY), copy);
        Grid g = grid;
        if (g != null) recompute(g, chunkX, chunkY, copy);
    }

    /** One tile changed (building, breaking, burning). */
    public void liveTile(int x, int y, int bits) {
        if (state == State.OFF) return;
        int cx = Math.floorDiv(x, 8), cy = Math.floorDiv(y, 8);
        long k = WorldSource.key(cx, cy);
        byte[] old = live.get(k);
        byte[] chunk = old != null ? old.clone() : baseChunk(cx, cy);
        chunk[Math.floorMod(y, 8) * 8 + Math.floorMod(x, 8)] = (byte) bits;
        live.put(k, chunk);
        tileChanges++;
        Grid g = grid;
        if (g != null) recompute(g, cx, cy, chunk);
    }

    private byte[] baseChunk(int cx, int cy) {
        byte[] out = new byte[64];
        TileCache tc = tiles;
        if (tc == null) {
            Arrays.fill(out, (byte) CollisionBits.NO_DATA);
            return out;
        }
        byte[] cell = tc.cell(Math.floorDiv(cx, 32), Math.floorDiv(cy, 32));
        int ox = Math.floorMod(cx, 32) * 8, oy = Math.floorMod(cy, 32) * 8;
        for (int ty = 0; ty < 8; ty++) System.arraycopy(cell, (oy + ty) * 256 + ox, out, ty * 8, 8);
        return out;
    }

    private static void recompute(Grid g, int cx, int cy, byte[] chunk) {
        if (g.index(cx, cy) < 0) return;
        int zone = ChunkInfo.zone(g.info(cx, cy));
        g.set(cx, cy, ChunkInfo.summarise(chunk, 0, 8, zone), ChunkInfo.masks(chunk, 0, 8));
    }

    // ---- reporting and debug ----

    public long memoryBytes() {
        Grid g = grid;
        TileCache tc = tiles;
        return (g == null ? 0 : g.memoryBytes()) + live.size() * 80L + (tc == null ? 0 : tc.size() * 65_536L);
    }

    public String report() {
        StringBuilder sb = new StringBuilder("world map ").append(state);
        BuildReport r = report;
        if (r != null) {
            sb.append(String.format(Locale.ROOT, " | built in %.1f s | %.1f MB", r.millis / 1000.0, memoryBytes() / 1048576.0));
            sb.append(" | chunks:");
            for (Terrain t : Terrain.values()) {
                int n = r.terrainCounts[t.code];
                if (n > 0) sb.append(' ').append(t.name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(' ').append(n).append(',');
            }
            sb.setLength(sb.length() - 1);
            sb.append(" | cells: ").append(r.cellsFromSave).append(" from save, ").append(r.cellsFromMap).append(" from map, ")
                .append(r.cellsMissing).append(" missing, ").append(r.filesDamaged).append(" damaged");
            if (!r.damaged.isEmpty()) sb.append(" (").append(String.join("; ", r.damaged)).append(')');
            sb.append(" | roads from files: ").append(r.streets).append(" streets, ").append(r.worldRoads)
                .append(" world-map roads, ").append(r.trails).append(" trails");
            if (!r.roadProblems.isEmpty()) sb.append(" (").append(String.join("; ", r.roadProblems)).append(')');
        }
        sb.append(" | live: ").append(live.size()).append(" chunks, ").append(tileChanges).append(" tile changes");
        return sb.toString();
    }

    /**
     * Walkable chunks within two chunks of the map's outer edge (no-data chunks or the grid's border): where newcomers
     * arrive later in a game. Keys chunkX << 32 | chunkY; empty if the map is not ready.
     */
    public long[] edgeChunks() {
        Grid g = grid;
        if (g == null) return new long[0];
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        for (int iy = 0; iy < g.height; iy++) {
            for (int ix = 0; ix < g.width; ix++) {
                int cx = g.originX + ix, cy = g.originY + iy;
                boolean border = ix < 2 || iy < 2 || ix >= g.width - 2 || iy >= g.height - 2;
                if (g.terrain(cx, cy) == Terrain.UNKNOWN) {
                    boolean nextToKnown = false;
                    for (Dir d : Dir.values()) {
                        if (g.index(cx + d.dx, cy + d.dy) >= 0 && g.terrain(cx + d.dx, cy + d.dy) != Terrain.UNKNOWN) nextToKnown = true;
                    }
                    if (!nextToKnown) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) addEdge(g, cx + dx, cy + dy, out);
                    }
                } else if (border) {
                    addEdge(g, cx, cy, out);
                }
            }
        }
        long[] keys = new long[out.size()];
        int i = 0;
        for (long k : out) keys[i++] = k;
        return keys;
    }

    private static void addEdge(Grid g, int cx, int cy, java.util.Set<Long> out) {
        if (g.index(cx, cy) < 0 || g.terrain(cx, cy) == Terrain.UNKNOWN || g.blocked(cx, cy)) return;
        out.add(WorldSource.key(cx, cy));
    }

    /** Terrain codes every step tiles (step rounded up to a multiple of 8) over the rectangle; null if not ready. */
    public TerrainSample terrainSample(int minX, int minY, int maxX, int maxY, int step) {
        Grid g = grid;
        if (g == null) return null;
        int s = Math.max(8, (step + 7) / 8 * 8);
        int x0 = Math.floorDiv(minX, s) * s, y0 = Math.floorDiv(minY, s) * s;
        int cols = Math.max(0, (maxX - x0 + s - 1) / s), rows = Math.max(0, (maxY - y0 + s - 1) / s);
        if ((long) cols * rows > MAX_SAMPLE_CELLS) return null;
        int[] codes = new int[cols * rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                codes[r * cols + c] = g.terrain(Math.floorDiv(x0 + c * s, 8), Math.floorDiv(y0 + r * s, 8)).code;
            }
        }
        return new TerrainSample(x0, y0, s, cols, rows, codes);
    }

    /** (x, y, bits) for each tile in [minX, maxX) x [minY, maxY) with any bit set, at most max tiles; empty if too big. */
    public int[] tileSample(int minX, int minY, int maxX, int maxY, int max) {
        if (grid == null || maxX - minX > MAX_TILE_SAMPLE_SIDE || maxY - minY > MAX_TILE_SAMPLE_SIDE || max <= 0) return new int[0];
        int[] out = new int[Math.min(max, (maxX - minX) * Math.max(0, maxY - minY)) * 3];
        int n = 0;
        for (int y = minY; y < maxY && n < out.length; y++) {
            for (int x = minX; x < maxX && n < out.length; x++) {
                int b = tileBits(x, y);
                if (b == 0) continue;
                out[n++] = x;
                out[n++] = y;
                out[n++] = b;
            }
        }
        return Arrays.copyOf(out, n);
    }
}
