package truehordes.test;

import truehordes.world.ChunkInfo;
import truehordes.world.CollisionBits;
import truehordes.world.Terrain;

public final class ChunkInfoTest {
    private static short summary(byte[] t, Terrain zone) { return ChunkInfo.summarise(t, 0, 8, zone.code); }

    public static void testOpenCountAndZone() {
        byte[] t = FakeCells.fill(0);
        t[0] = (byte) CollisionBits.SOLID;
        t[1] = (byte) CollisionBits.WATER;
        t[2] = (byte) CollisionBits.WALL_N; // a wall on the edge still leaves the tile walkable
        short s = summary(t, Terrain.FOREST);
        Check.equal(62, ChunkInfo.open(s), "open tiles");
        Check.equal(Terrain.FOREST.code, ChunkInfo.zone(s), "zone kept");
        Check.equal(Terrain.FOREST, Terrain.classify(s), "classified by zone");
    }

    public static void testMajorities() {
        byte[] water = FakeCells.fill(CollisionBits.WATER);
        Check.equal(Terrain.WATER, Terrain.classify(summary(water, Terrain.ROAD)), "water beats a road zone");
        byte[] room = FakeCells.fill(CollisionBits.ROOM);
        Check.equal(Terrain.BUILDING, Terrain.classify(summary(room, Terrain.TOWN)), "room majority is a building");
        byte[] none = FakeCells.fill(CollisionBits.NO_DATA);
        Check.equal(Terrain.UNKNOWN, Terrain.classify(summary(none, Terrain.ROAD)), "no data is unknown");
        byte[] half = FakeCells.fill(0);
        for (int i = 0; i < 32; i++) half[i] = (byte) CollisionBits.WATER;
        Check.equal(Terrain.OPEN, Terrain.classify(summary(half, Terrain.UNKNOWN)), "exactly half water is not water; no zone is open");
    }

    public static void testWithZoneKeepsTileFlags() {
        short s = summary(FakeCells.fill(CollisionBits.WATER), Terrain.UNKNOWN);
        short z = ChunkInfo.withZone(s, Terrain.ROAD.code);
        Check.equal(Terrain.ROAD.code, ChunkInfo.zone(z), "zone set");
        Check.equal(Terrain.WATER, Terrain.classify(z), "still water");
        Check.equal(Terrain.UNKNOWN, Terrain.classify(ChunkInfo.UNLOADED), "unloaded is unknown");
    }

    public static void testMasks() {
        byte[] t = FakeCells.fill(0);
        t[0 * 8 + 3] = (byte) CollisionBits.WALL_N;   // top row, column 3: cannot leave north
        t[7 * 8 + 5] = (byte) CollisionBits.SOLID;    // bottom row, column 5: cannot stand
        t[2 * 8 + 0] = (byte) CollisionBits.WALL_W;   // left column, row 2: cannot leave west
        t[6 * 8 + 7] = (byte) CollisionBits.WATER;    // right column, row 6: cannot stand
        int m = ChunkInfo.masks(t, 0, 8);
        Check.equal(0xFF & ~(1 << 3), m & 0xFF, "north entry");
        Check.equal(0xFF & ~(1 << 5), (m >>> 8) & 0xFF, "south exit");
        Check.equal(0xFF & ~(1 << 2), (m >>> 16) & 0xFF, "west entry");
        Check.equal(0xFF & ~(1 << 6), (m >>> 24) & 0xFF, "east exit");
    }

    public static void testMasksFromCellLayout() {
        byte[] cell = new byte[256 * 256];
        cell[(8 + 0) * 256 + 16 + 4] = (byte) CollisionBits.WALL_N; // chunk (2,1), top row column 4
        int m = ChunkInfo.masks(cell, 8 * 256 + 16, 256);
        Check.equal(0xFF & ~(1 << 4), m & 0xFF, "offset and stride respected");
    }

    public static void testCrossingNeedsBothSides() {
        int open = ChunkInfo.masks(FakeCells.fill(0), 0, 8);
        int blocked = ChunkInfo.masks(FakeCells.fill(CollisionBits.SOLID), 0, 8);
        byte[] walled = FakeCells.fill(0);
        for (int i = 0; i < 8; i++) walled[i] = (byte) CollisionBits.WALL_N;
        int wallN = ChunkInfo.masks(walled, 0, 8);
        Check.isTrue(ChunkInfo.crossNorth(open, open), "open to open");
        Check.isTrue(!ChunkInfo.crossNorth(open, blocked), "into solid");
        Check.isTrue(!ChunkInfo.crossNorth(wallN, open), "wall along the north edge");
        byte[] oneGap = FakeCells.fill(0);
        for (int i = 1; i < 8; i++) oneGap[i * 8] = (byte) CollisionBits.WALL_W;
        Check.isTrue(ChunkInfo.crossWest(ChunkInfo.masks(oneGap, 0, 8), open), "one gap is enough");
    }

    public static void testZoneTypesAndCosts() {
        Check.equal(Terrain.ROAD, Terrain.fromZoneType("Nav"), "Nav");
        Check.equal(Terrain.TOWN, Terrain.fromZoneType("TownZone"), "TownZone");
        Check.equal(Terrain.FARM, Terrain.fromZoneType("FarmLand"), "FarmLand");
        Check.equal(Terrain.FARM, Terrain.fromZoneType("Ranch"), "Ranch");
        Check.equal(Terrain.DEEP_FOREST, Terrain.fromZoneType("DeepForest"), "DeepForest");
        Check.equal(Terrain.OPEN, Terrain.fromZoneType("Vegitation"), "Vegitation (the game's spelling)");
        Check.equal(null, Terrain.fromZoneType("ParkingStall"), "ignored type");
        Check.isTrue(Terrain.ROAD.zonePriority > Terrain.TOWN.zonePriority, "road beats town");
        Check.isTrue(Terrain.FARM.zonePriority > Terrain.DEEP_FOREST.zonePriority, "farm beats deep forest");
        Check.isTrue(Terrain.DEEP_FOREST.zonePriority > Terrain.FOREST.zonePriority, "deep forest beats forest");
        Check.isTrue(Terrain.FOREST.zonePriority > Terrain.OPEN.zonePriority, "forest beats open");
        short road = ChunkInfo.summarise(FakeCells.fill(0), 0, 8, Terrain.ROAD.code);
        Check.near(1.0, Terrain.cost(road), 1e-6, "open road costs its base");
        byte[] half = FakeCells.fill(0);
        for (int i = 0; i < 32; i++) half[i] = (byte) CollisionBits.SOLID;
        Check.near(2.0, Terrain.cost(ChunkInfo.summarise(half, 0, 8, Terrain.ROAD.code)), 1e-6, "half open doubles");
        Check.isTrue(Float.isInfinite(Terrain.cost(ChunkInfo.UNLOADED)), "unknown is infinite");
    }
}
