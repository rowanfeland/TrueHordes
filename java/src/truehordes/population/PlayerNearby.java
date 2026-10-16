package truehordes.population;

/** Whether a player has a cell or chunk loaded right now (no respawn there, as in vanilla). */
public interface PlayerNearby {
    boolean nearCell(int cellX, int cellY);

    boolean nearChunk(int chunkX, int chunkY);

    /**
     * Held zombies anywhere in this chunk become real (sub-project 6: loaded and wholly within the real radius of a
     * player). Defaults to loaded.
     */
    default boolean realChunk(int chunkX, int chunkY) { return nearChunk(chunkX, chunkY); }

    /** A held zombie at (x, y) should be real now (main thread only: it may ask the game what players can see). */
    default boolean realAt(float x, float y) {
        return realChunk(Math.floorDiv((int) Math.floor(x), 8), Math.floorDiv((int) Math.floor(y), 8));
    }
}
