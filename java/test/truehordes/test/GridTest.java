package truehordes.test;

import truehordes.world.ChunkInfo;
import truehordes.world.CollisionBits;
import truehordes.world.Dir;
import truehordes.world.Grid;
import truehordes.world.Terrain;

public final class GridTest {
    private static final int OPEN = ChunkInfo.masks(FakeCells.fill(0), 0, 8);

    private static Grid openGrid() {
        Grid g = new Grid(100, 200, 3, 3);
        for (int cy = 200; cy < 203; cy++) {
            for (int cx = 100; cx < 103; cx++) {
                g.set(cx, cy, ChunkInfo.summarise(FakeCells.fill(0), 0, 8, Terrain.ROAD.code), OPEN);
            }
        }
        return g;
    }

    public static void testOutsideIsBlocked() {
        Grid g = openGrid();
        Check.equal(-1, g.index(99, 200), "left of grid");
        Check.equal(-1, g.index(-5, -5), "negative");
        Check.equal(Terrain.UNKNOWN, g.terrain(103, 201), "right of grid");
        Check.isTrue(g.blocked(-1, -1), "blocked outside");
        Check.isTrue(Float.isInfinite(g.cost(500, 500)), "infinite outside");
        Check.isTrue(!g.canCross(102, 201, Dir.E), "cannot leave the map");
    }

    public static void testEdgesBothWays() {
        Grid g = openGrid();
        byte[] wallW = FakeCells.fill(0);
        for (int i = 0; i < 8; i++) wallW[i * 8] = (byte) CollisionBits.WALL_W;
        g.set(101, 201, ChunkInfo.summarise(wallW, 0, 8, Terrain.ROAD.code), ChunkInfo.masks(wallW, 0, 8));
        Check.isTrue(!g.canCross(101, 201, Dir.W), "wall on the west edge");
        Check.isTrue(!g.canCross(100, 201, Dir.E), "same wall seen from the west");
        Check.isTrue(g.canCross(101, 201, Dir.E), "east side open");
        Check.isTrue(g.canCross(101, 201, Dir.N) && g.canCross(101, 201, Dir.S), "north and south open");
        Check.isTrue(!g.blocked(101, 201), "not blocked");
        Check.near(1.0, g.cost(101, 201), 1e-6, "road cost");
    }

    public static void testSolidChunkIsBlocked() {
        Grid g = openGrid();
        byte[] solid = FakeCells.fill(CollisionBits.SOLID);
        g.set(101, 201, ChunkInfo.summarise(solid, 0, 8, Terrain.ROAD.code), ChunkInfo.masks(solid, 0, 8));
        Check.isTrue(g.blocked(101, 201), "blocked");
        Check.isTrue(Float.isInfinite(g.cost(101, 201)), "infinite");
        Check.isTrue(!g.canCross(101, 200, Dir.S), "neighbour cannot enter");
    }

    public static void testNewGridIsUnknown() {
        Grid g = new Grid(0, 0, 2, 2);
        Check.equal(Terrain.UNKNOWN, g.terrain(1, 1), "unloaded");
        Check.isTrue(g.blocked(1, 1), "blocked");
        Check.equal(4 * 2L + 4 * 4L, g.memoryBytes(), "2 + 4 bytes per chunk");
        Check.equal(4, g.terrainCounts()[Terrain.UNKNOWN.code], "counts");
    }
}
