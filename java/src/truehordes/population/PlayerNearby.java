package truehordes.population;

/** Whether a player has a cell or chunk loaded right now (no respawn there, as in vanilla). */
public interface PlayerNearby {
    boolean nearCell(int cellX, int cellY);

    boolean nearChunk(int chunkX, int chunkY);
}
