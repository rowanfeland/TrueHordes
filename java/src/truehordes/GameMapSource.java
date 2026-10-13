package truehordes;

import java.util.ArrayList;
import java.util.List;
import truehordes.population.MapSource;
import zombie.iso.BuildingDef;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.LotHeader;
import zombie.iso.RoomDef;

/**
 * The map data seeding needs, copied on the main thread (the seeding itself runs on a background thread): every
 * chunk's zombie intensity (LotHeader.getZombieIntensityForChunk, the value the game gives the native population;
 * B42's Voronoi noise already applied) and every building's rooms.
 */
final class GameMapSource implements MapSource {
    private final int minChunkX, minChunkY, width, height;
    private final short[] intensity;
    private final List<RoomInfo> rooms = new ArrayList<>();

    GameMapSource(IsoMetaGrid grid) {
        minChunkX = grid.minX * 32;
        minChunkY = grid.minY * 32;
        width = (grid.maxX - grid.minX + 1) * 32;
        height = (grid.maxY - grid.minY + 1) * 32;
        intensity = new short[width * height];
        for (int cellY = grid.minY; cellY <= grid.maxY; cellY++) {
            for (int cellX = grid.minX; cellX <= grid.maxX; cellX++) {
                IsoMetaCell cell = grid.getCellData(cellX, cellY);
                LotHeader info = cell == null ? null : cell.info;
                for (int wy = 0; wy < 32; wy++) {
                    for (int wx = 0; wx < 32; wx++) {
                        int v = LotHeader.getZombieIntensityForChunk(info, wx, wy);
                        int ix = cellX * 32 + wx - minChunkX, iy = cellY * 32 + wy - minChunkY;
                        intensity[iy * width + ix] = (short) v;
                    }
                }
            }
        }
        for (BuildingDef b : grid.getBuildings()) {
            int roomCount = b.rooms.size();
            for (RoomDef r : b.rooms) {
                int[] rects = new int[r.rects.size() * 4];
                for (int i = 0; i < r.rects.size(); i++) {
                    RoomDef.RoomRect rr = r.rects.get(i);
                    rects[i * 4] = rr.x;
                    rects[i * 4 + 1] = rr.y;
                    rects[i * 4 + 2] = rr.w;
                    rects[i * 4 + 3] = rr.h;
                }
                rooms.add(new RoomInfo(r.level, r.getArea(), roomCount, intensity(Math.floorDiv(r.x, 8), Math.floorDiv(r.y, 8)),
                    r.isEmptyOutside(), rects));
            }
        }
    }

    @Override public int minChunkX() { return minChunkX; }

    @Override public int minChunkY() { return minChunkY; }

    @Override public int maxChunkX() { return minChunkX + width - 1; }

    @Override public int maxChunkY() { return minChunkY + height - 1; }

    @Override
    public int intensity(int chunkX, int chunkY) {
        int ix = chunkX - minChunkX, iy = chunkY - minChunkY;
        if (ix < 0 || iy < 0 || ix >= width || iy >= height) return -1;
        return intensity[iy * width + ix];
    }

    @Override public List<RoomInfo> rooms() { return rooms; }
}
