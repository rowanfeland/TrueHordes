package truehordes.world;

/**
 * One chunk's summary packed into a short, and its border masks packed into an int.
 * Summary: bits 0-6 walkable tiles (0-64); bit 7 water majority; bit 8 room majority; bit 9 no-data majority;
 * bits 10-13 the covering zone's Terrain code (0 none). Majority means more than 32 of 64 tiles.
 * Masks: byte 0 north entry (top-row tile walkable with no north wall), byte 1 south exit (bottom-row tile walkable),
 * byte 2 west entry (left-column tile walkable with no west wall), byte 3 east exit (right-column tile walkable);
 * bit i is column i (north, south) or row i (west, east). Walls sit on a tile's own north and west edges.
 */
public final class ChunkInfo {
    public static final int OPEN_MASK = 0x7F;
    public static final int WATER = 1 << 7;
    public static final int ROOM = 1 << 8;
    public static final int NO_DATA = 1 << 9;
    public static final int ZONE_SHIFT = 10;
    /** A chunk nothing has been read for. */
    public static final short UNLOADED = (short) NO_DATA;

    private ChunkInfo() {}

    /** tiles[offset + ty * stride + tx] for the chunk's 8x8 tiles. */
    public static short summarise(byte[] tiles, int offset, int stride, int zoneCode) {
        int open = 0, water = 0, room = 0, none = 0;
        for (int ty = 0; ty < 8; ty++) {
            int row = offset + ty * stride;
            for (int tx = 0; tx < 8; tx++) {
                int b = tiles[row + tx] & 0xFF;
                if ((b & CollisionBits.NO_DATA) != 0) { none++; continue; }
                if ((b & CollisionBits.WATER) != 0) water++;
                if ((b & CollisionBits.ROOM) != 0) room++;
                if (CollisionBits.walkable(b)) open++;
            }
        }
        int info = open;
        if (water > 32) info |= WATER;
        if (room > 32) info |= ROOM;
        if (none > 32) info |= NO_DATA;
        return (short) (info | ((zoneCode & 0xF) << ZONE_SHIFT));
    }

    public static int masks(byte[] tiles, int offset, int stride) {
        int n = 0, s = 0, w = 0, e = 0;
        for (int i = 0; i < 8; i++) {
            int top = tiles[offset + i] & 0xFF;
            if (CollisionBits.walkable(top) && (top & CollisionBits.WALL_N) == 0) n |= 1 << i;
            int bottom = tiles[offset + 7 * stride + i] & 0xFF;
            if (CollisionBits.walkable(bottom)) s |= 1 << i;
            int left = tiles[offset + i * stride] & 0xFF;
            if (CollisionBits.walkable(left) && (left & CollisionBits.WALL_W) == 0) w |= 1 << i;
            int right = tiles[offset + i * stride + 7] & 0xFF;
            if (CollisionBits.walkable(right)) e |= 1 << i;
        }
        return n | (s << 8) | (w << 16) | (e << 24);
    }

    public static int open(short info) { return info & OPEN_MASK; }

    public static int zone(short info) { return ((info & 0xFFFF) >>> ZONE_SHIFT) & 0xF; }

    public static short withZone(short info, int zoneCode) {
        return (short) ((info & ~(0xF << ZONE_SHIFT)) | ((zoneCode & 0xF) << ZONE_SHIFT));
    }

    /** From chunk a into its north neighbour: a's top row (no north wall) meets the neighbour's bottom row. */
    public static boolean crossNorth(int a, int north) {
        return (a & 0xFF & (north >>> 8)) != 0;
    }

    /** From chunk a into its west neighbour: a's left column (no west wall) meets the neighbour's right column. */
    public static boolean crossWest(int a, int west) {
        return ((a >>> 16) & 0xFF & (west >>> 24)) != 0;
    }
}
