package truehordes.world;

/** What a chunk is, for walking. Codes are stored in ChunkInfo (4 bits). Costs are placeholders until sub-project 4. */
public enum Terrain {
    UNKNOWN(0, 0, Float.POSITIVE_INFINITY, false),
    ROAD(1, 7, 1f, true),
    TOWN(2, 6, 2f, false),
    FARM(3, 4, 3f, false),
    OPEN(4, 1, 3f, false),
    FOREST(5, 2, 4f, false),
    DEEP_FOREST(6, 3, 6f, false),
    BUILDING(7, 0, Float.POSITIVE_INFINITY, false),
    WATER(8, 0, Float.POSITIVE_INFINITY, false),
    /** Dirt paths and forest tracks (the world map's highway=trail). */
    TRAIL(9, 5, 1.5f, true);

    public final int code;
    /** Which zone wins where zones overlap (higher wins); 0 for kinds that never come from a zone. */
    public final int zonePriority;
    public final float baseCost;
    /** Marks a chunk if it covers any of its tiles (narrow roads and trails are never lost); others need the centre. */
    public final boolean anyTile;

    private static final Terrain[] BY_CODE = new Terrain[16];

    static {
        for (Terrain t : values()) BY_CODE[t.code] = t;
    }

    Terrain(int code, int zonePriority, float baseCost, boolean anyTile) {
        this.code = code;
        this.zonePriority = zonePriority;
        this.baseCost = baseCost;
        this.anyTile = anyTile;
    }

    public static Terrain ofCode(int code) {
        Terrain t = code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
        return t == null ? UNKNOWN : t;
    }

    /** The game's zone type names (objects.lua); null for types that do not affect walking. */
    public static Terrain fromZoneType(String type) {
        if (type == null) return null;
        switch (type) {
            case "Nav": return ROAD;
            case "TownZone": return TOWN;
            case "Farm":
            case "FarmLand":
            case "Ranch": return FARM;
            case "Forest": return FOREST;
            case "DeepForest": return DEEP_FOREST;
            case "Vegitation":
            case "TrailerPark": return OPEN;
            default: return null;
        }
    }

    /** No data, then water, then building, then the zone (no zone = open). */
    public static Terrain classify(short info) {
        if ((info & ChunkInfo.NO_DATA) != 0) return UNKNOWN;
        if ((info & ChunkInfo.WATER) != 0) return WATER;
        if ((info & ChunkInfo.ROOM) != 0) return BUILDING;
        int zone = ChunkInfo.zone(info);
        return zone == 0 ? OPEN : ofCode(zone);
    }

    /** Base cost, rising to 3x as walkable tiles drop to none. Blocked chunks are handled by Grid.cost. */
    public static float cost(short info) {
        Terrain t = classify(info);
        if (Float.isInfinite(t.baseCost)) return Float.POSITIVE_INFINITY;
        return t.baseCost * (1f + (64 - ChunkInfo.open(info)) / 32f);
    }
}
