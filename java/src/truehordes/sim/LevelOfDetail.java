package truehordes.sim;

import truehordes.population.PlayerNearby;

/**
 * How often a walker is updated, by how close it is to a player's loaded area (D2): near the loaded edge every
 * in-game minute (a few real seconds), within the cells around a player every 5 minutes, far away every 20 minutes in
 * big steps. A horde costs the same as one wanderer.
 */
public final class LevelOfDetail {
    public static final int EDGE_MINUTES = 1;
    public static final int NEAR_MINUTES = 5;
    public static final int FAR_MINUTES = 20;
    /** Chunks from a loaded chunk that still count as the loaded edge. */
    static final int EDGE_CHUNKS = 2;

    private LevelOfDetail() {}

    /** Update interval in in-game minutes for a walker in chunk (cx, cy). */
    public static int intervalMinutes(PlayerNearby players, int cx, int cy) {
        if (players == null) return FAR_MINUTES;
        for (int dx = -EDGE_CHUNKS; dx <= EDGE_CHUNKS; dx++) {
            for (int dy = -EDGE_CHUNKS; dy <= EDGE_CHUNKS; dy++) {
                if (players.nearChunk(cx + dx, cy + dy)) return EDGE_MINUTES;
            }
        }
        int cellX = Math.floorDiv(cx, 32), cellY = Math.floorDiv(cy, 32);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (players.nearCell(cellX + dx, cellY + dy)) return NEAR_MINUTES;
            }
        }
        return FAR_MINUTES;
    }
}
