package truehordes.store;

/**
 * One zombie the mod holds off-screen: the same fields the game itself keeps for an off-screen zombie
 * (ZombiePopulationManager.removeChunkFromWorld → n_addZombie), plus the zombie's mod data. Position changes only
 * through {@link Store#moved}, so the store's indexes stay right.
 */
public final class ZombieRecord {
    /** No walking target. */
    public static final int NO_TARGET = Integer.MIN_VALUE;

    public final long id;
    float x;
    float y;
    public final float z;
    /** IsoDirections ordinal. */
    public final byte dir;
    /** The game's persistent outfit ID; 0 = dress by the area's zombie type when it first becomes real. */
    public final int outfitId;
    /** ZombieStateFlags as an int (crawler, fake dead, can walk, ...). */
    public final int stateFlags;
    /** The game's persistent zombie ID; 0 = none yet (the game assigns one when it becomes real). */
    public final int persistentId;
    public final int targetX;
    public final int targetY;
    /** The zombie's ModData as saved by the game's Lua table serialiser; null = none. Never modified. */
    public final byte[] modData;
    /** Seeded inside a building (option BuildingZombiesSeeded). */
    public final boolean building;

    public ZombieRecord(long id, float x, float y, float z, byte dir, int outfitId, int stateFlags, int persistentId,
                        int targetX, int targetY, byte[] modData, boolean building) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dir = dir;
        this.outfitId = outfitId;
        this.stateFlags = stateFlags;
        this.persistentId = persistentId;
        this.targetX = targetX;
        this.targetY = targetY;
        this.modData = modData;
        this.building = building;
    }

    public float x() { return x; }

    public float y() { return y; }

    public boolean hasTarget() { return targetX != NO_TARGET; }

    public int chunkX() { return Math.floorDiv((int) Math.floor(x), 8); }

    public int chunkY() { return Math.floorDiv((int) Math.floor(y), 8); }
}
