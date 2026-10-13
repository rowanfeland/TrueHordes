package truehordes.population;

import java.util.List;

/** The map data seeding needs, captured from the game (GameMapSource) or faked in tests. Chunk = 8x8 tiles. */
public interface MapSource {
    int minChunkX();

    int minChunkY();

    int maxChunkX();

    int maxChunkY();

    /** The chunk's zombie intensity 0-255 (B42's Voronoi noise already applied), or -1 when the map has no data. */
    int intensity(int chunkX, int chunkY);

    List<RoomInfo> rooms();

    /** One room of a building. rects = x, y, w, h, ... in tiles; level = floor (z). */
    record RoomInfo(int level, int area, int buildingRoomCount, int chunkIntensity, boolean emptyOutside, int[] rects) {}
}
