package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import truehordes.util.Log;
import truehordes.world.BuildReport;
import truehordes.world.CollisionBits;
import truehordes.world.Grid;
import truehordes.world.GridBuilder;
import truehordes.world.Terrain;
import truehordes.world.WorldSource;
import truehordes.world.ZoneShape;

public final class GridBuilderTest {
    private static Map<Long, Path> files(Object... cellAndPath) {
        Map<Long, Path> m = new HashMap<>();
        for (int i = 0; i < cellAndPath.length; i += 3) {
            m.put(WorldSource.key((Integer) cellAndPath[i], (Integer) cellAndPath[i + 1]), (Path) cellAndPath[i + 2]);
        }
        return m;
    }

    public static void testBuildsFromMapFilesAndZones() throws Exception {
        Path dir = Files.createTempDirectory("th-map");
        Path a = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_WATER).write(dir, "chunkdata_10_20.bin");
        WorldSource src = new WorldSource(10, 20, 11, 20, files(10, 20, a), null,
            List.of(ZoneShape.rect(Terrain.FOREST, 10 * 256, 20 * 256, 512, 256)));
        BuildReport r = new BuildReport();
        Grid g = GridBuilder.build(src, () -> false, r);
        Check.equal(Terrain.WATER, g.terrain(320, 640), "water chunk at the cell's first chunk");
        Check.equal(Terrain.FOREST, g.terrain(321, 640), "forest zone on empty ground");
        Check.equal(Terrain.UNKNOWN, g.terrain(352, 640), "cell 11 has no file");
        Check.equal(1, r.cellsFromMap, "one map file");
        Check.equal(1, r.cellsMissing, "one missing");
        Check.equal(64L * 32 * 6, g.memoryBytes(), "2 cells of 32x32 chunks, 6 bytes each");
        Check.isTrue(r.terrainCounts[Terrain.FOREST.code] > 0, "counts filled");
    }

    public static void testSaveCopyWinsOverMapCopy() throws Exception {
        Path map = FakeCells.cell().write(Files.createTempDirectory("th-map"), "chunkdata_0_0.bin");
        Path saveDir = Files.createTempDirectory("th-save");
        FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_SOLID).write(saveDir, "chunkdata_0_0.bin");
        BuildReport r = new BuildReport();
        Grid g = GridBuilder.build(new WorldSource(0, 0, 0, 0, files(0, 0, map), saveDir, List.of()), () -> false, r);
        Check.isTrue(g.blocked(0, 0), "the save's wall is used");
        Check.equal(1, r.cellsFromSave, "from save");
        Check.equal(0, r.cellsFromMap, "not from map");
    }

    public static void testDamagedSaveCopyFallsBackToMapCopy() throws Exception {
        Log.setSink(line -> {});
        try {
            Path map = FakeCells.cell().write(Files.createTempDirectory("th-map"), "chunkdata_0_0.bin");
            Path saveDir = Files.createTempDirectory("th-save");
            byte[] full = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_SOLID).bytes();
            Files.write(saveDir.resolve("chunkdata_0_0.bin"), java.util.Arrays.copyOf(full, 50));
            BuildReport r = new BuildReport();
            Grid g = GridBuilder.build(new WorldSource(0, 0, 0, 0, files(0, 0, map), saveDir, List.of()), () -> false, r);
            Check.isTrue(!g.blocked(0, 0), "map copy used, not the half-written save");
            Check.equal(1, r.filesDamaged, "damage counted");
            Check.equal(1, r.cellsFromMap, "from map");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testDamagedMapCopyKeepsWhatWasRead() throws Exception {
        Log.setSink(line -> {});
        try {
            Path dir = Files.createTempDirectory("th-map");
            byte[] full = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_WATER).bytes();
            Path p = dir.resolve("chunkdata_0_0.bin");
            Files.write(p, java.util.Arrays.copyOf(full, 10));
            BuildReport r = new BuildReport();
            Grid g = GridBuilder.build(new WorldSource(0, 0, 0, 0, files(0, 0, p), null, List.of()), () -> false, r);
            Check.equal(Terrain.WATER, g.terrain(0, 0), "chunk read before the damage");
            Check.equal(Terrain.UNKNOWN, g.terrain(31, 31), "unread chunk unknown");
            Check.equal(1, r.filesDamaged, "damage counted");
            Check.isTrue(r.damaged.get(0).contains("chunkdata_0_0.bin"), "path named: " + r.damaged);
        } finally {
            Log.setSink(null);
        }
    }

    public static void testFirstMapFolderWins() {
        Map<String, String> modMap = new HashMap<>();
        modMap.put("chunkdata_1_2.bin", "C:/mod/chunkdata_1_2.bin");
        modMap.put("1_2.lotheader", "C:/mod/1_2.lotheader");
        Map<String, String> vanilla = new HashMap<>();
        vanilla.put("chunkdata_1_2.bin", "C:/vanilla/chunkdata_1_2.bin");
        vanilla.put("chunkdata_3_4.bin", "C:/vanilla/chunkdata_3_4.bin");
        Map<Long, Path> files = WorldSource.resolveMapFiles(List.of(modMap, vanilla));
        Check.equal(2, files.size(), "two cells, lot headers ignored");
        Check.isTrue(files.get(WorldSource.key(1, 2)).toString().contains("mod"), "first folder wins");
        Check.isTrue(files.get(WorldSource.key(3, 4)).toString().contains("vanilla"), "fills the rest");
    }

    public static void testCancelReturnsNull() throws Exception {
        WorldSource src = new WorldSource(0, 0, 3, 3, new HashMap<>(), null, List.of());
        Check.equal(null, GridBuilder.build(src, () -> true, new BuildReport()), "cancelled");
    }

    public static void testRoadsFromMapFolder() throws Exception {
        Path dir = Files.createTempDirectory("th-map");
        Path cell = FakeCells.cell().write(dir, "chunkdata_0_0.bin");
        Files.writeString(dir.resolve("streets.xml"), "<streets><street name=\"A\" width=\"2\"><points>"
            + "<point x=\"16.0\" y=\"20.0\"/><point x=\"23.0\" y=\"20.0\"/></points></street></streets>");
        BuildReport r = new BuildReport();
        Grid g = GridBuilder.build(new WorldSource(0, 0, 0, 0, files(0, 0, cell), null, List.of(), List.of(dir)), () -> false, r);
        Check.equal(Terrain.ROAD, g.terrain(2, 2), "street chunk");
        Check.equal(Terrain.OPEN, g.terrain(4, 2), "beyond the street");
        Check.equal(1, r.streets, "counted");
    }

    public static void testTrailFromWorldMap() throws Exception {
        Path dir = Files.createTempDirectory("th-map");
        Path cell = FakeCells.cell().write(dir, "chunkdata_0_0.bin");
        Files.writeString(dir.resolve("worldmap.xml"), "<world><cell x=\"0\" y=\"0\"><feature><geometry type=\"Polygon\">"
            + "<coordinates><point x=\"40\" y=\"40\"/><point x=\"44\" y=\"40\"/><point x=\"44\" y=\"44\"/>"
            + "<point x=\"40\" y=\"44\"/></coordinates></geometry><properties><property name=\"highway\" value=\"trail\"/>"
            + "</properties></feature></cell></world>");
        BuildReport r = new BuildReport();
        Grid g = GridBuilder.build(new WorldSource(0, 0, 0, 0, files(0, 0, cell), null, List.of(), List.of(dir)), () -> false, r);
        Check.equal(Terrain.TRAIL, g.terrain(5, 5), "trail chunk");
        Check.equal(1, r.trails, "counted");
    }
}
