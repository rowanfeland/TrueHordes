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
    /** Walking target kept from the real zombie; dropped once the simulation walks it elsewhere. */
    public int targetX;
    public int targetY;
    /** The zombie's ModData as saved by the game's Lua table serialiser; null = none. Never modified. */
    public final byte[] modData;
    /** Seeded inside a building (option BuildingZombiesSeeded). */
    public final boolean building;

    /** ZombieStateFlag.FakeDead (popman.ZombieStateFlag, 42.21). */
    public static final int FLAG_FAKE_DEAD = 8;

    // Walk state: owned by the off-screen simulation, changed only under the core's lock.
    /** Heading in radians (0 = +x, east). */
    public float heading;
    /** In-game minutes left standing still. */
    public float pauseMinutes;
    /** Never joins or follows a horde (rolled once, see strRolled). */
    public boolean straggler;
    public boolean strRolled;
    /** Who this wanderer trails: 0 none, &gt;0 a record id, &lt;0 minus a horde id. */
    public long followId;
    /** The horde this zombie last belonged to (0 none); lets it rejoin after a spell as a real zombie. */
    public long hordeId;
    /** World hour until which a left-behind or straggling zombie stays put. */
    public double lingerUntilHours;
    /** Standing still in a building or lying fake-dead: does nothing until a sound moves it. */
    public boolean idle;
    /** Scheduling (simulation only, not saved): the in-game minute of its next update and the hour of its last. */
    public long dueMinute = Long.MIN_VALUE;
    /** Heading to a sound it heard (its target) until this world hour (not saved: a reload forgets it). */
    public double investigateUntilHours;
    public double simHours = Double.NaN;

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
        this.heading = headingOf(dir);
        this.idle = building || (stateFlags & FLAG_FAKE_DEAD) != 0;
    }

    /** IsoDirections ordinal (N, NW, W, SW, S, SE, E, NE; y grows south) to radians (0 = east). */
    public static float headingOf(byte dir) {
        return (float) Math.atan2(DIR_Y[dir & 7], DIR_X[dir & 7]);
    }

    /** Radians to the nearest IsoDirections ordinal. */
    public static byte dirOf(float heading) {
        int best = 0;
        double bestDot = -2;
        for (int i = 0; i < 8; i++) {
            double len = Math.hypot(DIR_X[i], DIR_Y[i]);
            double dot = (DIR_X[i] * Math.cos(heading) + DIR_Y[i] * Math.sin(heading)) / len;
            if (dot > bestDot) { bestDot = dot; best = i; }
        }
        return (byte) best;
    }

    private static final int[] DIR_X = {0, -1, -1, -1, 0, 1, 1, 1};
    private static final int[] DIR_Y = {-1, -1, 0, 1, 1, 1, 0, -1};

    public float x() { return x; }

    public float y() { return y; }

    public boolean hasTarget() { return targetX != NO_TARGET; }

    public int chunkX() { return Math.floorDiv((int) Math.floor(x), 8); }

    public int chunkY() { return Math.floorDiv((int) Math.floor(y), 8); }
}
