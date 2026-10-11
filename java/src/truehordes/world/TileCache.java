package truehordes.world;

import java.util.LinkedHashMap;
import java.util.Map;

/** The most recently used cells' tiles (same file choice as the build). Thread-safe. */
final class TileCache {
    static final int MAX_CELLS = 64;

    private final WorldSource src;
    private final LinkedHashMap<Long, byte[]> cells = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) { return size() > MAX_CELLS; }
    };

    TileCache(WorldSource src) { this.src = src; }

    synchronized byte[] cell(int cx, int cy) {
        long k = WorldSource.key(cx, cy);
        byte[] tiles = cells.get(k);
        if (tiles == null) {
            boolean inside = cx >= src.minCellX && cx <= src.maxCellX && cy >= src.minCellY && cy <= src.maxCellY;
            tiles = inside ? CellSource.load(src, cx, cy, new BuildReport()) : null;
            if (tiles == null) tiles = CellReader.noData();
            cells.put(k, tiles);
        }
        return tiles;
    }

    synchronized int size() { return cells.size(); }
}
