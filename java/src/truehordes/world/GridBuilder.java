package truehordes.world;

import java.util.function.BooleanSupplier;

/** Builds the coarse grid for a whole map from its collision files and zones. Runs on the build thread. */
public final class GridBuilder {
    private GridBuilder() {}

    /** Null if cancelled. */
    public static Grid build(WorldSource src, BooleanSupplier cancelled, BuildReport report) {
        long t0 = System.nanoTime();
        int originX = src.minCellX * 32, originY = src.minCellY * 32;
        int width = (src.maxCellX - src.minCellX + 1) * 32, height = (src.maxCellY - src.minCellY + 1) * 32;
        Grid g = new Grid(originX, originY, width, height);
        byte[] zones = ZoneRaster.rasterise(src.zones, originX, originY, width, height);
        for (int cy = 0; cy < height; cy++) {
            for (int cx = 0; cx < width; cx++) {
                int z = zones[cy * width + cx];
                if (z != 0) g.set(originX + cx, originY + cy, ChunkInfo.withZone(ChunkInfo.UNLOADED, z), 0);
            }
        }
        for (int cellY = src.minCellY; cellY <= src.maxCellY; cellY++) {
            for (int cellX = src.minCellX; cellX <= src.maxCellX; cellX++) {
                if (cancelled.getAsBoolean()) return null;
                byte[] tiles = CellSource.load(src, cellX, cellY, report);
                if (tiles == null) continue;
                for (int wy = 0; wy < 32; wy++) {
                    for (int wx = 0; wx < 32; wx++) {
                        int cx = cellX * 32 + wx, cy = cellY * 32 + wy;
                        int offset = wy * 8 * 256 + wx * 8;
                        int zone = zones[(cy - originY) * width + (cx - originX)];
                        g.set(cx, cy, ChunkInfo.summarise(tiles, offset, 256, zone), ChunkInfo.masks(tiles, offset, 256));
                    }
                }
            }
        }
        report.terrainCounts = g.terrainCounts();
        report.memoryBytes = g.memoryBytes();
        report.millis = (System.nanoTime() - t0) / 1_000_000L;
        return g;
    }
}
