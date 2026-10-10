package truehordes.store;

/**
 * What the debug map draws. Points mode: data holds x,y pairs. Density mode: data holds
 * cellOriginX, cellOriginY, count triples for square cells of cellSize tiles.
 */
public final class Snapshot {
    public final boolean density;
    public final int cellSize;
    public final float[] data;
    public final int items;
    public final int total;
    public final int maxCount;

    public Snapshot(boolean density, int cellSize, float[] data, int items, int total, int maxCount) {
        this.density = density;
        this.cellSize = cellSize;
        this.data = data;
        this.items = items;
        this.total = total;
        this.maxCount = maxCount;
    }
}
