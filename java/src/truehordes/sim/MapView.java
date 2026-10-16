package truehordes.sim;

/** What the off-screen simulation knows about the map, per 8x8-tile chunk (chunk coordinates). */
public interface MapView {
    /** Walking preference: 1 road, 2 trail, 3 farmland, 4 open ground or forest; 0 = cannot be entered. */
    int rank(int chunkX, int chunkY);

    /** Inside the playable map (not void, not past the map's edge). */
    boolean inMap(int chunkX, int chunkY);

    /** A passable chunk squeezed between buildings (an alley or a gap): hordes shed members here. */
    boolean tight(int chunkX, int chunkY);

    /** A building chunk (towns are where these are dense; round 8). */
    default boolean building(int chunkX, int chunkY) { return false; }
}
