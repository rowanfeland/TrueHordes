package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import truehordes.util.Log;
import truehordes.world.CollisionBits;
import truehordes.world.Dir;
import truehordes.world.Terrain;
import truehordes.world.TerrainSample;
import truehordes.world.World;
import truehordes.world.WorldSource;
import truehordes.world.ZoneShape;

public final class WorldTest {
    /** Cell (0,0): chunk (0,0) water, chunk (1,0) a wall on the west edge of tile (8,3), the rest empty ground. */
    private static WorldSource source() throws Exception {
        Path dir = Files.createTempDirectory("th-world");
        byte[] wall = FakeCells.fill(0);
        wall[3 * 8] = (byte) CollisionBits.WALL_W;
        Path p = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_WATER).tiles(1, 0, wall).write(dir, "chunkdata_0_0.bin");
        Map<Long, Path> files = new HashMap<>();
        files.put(WorldSource.key(0, 0), p);
        return new WorldSource(0, 0, 0, 0, files, null, List.of(ZoneShape.rect(Terrain.ROAD, 16, 0, 8, 256)));
    }

    private static World built() throws Exception {
        World w = new World();
        w.start(source());
        Check.isTrue(w.awaitBuilt(10_000), "build finished");
        w.tick();
        return w;
    }

    public static void testNotReadyUntilPublished() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = new World();
            Check.equal(World.State.OFF, w.state(), "off before start");
            Check.isTrue(w.blockedAt(20, 4), "blocked while off");
            w.start(source());
            Check.isTrue(w.awaitBuilt(10_000), "build finished");
            Check.isTrue(!w.ready(), "not published before tick");
            w.tick();
            Check.isTrue(w.ready(), "published");
            Check.equal(World.State.READY, w.state(), "ready");
            w.stop();
            Check.equal(World.State.OFF, w.state(), "off after stop");
            Check.isTrue(!w.ready(), "dropped");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testCoarseQueries() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            Check.equal(Terrain.WATER, w.terrainAt(3, 3), "water chunk");
            Check.isTrue(w.blockedAt(3, 3), "water blocks (D16)");
            Check.equal(Terrain.ROAD, w.terrainAt(17, 100), "road zone");
            Check.near(1.0, w.costAt(17, 100), 1e-6, "road cost");
            Check.equal(Terrain.OPEN, w.terrainAt(100, 100), "no zone");
            Check.isTrue(w.canCross(1, 0, Dir.E), "chunk (1,0) to the east");
            Check.isTrue(!w.canCross(1, 0, Dir.W), "chunk (1,0) to the water");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testTileQueries() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            Check.isTrue(!w.walkable(2, 2), "water tile");
            Check.isTrue(w.walkable(9, 3), "ground");
            Check.isTrue(!w.canStep(8, 3, 7, 3), "into water");
            Check.isTrue(w.canStep(9, 4, 9, 5), "open step south");
            Check.isTrue(!w.canStep(9, 4, 9, 4), "not a step");
            Check.isTrue(!w.canStep(9, 4, 11, 4), "too far");
            Check.isTrue(w.canStep(10, 10, 11, 11), "open diagonal");
            Check.equal(CollisionBits.WALL_W, w.tileBits(8, 3), "wall tile bits");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testWallBlocksStepAndDiagonal() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            w.liveTile(12, 12, CollisionBits.WALL_W); // wall between (11,12) and (12,12)
            Check.isTrue(!w.canStep(11, 12, 12, 12), "east into the wall");
            Check.isTrue(!w.canStep(12, 12, 11, 12), "west through the wall");
            Check.isTrue(!w.canStep(11, 11, 12, 12), "diagonal past the wall");
            Check.isTrue(w.canStep(11, 11, 12, 11), "the row above is open");
            w.liveTile(20, 20, CollisionBits.WALL_N); // wall between (20,19) and (20,20)
            Check.isTrue(!w.canStep(20, 19, 20, 20), "south into the wall");
            Check.isTrue(!w.canStep(20, 20, 20, 19), "north through the wall");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testLiveChunkRewritesEntry() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            Check.isTrue(!w.blockedAt(40, 40), "open before");
            w.liveChunk(5, 5, FakeCells.fill(CollisionBits.SOLID));
            Check.isTrue(w.blockedAt(40, 40), "blocked after the chunk changed");
            Check.isTrue(!w.walkable(41, 41), "tile detail follows");
            Check.equal(Terrain.OPEN, w.terrainAt(40, 40), "zone kept (open: no zone)");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testLiveChangeDuringBuildIsAppliedOnPublish() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = new World();
            w.start(source());
            w.liveChunk(6, 6, FakeCells.fill(CollisionBits.SOLID));
            w.liveTile(60, 60, CollisionBits.SOLID);
            Check.isTrue(w.awaitBuilt(10_000), "build finished");
            w.tick();
            Check.isTrue(w.blockedAt(50, 50), "chunk change kept");
            Check.isTrue(!w.walkable(60, 60), "tile change kept");
            Check.isTrue(w.walkable(61, 60), "neighbour tile from the file");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testRestartIgnoresOldBuild() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = new World();
            w.start(source());
            Path dir = Files.createTempDirectory("th-world2");
            Path p = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_SOLID).write(dir, "chunkdata_0_0.bin");
            Map<Long, Path> files = new HashMap<>();
            files.put(WorldSource.key(0, 0), p);
            w.stop();
            w.start(new WorldSource(0, 0, 0, 0, files, null, List.of()));
            Check.isTrue(w.awaitBuilt(10_000), "second build finished");
            Thread.sleep(200); // give a late first build every chance to publish wrongly
            w.tick();
            Check.isTrue(w.blockedAt(3, 3), "the second world's solid chunk, not the first world's water");
            Check.equal(Terrain.OPEN, w.terrainAt(3, 3), "solid ground without a zone classifies as open");
            Check.equal(Terrain.OPEN, w.terrainAt(17, 100), "no road: the first world's zones are gone");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testQueriesOutsideMap() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            Check.isTrue(w.blockedAt(-10, -10), "negative");
            Check.isTrue(w.blockedAt(5000, 5000), "beyond");
            Check.equal(Terrain.UNKNOWN, w.terrainAt(-1, 3), "unknown");
            Check.isTrue(!w.walkable(-1, -1), "negative tile");
            Check.isTrue(!w.walkable(300, 300), "tile outside");
            Check.isTrue(!w.canStep(-1, 0, 0, 0), "step from outside");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testReportAndSamples() throws Exception {
        Log.setSink(line -> {});
        try {
            World w = built();
            w.liveTile(9, 9, CollisionBits.SOLID);
            String r = w.report();
            Check.isTrue(r.contains("READY"), r);
            Check.isTrue(r.contains("1 from map"), r);
            Check.isTrue(r.contains("1 tile changes"), r);
            Check.isTrue(r.contains("water"), "terrain counts: " + r);
            TerrainSample s = w.terrainSample(0, 0, 32, 16, 8);
            Check.equal(4, s.cols, "cols");
            Check.equal(2, s.rows, "rows");
            Check.equal(Terrain.WATER.code, s.codes[0], "first sample water");
            Check.equal(Terrain.ROAD.code, s.codes[2], "third sample road");
            int[] tiles = w.tileSample(0, 0, 16, 16, 1000);
            boolean sawWall = false;
            for (int i = 0; i < tiles.length; i += 3) {
                if (tiles[i] == 8 && tiles[i + 1] == 3 && tiles[i + 2] == CollisionBits.WALL_W) sawWall = true;
            }
            Check.isTrue(sawWall, "wall tile in the sample");
            Check.isTrue(w.tileSample(0, 0, 16, 16, 3).length <= 9, "capped");
            Check.equal(0, w.tileSample(0, 0, 1000, 1000, 100000).length, "too big an area returns nothing");
        } finally {
            Log.setSink(null);
        }
    }
}
