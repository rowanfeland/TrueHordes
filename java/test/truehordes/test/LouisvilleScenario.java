package truehordes.test;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import truehordes.sim.Horde;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.sim.WorldMapView;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;
import truehordes.world.Terrain;
import truehordes.world.World;
import truehordes.world.WorldSource;

/**
 * Long offline run on the real map (not part of the test suite): Louisville's population over in-game months, to see
 * whether a city-wide horde emerges. Run: java -cp build/test;build/classes;<game jar> truehordes.test.LouisvilleScenario
 * <game jar> [days] [zombies] [follow per hour] [seed]
 */
public final class LouisvilleScenario {
    private static final Pattern FILE = Pattern.compile("chunkdata_(\\d+)_(\\d+)\\.bin");

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]).getParent().resolve("media").resolve("maps").resolve("Muldraugh, KY");
        int days = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        int zombies = args.length > 2 ? Integer.parseInt(args[2]) : 30000;
        double follow = args.length > 3 ? Double.parseDouble(args[3]) : SimSettings.defaults().followChancePerHour();
        long seed = args.length > 4 ? Long.parseLong(args[4]) : 1;
        double join = args.length > 5 ? Double.parseDouble(args[5]) : SimSettings.defaults().joinChancePerHour();
        double moan = args.length > 6 ? Double.parseDouble(args[6]) : SimSettings.defaults().moanResponsePerHour();
        Map<Long, Path> files = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "chunkdata_*.bin")) {
            for (Path p : ds) {
                Matcher m = FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int cx = Integer.parseInt(m.group(1)), cy = Integer.parseInt(m.group(2));
                files.put(WorldSource.key(cx, cy), p);
                minX = Math.min(minX, cx); maxX = Math.max(maxX, cx); minY = Math.min(minY, cy); maxY = Math.max(maxY, cy);
            }
        }
        World w = new World();
        w.start(new WorldSource(minX, minY, maxX, maxY, files, null, List.of(), List.of(dir)));
        if (!w.awaitBuilt(180_000)) throw new IllegalStateException("map did not build");
        w.tick();
        // Louisville = the 4x4-cell block with the most building chunks in the map's north-east quarter.
        int bestX = 0, bestY = 0, best = -1;
        for (int cx = (minX + maxX) / 2; cx <= maxX - 3; cx++) {
            for (int cy = minY; cy <= (minY + maxY) / 2; cy++) {
                int n = 0;
                for (int x = cx * 32; x < (cx + 4) * 32; x += 2) {
                    for (int y = cy * 32; y < (cy + 4) * 32; y += 2) if (w.chunkTerrain(x, y) == Terrain.BUILDING) n++;
                }
                if (n > best) { best = n; bestX = cx; bestY = cy; }
            }
        }
        int x0 = bestX * 256, y0 = bestY * 256, size = 4 * 256;
        System.out.println("Louisville block: cells " + bestX + "," + bestY + " (tiles " + x0 + "," + y0 + " +" + size + ")");
        WorldMapView map = new WorldMapView(w);
        Store s = new Store();
        Random rng = new Random(seed);
        int placed = 0;
        String fromSave = System.getProperty("save");
        java.util.List<truehordes.save.LoadResult.SavedHorde> savedHordes = java.util.List.of();
        if (fromSave != null) {
            // Start from a real mod save (state.bin): the exact population the game had.
            truehordes.save.LoadResult lr = truehordes.save.SaveFile.read(Paths.get(fromSave));
            for (ZombieRecord r : lr.records) s.restore(r);
            savedHordes = lr.hordes;
            placed = zombies;
            System.out.println("from save: " + lr.records.size() + " records, " + lr.hordes.size() + " hordes, hour "
                + lr.worldAgeHours);
        }
        while (placed < zombies) {
            float x = x0 + rng.nextInt(size) + 0.5f, y = y0 + rng.nextInt(size) + 0.5f;
            int r = map.rank(Math.floorDiv((int) x, 8), Math.floorDiv((int) y, 8));
            if (r == 0) continue;
            s.add(x, y, 0, (byte) rng.nextInt(8), 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false);
            placed++;
        }
        SimSettings settings = SimSettings.defaults().toBuilder().followChancePerHour(follow).joinChancePerHour(join)
            .moanResponsePerHour(moan).build();
        System.out.println("follow " + follow + "/h, join " + join + "/h, moan " + moan + "/h");
        Simulation sim = new Simulation(s, map, settings, seed, 0);
        for (truehordes.save.LoadResult.SavedHorde h : savedHordes) {
            sim.restoreHorde(h.id(), h.x(), h.y(), h.heading(), h.pauseMinutes(), h.members());
        }
        long t0 = System.currentTimeMillis();
        for (int day = 1; day <= days; day++) {
            for (double t = (day - 1) * 24; t < day * 24; t += 1.0 / 60) sim.step(t, Long.MAX_VALUE);
            if (day % 5 == 0 || day == 1 || day == 2) {
                List<Horde> hs = new ArrayList<>(sim.hordes().all());
                hs.sort((a, b) -> Integer.compare(b.size(), a.size()));
                int inCity = 0;
                for (ZombieRecord r : s.all()) if (r.x() >= x0 && r.x() < x0 + size && r.y() >= y0 && r.y() < y0 + size) inCity++;
                StringBuilder top = new StringBuilder();
                for (int i = 0; i < Math.min(5, hs.size()); i++) {
                    Horde h = hs.get(i);
                    boolean inside = h.x >= x0 && h.x < x0 + size && h.y >= y0 && h.y < y0 + size;
                    top.append(h.size()).append(inside ? "" : "*").append(' ');
                }
                int following = 0, idle = 0;
                for (ZombieRecord r : s.all()) { if (r.followId > 0) following++; if (r.idle) idle++; }
                System.out.printf("  following %d, idle %d%n", following, idle);
                System.out.printf("day %3d: hordes %4d, in hordes %6d (%.0f%%), wanderers %6d (%d in city), top: %s| %ds%n",
                    day, hs.size(), sim.hordes().memberCount(), 100.0 * sim.hordes().memberCount() / sim.held(), s.size(),
                    inCity, top, (System.currentTimeMillis() - t0) / 1000);
            }
        }
        System.out.println(sim.stats());
        w.stop();
    }
}
