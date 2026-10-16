package truehordes.sim;

import truehordes.world.Terrain;
import truehordes.world.World;

/**
 * The sub-project 2 world map seen by the simulation. Roads (highways included) rank best, then trails, farmland, and
 * open ground, forest and town lots alike; buildings, water, walled-in chunks and void cannot be entered.
 */
public final class WorldMapView implements MapView {
    private final World world;

    public WorldMapView(World world) { this.world = world; }

    @Override
    public int rank(int cx, int cy) {
        if (!world.chunkInMap(cx, cy) || world.chunkBlocked(cx, cy)) return 0;
        return rankOf(world.chunkTerrain(cx, cy));
    }

    static int rankOf(Terrain t) {
        switch (t) {
            case ROAD: return 1;
            case TRAIL: return 2;
            case FARM: return 3;
            case BUILDING: case WATER: return 0;
            default: return 4; // OPEN, FOREST, DEEP_FOREST, TOWN, UNKNOWN
        }
    }

    @Override
    public boolean inMap(int cx, int cy) { return world.chunkInMap(cx, cy); }

    @Override
    public boolean building(int cx, int cy) { return world.chunkTerrain(cx, cy) == Terrain.BUILDING; }

    @Override
    public boolean tight(int cx, int cy) {
        if (rank(cx, cy) == 0) return false;
        int b = 0;
        if (world.chunkTerrain(cx + 1, cy) == Terrain.BUILDING) b++;
        if (world.chunkTerrain(cx - 1, cy) == Terrain.BUILDING) b++;
        if (world.chunkTerrain(cx, cy + 1) == Terrain.BUILDING) b++;
        if (world.chunkTerrain(cx, cy - 1) == Terrain.BUILDING) b++;
        return b >= 2;
    }
}
