package truehordes.world;

/** Terrain codes sampled every step tiles, row-major, starting at tile (x0, y0). For the debug map. */
public final class TerrainSample {
    public final int x0, y0, step, cols, rows;
    public final int[] codes;

    TerrainSample(int x0, int y0, int step, int cols, int rows, int[] codes) {
        this.x0 = x0;
        this.y0 = y0;
        this.step = step;
        this.cols = cols;
        this.rows = rows;
        this.codes = codes;
    }
}
