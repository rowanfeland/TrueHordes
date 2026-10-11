package truehordes.world;

import java.util.Arrays;

/**
 * The coarse map: one ChunkInfo summary and one border-mask int per 8x8-tile chunk. Coordinates are chunk coordinates
 * (tile / 8). Written by one thread at a time (the builder before publishing, then the main thread); entries are single
 * array elements, so readers see an old or a new value, never a torn one.
 */
public final class Grid {
    public final int originX, originY, width, height;
    private final short[] info;
    private final int[] masks;

    public Grid(int originX, int originY, int width, int height) {
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.info = new short[width * height];
        this.masks = new int[width * height];
        Arrays.fill(info, ChunkInfo.UNLOADED);
    }

    public int index(int cx, int cy) {
        int ix = cx - originX, iy = cy - originY;
        if (ix < 0 || iy < 0 || ix >= width || iy >= height) return -1;
        return iy * width + ix;
    }

    public short info(int cx, int cy) {
        int i = index(cx, cy);
        return i < 0 ? ChunkInfo.UNLOADED : info[i];
    }

    public int masks(int cx, int cy) {
        int i = index(cx, cy);
        return i < 0 ? 0 : masks[i];
    }

    public void set(int cx, int cy, short chunkInfo, int chunkMasks) {
        int i = index(cx, cy);
        if (i < 0) return;
        info[i] = chunkInfo;
        masks[i] = chunkMasks;
    }

    public Terrain terrain(int cx, int cy) { return Terrain.classify(info(cx, cy)); }

    public boolean canCross(int cx, int cy, Dir d) {
        int a = masks(cx, cy), b = masks(cx + d.dx, cy + d.dy);
        switch (d) {
            case N: return ChunkInfo.crossNorth(a, b);
            case S: return ChunkInfo.crossNorth(b, a);
            case W: return ChunkInfo.crossWest(a, b);
            default: return ChunkInfo.crossWest(b, a);
        }
    }

    public boolean blocked(int cx, int cy) {
        for (Dir d : Dir.values()) if (canCross(cx, cy, d)) return false;
        return true;
    }

    public float cost(int cx, int cy) {
        return blocked(cx, cy) ? Float.POSITIVE_INFINITY : Terrain.cost(info(cx, cy));
    }

    public long memoryBytes() { return info.length * 2L + masks.length * 4L; }

    /** Number of chunks of each terrain, indexed by Terrain.code. */
    public int[] terrainCounts() {
        int[] counts = new int[16];
        for (short s : info) counts[Terrain.classify(s).code]++;
        return counts;
    }
}
