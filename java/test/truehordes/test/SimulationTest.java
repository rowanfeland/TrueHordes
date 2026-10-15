package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import truehordes.population.PlayerNearby;
import truehordes.sim.Horde;
import truehordes.sim.MapView;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

public final class SimulationTest {
    private SimulationTest() {}

    /** Open ground in a square of {@code size} chunks, optional water band, nothing outside. */
    static MapView openMap(int size, int waterRow) {
        return new MapView() {
            @Override public int rank(int cx, int cy) {
                if (!inMap(cx, cy) || cy == waterRow) return 0;
                return 4;
            }
            @Override public boolean inMap(int cx, int cy) { return cx >= 0 && cy >= 0 && cx < size && cy < size; }
            @Override public boolean tight(int cx, int cy) { return false; }
        };
    }

    /** A long east-west road on row {@code roadRow}, forest elsewhere. */
    static MapView roadMap(int width, int height, int roadRow) {
        return new MapView() {
            @Override public int rank(int cx, int cy) { return !inMap(cx, cy) ? 0 : cy == roadRow ? 1 : 4; }
            @Override public boolean inMap(int cx, int cy) { return cx >= 0 && cy >= 0 && cx < width && cy < height; }
            @Override public boolean tight(int cx, int cy) { return false; }
        };
    }

    static ZombieRecord add(Store s, float x, float y, float heading) {
        ZombieRecord r = s.add(x, y, 0, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false);
        r.heading = heading;
        r.strRolled = true;
        return r;
    }

    static PlayerNearby loadedAround(int cx0, int cy0, int r) {
        return new PlayerNearby() {
            @Override public boolean nearCell(int cellX, int cellY) {
                return cellX == Math.floorDiv(cx0, 32) && cellY == Math.floorDiv(cy0, 32);
            }
            @Override public boolean nearChunk(int cx, int cy) { return Math.abs(cx - cx0) <= r && Math.abs(cy - cy0) <= r; }
        };
    }

    /** Day 40: past the population peak, so forming chances (moaning, following) are at full strength. */
    static final double LATE = 24 * 40;

    static void run(Simulation sim, double fromHours, double hours) {
        for (double t = fromHours; t <= fromHours + hours; t += 1.0 / 60) sim.step(t, Long.MAX_VALUE);
    }

    static SimSettings quiet() { return SimSettings.defaults().toBuilder().followChancePerHour(0).build(); }

    public static void testWanderersSpreadOverDays() {
        Store s = new Store();
        Random rng = new Random(1);
        for (int i = 0; i < 1000; i++) add(s, 800 + rng.nextInt(32), 800 + rng.nextInt(32), (float) (rng.nextDouble() * 6.28));
        MapView map = openMap(200, 90);
        Simulation sim = new Simulation(s, map, quiet(), 7, 0);
        run(sim, 0, 7 * 24);
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (ZombieRecord r : s.all()) {
            x0 = Math.min(x0, r.x()); x1 = Math.max(x1, r.x()); y0 = Math.min(y0, r.y()); y1 = Math.max(y1, r.y());
            int cx = Math.floorDiv((int) r.x(), 8), cy = Math.floorDiv((int) r.y(), 8);
            Check.isTrue(map.inMap(cx, cy), "never outside the map");
            Check.isTrue(cy != 90, "never in the water band");
        }
        Check.isTrue(x1 - x0 > 96 && y1 - y0 > 96, "spread out over a week (" + (x1 - x0) + " x " + (y1 - y0) + ")");
        Check.equal("", sim.checkConservation("test"), "nobody lost or made");
        Check.equal(1000L, sim.held(), "held count");
    }

    public static void testFarWalkersUpdateLessOften() {
        MapView map = openMap(400, -1);
        Store edge = new Store();
        add(edge, 100 * 8 + 4, 102 * 8 + 4, 0);
        Simulation a = new Simulation(edge, map, quiet(), 1, 0);
        a.setPlayers(loadedAround(100, 100, 1));
        run(a, 0, 1);
        Store far = new Store();
        add(far, 300 * 8 + 4, 300 * 8 + 4, 0);
        Simulation b = new Simulation(far, map, quiet(), 1, 0);
        b.setPlayers(loadedAround(100, 100, 1));
        run(b, 0, 1);
        Check.isTrue(a.updatesTotal() >= 5 * Math.max(1, b.updatesTotal()), "near the loaded edge: far more often ("
            + a.updatesTotal() + " vs " + b.updatesTotal() + ")");
        Check.isTrue(b.updatesTotal() <= 3, "far away: every 30 minutes (" + b.updatesTotal() + ")");
    }

    public static void testSliceRespectsBudget() {
        Store s = new Store();
        Random rng = new Random(2);
        for (int i = 0; i < 1_000_000; i++) add(s, rng.nextInt(2400) + 0.5f, rng.nextInt(2400) + 0.5f, 0);
        Simulation sim = new Simulation(s, openMap(300, -1), quiet(), 3, 0);
        sim.step(0.5, 2_000_000L); // warm-up
        long worst = 0;
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            sim.step(1.0, 2_000_000L);
            worst = Math.max(worst, System.nanoTime() - t0);
        }
        Check.isTrue(worst < 6_000_000L, "a 2 ms slice stays short with 1M zombies (" + worst / 1000 + " us)");
    }

    public static void testFollowerHordeTrailsUntilItCatchesUp() {
        Store s = new Store();
        // Noticing is certain here: this checks how a follower trails, not how soon it notices.
        Simulation sim = new Simulation(s, roadMap(400, 21, 10),
            quiet().toBuilder().offRoadChance(0).moanResponsePerHour(1).build(), 4, LATE);
        Horde leader = horde(sim, 60 * 8, 10 * 8 + 4, 0, 2000);
        Horde follower = horde(sim, 50 * 8, 10 * 8 + 4, 0, 20);
        boolean trailed = false;
        double t = LATE;
        for (; t < LATE + 48 && sim.hordes().get(follower.id) != null; t += 1.0 / 60) {
            sim.step(t, Long.MAX_VALUE);
            if (follower.followHordeId == leader.id) trailed = true;
        }
        Check.isTrue(trailed, "the small horde followed the big one's moaning");
        Check.isTrue(sim.hordes().get(follower.id) == null, "and caught up and merged within two days");
        Check.equal(2020, leader.size(), "all members kept");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    static Horde horde(Simulation sim, float x, float y, float heading, int n) {
        List<ZombieRecord> m = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ZombieRecord r = new ZombieRecord(5_000_000 + sim.hordes().memberCount() + i, x, y, 0, (byte) 0, 0, 0, 0,
                ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false);
            r.strRolled = true;
            m.add(r);
        }
        return sim.restoreHorde(sim.hordes().nextId(), x, y, heading, 0, m);
    }

    public static void testNoDirector() {
        Store s = new Store();
        Random rng = new Random(5);
        java.util.Map<Long, float[]> start = new java.util.HashMap<>();
        // Spread out (bumping ties walkers into chains that move together) and hordes off (a horde walks as one):
        // this checks that nothing steers walkers one way, so the walkers compared must move independently.
        for (int i = 0; i < 1000; i++) {
            ZombieRecord r = add(s, 100 + rng.nextInt(1400), 100 + rng.nextInt(1400), (float) (rng.nextDouble() * 6.28));
            start.put(r.id, new float[] {r.x(), r.y()});
        }
        Simulation sim = new Simulation(s, openMap(200, -1), quiet().toBuilder().hordeFormSize(1000).build(), 9, 0);
        run(sim, 0, 24); // a day: long enough to travel, short enough that most still walk alone
        double sx = 0, sy = 0, sq = 0;
        int n = 0;
        for (ZombieRecord r : s.all()) {
            if (r.followId != 0) continue; // followers move with their leader: only heads are independent
            float[] p = start.get(r.id);
            double dx = r.x() - p[0], dy = r.y() - p[1];
            sx += dx; sy += dy; sq += dx * dx + dy * dy;
            n++;
        }
        double drift = Math.hypot(sx, sy) / n, chance = Math.sqrt(sq / n) / Math.sqrt(n);
        Check.isTrue(n > 300, "enough independent walkers (" + n + ")");
        Check.isTrue(drift < 3 * chance, "no shared direction beyond chance (mean drift " + drift + " tiles, chance about "
            + chance + ")");
    }

    /** Final review I3: a zombie that walked off screen forgets its old walking target. */
    public static void testWalkingClearsAnOldTarget() {
        Store s = new Store();
        ZombieRecord r = s.add(800, 800, 0, (byte) 6, 0, 0, 0, 900, 900, null, false);
        r.strRolled = true;
        Simulation sim = new Simulation(s, openMap(200, -1), quiet(), 1, 0);
        run(sim, 0, 2);
        Check.isTrue(!r.hasTarget(), "the stale target is dropped once it walks");
    }

    /** Final review I6: stragglers linger and stay near where they are, but still hear real sounds. */
    public static void testStragglersLingerButHearSounds() {
        Store s = new Store();
        ZombieRecord str = add(s, 800, 800, 0);
        str.straggler = true;
        ZombieRecord w = add(s, 800, 1200, 0);
        Simulation sim = new Simulation(s, openMap(200, -1), quiet(), 3, 0);
        run(sim, 0, 24);
        float strayed = Math.abs(str.x() - 800) + Math.abs(str.y() - 800);
        float walked = Math.abs(w.x() - 800) + Math.abs(w.y() - 1200);
        Check.isTrue(strayed * 3 < walked, "a straggler stays close (" + strayed + " vs " + walked + " tiles)");
        float before = Math.abs(str.x() - 900);
        sim.addSound(900, str.y(), 90, 1);
        run(sim, 24, 1);
        Check.isTrue(Math.abs(str.x() - 900) < before, "but it heads for a real sound");
    }

    /** A town of 30x30 chunks, a road every 4th row and column (about 44% of its chunks), open ground around. */
    static MapView townMap() {
        return new MapView() {
            @Override public int rank(int cx, int cy) {
                if (!inMap(cx, cy)) return 0;
                boolean inTown = cx >= 80 && cx < 110 && cy >= 80 && cy < 110;
                return inTown && (cx % 4 == 0 || cy % 4 == 0) ? 1 : 4;
            }
            @Override public boolean inMap(int cx, int cy) { return cx >= 0 && cy >= 0 && cx < 200 && cy < 200; }
            @Override public boolean tight(int cx, int cy) { return false; }
        };
    }

    static Store town(int n, long seed) {
        Store s = new Store();
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) add(s, 80 * 8 + rng.nextInt(240), 80 * 8 + rng.nextInt(240), (float) (rng.nextDouble() * 6.28));
        return s;
    }

    /** In-game test 2026-10-14: within a day nearly every zombie sat on a road. Roads are preferred, not a trap. */
    public static void testRoadsArePreferredNotATrap() {
        Store s = town(1000, 1);
        MapView map = townMap();
        Simulation sim = new Simulation(s, map, SimSettings.defaults(), 5, 0);
        run(sim, 0, 24);
        int inTown = 0, onRoad = 0;
        for (ZombieRecord r : s.all()) {
            int cx = Math.floorDiv((int) r.x(), 8), cy = Math.floorDiv((int) r.y(), 8);
            if (cx < 80 || cx >= 110 || cy < 80 || cy >= 110) continue;
            inTown++;
            if (map.rank(cx, cy) == 1) onRoad++;
        }
        double share = onRoad / (double) Math.max(1, inTown);
        // In-game test 2026-10-15: zombies should keep to roads and walk down them (most of the town's zombies).
        Check.isTrue(share > 0.75, "roads hold most of the town's zombies (" + share + ")");
        // With the user's 15% off-road / 30% trail rolls town zombies mostly keep to the streets (about 80%), but the
        // roads are not a trap any more (91% within a day before).
        int offRoad = 0;
        for (ZombieRecord r : s.all()) {
            if (map.rank(Math.floorDiv((int) r.x(), 8), Math.floorDiv((int) r.y(), 8)) != 1) offRoad++;
        }
        Check.isTrue(offRoad > 20, "but some still head off across country (" + offRoad + " of 1000)");
    }

    /** In-game test 2026-10-14: three weeks, no hordes. Followers trail close behind, so chains become hordes. */
    public static void testFollowChainsBecomeHordesInATown() {
        Store s = town(300, 2);
        Simulation sim = new Simulation(s, townMap(), SimSettings.defaults(), 6, 0);
        run(sim, 0, 28 * 24);
        Check.isTrue(sim.hordes().size() >= 1, "a town of 300 grows at least one horde within four weeks ("
            + sim.hordes().size() + ")");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    /** In-game test 2026-10-15: at fast-forward the simulation fell hours behind; leaders jumped away from followers. */
    public static void testBigTimeJumpKeepsFollowers() {
        Store s = new Store();
        ZombieRecord leader = add(s, 800, 800, 0);
        ZombieRecord follower = add(s, 798, 800, 0);
        follower.followId = leader.id;
        Simulation sim = new Simulation(s, openMap(200, -1), quiet(), 3, 0);
        sim.step(0.5, Long.MAX_VALUE);
        sim.step(12.0, Long.MAX_VALUE); // the game clock jumped 11.5 hours
        Check.equal(leader.id, follower.followId, "still following after a jump of hours");
        Check.isTrue(Math.hypot(leader.x() - follower.x(), leader.y() - follower.y()) < 48, "and still close");
    }

    /** In-game test 2026-10-15: zombies far from a test sound never arrived before it ended, so no horde formed. */
    public static void testSoundGathersZombiesFromFarAway() {
        Store s = new Store();
        Random rng = new Random(7);
        for (int i = 0; i < 30; i++) {
            double a = rng.nextDouble() * 2 * Math.PI, d = 80 + rng.nextInt(60);
            add(s, (float) (800 + Math.cos(a) * d), (float) (800 + Math.sin(a) * d), (float) a);
        }
        Simulation sim = new Simulation(s, openMap(200, -1), quiet(), 12, 0);
        sim.addSound(800, 800, 90, 0.5);
        run(sim, 0, 8);
        Check.isTrue(sim.hordes().size() >= 1, "zombies that heard it walked there and became a horde ("
            + sim.hordes().size() + " hordes, " + s.size() + " wanderers left)");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    public static void testRealChunksSkipped() {
        Store s = new Store();
        ZombieRecord r = add(s, 100 * 8 + 4, 100 * 8 + 4, 0);
        Simulation sim = new Simulation(s, openMap(200, -1), quiet(), 1, 0);
        sim.setPlayers(loadedAround(100, 100, 2));
        run(sim, 0, 2);
        Check.near(100 * 8 + 4, r.x(), 1e-6, "a zombie inside the loaded area is left to the game");
    }

    public static void testWalkersNeverEnterTheLoadedArea() {
        Store s = new Store();
        ZombieRecord r = add(s, 96 * 8 + 4, 100 * 8 + 4, 0);
        Simulation sim = new Simulation(s, roadMap(200, 200, 100), quiet().toBuilder().offRoadChance(0).build(), 2, 0);
        sim.setPlayers(loadedAround(100, 100, 2));
        for (double t = 0; t < 6; t += 1.0 / 60) {
            sim.step(t, Long.MAX_VALUE);
            Check.isTrue(!sim.players().nearChunk(Math.floorDiv((int) r.x(), 8), Math.floorDiv((int) r.y(), 8)),
                "held zombies stay outside the loaded area");
        }
    }

    public static void testTestSoundGathersAHorde() {
        Store s = new Store();
        Random rng = new Random(6);
        for (int i = 0; i < 40; i++) add(s, 780 + rng.nextInt(40), 780 + rng.nextInt(40), (float) (rng.nextDouble() * 6.28));
        Simulation sim = new Simulation(s, openMap(200, -1), quiet(), 11, 0);
        sim.addSound(800, 800, 80, 0.5);
        run(sim, 0, 1);
        Check.isTrue(sim.hordes().size() >= 1, "zombies that gathered at the sound became a horde");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }
}
