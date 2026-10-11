package truehordes.world;

/** Tile bits and chunk types of the game's chunkdata_X_Y.bin collision files (checked against 42.21's map files). */
public final class CollisionBits {
    public static final int SOLID = 1;
    public static final int WALL_N = 2;
    public static final int WALL_W = 4;
    public static final int WATER = 8;
    public static final int ROOM = 16;
    /** The mod's own marker for "no map here / not read" (never in the game's files). */
    public static final int NO_DATA = 0x80;
    /** A zombie cannot stand on a tile with any of these (water blocks: D16; buildings are sealed in this data). */
    public static final int NOT_WALKABLE = SOLID | WATER | ROOM | NO_DATA;

    public static final int CHUNK_EMPTY = 0;
    public static final int CHUNK_SOLID = 1;
    public static final int CHUNK_REGULAR = 2;
    public static final int CHUNK_WATER = 3;
    public static final int CHUNK_ROOM = 4;
    /** Not in the game's Java reader; found in the vanilla map's edge strips. Means "no map here". */
    public static final int CHUNK_VOID = 5;

    public static final int CELL_TILES = 256;
    public static final int CHUNK_TILES = 8;
    public static final int CHUNKS_PER_CELL = 32;

    private CollisionBits() {}

    public static boolean walkable(int bits) {
        return (bits & NOT_WALKABLE) == 0;
    }
}
