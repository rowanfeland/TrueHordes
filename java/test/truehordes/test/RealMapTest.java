package truehordes.test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import truehordes.util.Log;
import truehordes.world.Terrain;
import truehordes.world.World;
import truehordes.world.WorldSource;
import truehordes.world.ZoneShape;

/** Builds the grid from the installed vanilla map (Muldraugh, KY): every file reads, timing, and known spots. */
public final class RealMapTest {
    private static final Pattern FILE = Pattern.compile("chunkdata_(\\d+)_(\\d+)\\.bin");
    private static final Pattern NAV = Pattern.compile(
        "type = \"Nav\", x = (\\d+), y = (\\d+), z = 0, width = (\\d+), height = (\\d+)");

    private static Path mapDir() {
        String jar = System.getProperty("truehordes.gameJar");
        return jar == null ? null : Paths.get(jar).getParent().resolve("media").resolve("maps").resolve("Muldraugh, KY");
    }

    public static void testVanillaMap() throws Exception {
        Path dir = mapDir();
        if (dir == null || !Files.isDirectory(dir)) {
            System.out.println("RealMapTest: vanilla map folder not found, skipped");
            return;
        }
        Map<Long, Path> files = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "chunkdata_*.bin")) {
            for (Path p : ds) {
                Matcher m = FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int cx = Integer.parseInt(m.group(1)), cy = Integer.parseInt(m.group(2));
                files.put(WorldSource.key(cx, cy), p);
                minX = Math.min(minX, cx);
                maxX = Math.max(maxX, cx);
                minY = Math.min(minY, cy);
                maxY = Math.max(maxY, cy);
            }
        }
        List<ZoneShape> roads = navRects(dir.resolve("objects.lua"));
        List<String> log = new ArrayList<>();
        Log.setSink(log::add);
        World w = new World();
        try {
            w.start(new WorldSource(minX, minY, maxX, maxY, files, null, roads, List.of(dir)));
            Check.isTrue(w.awaitBuilt(120_000), "built within 2 minutes");
            w.tick();
            String report = w.report();
            System.out.println("RealMapTest: " + files.size() + " files, " + roads.size() + " road zones; " + report);
            Check.isTrue(report.contains(" 0 damaged"), "no damaged files: " + report);
            Check.isTrue(report.contains(files.size() + " from map"), "every file read: " + report);
            Check.isTrue(w.walkable(12316, 6718), "bridge tile walkable");
            Check.equal(Terrain.ROAD, w.terrainAt(12316, 6718), "bridge chunk is road");
            Check.isTrue(!w.walkable(12300, 6718), "river beside the bridge");
            Check.equal(Terrain.WATER, w.terrainAt(12330, 6722), "all-water chunk");
            Check.isTrue(w.blockedAt(12330, 6722), "water blocks (D16)");
            Check.isTrue(w.canStep(12316, 6716, 12316, 6717), "walk along the bridge");
            Check.isTrue(!w.blockedAt(12316, 6718), "bridge chunk connects");
            // Spots no Nav zone covers (found by scanning the 42.21 files): only the map's road files make them road.
            Check.equal(Terrain.ROAD, w.terrainAt(548, 9244), "streets.xml-only road");
            Check.equal(Terrain.ROAD, w.terrainAt(92, 9436), "world-map-only road");
            Check.equal(Terrain.TRAIL, w.terrainAt(332, 9596), "world-map trail");
        } finally {
            w.stop();
            Log.setSink(null);
        }
    }

    private static List<ZoneShape> navRects(Path objects) throws IOException {
        List<ZoneShape> out = new ArrayList<>();
        if (!Files.exists(objects)) return out;
        Matcher m = NAV.matcher(Files.readString(objects));
        while (m.find()) {
            out.add(ZoneShape.rect(Terrain.ROAD, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
        }
        return out;
    }
}
