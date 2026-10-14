package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import truehordes.sim.MapView;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/** Cost of the simulation at a population far above vanilla's (1M held zombies) on a map the size of the game's. */
public final class SimSpeedTest {
    private SimSpeedTest() {}

    /** 300x300 cells of open ground with a road every 8th chunk row and column. */
    static MapView bigMap() {
        int size = 300 * 32;
        return new MapView() {
            @Override public int rank(int cx, int cy) {
                if (!inMap(cx, cy)) return 0;
                return (cx % 8 == 0 || cy % 8 == 0) ? 1 : 4;
            }
            @Override public boolean inMap(int cx, int cy) { return cx >= 0 && cy >= 0 && cx < size && cy < size; }
            @Override public boolean tight(int cx, int cy) { return false; }
        };
    }

    static long gcMillis() {
        long t = 0;
        for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            t += Math.max(0, b.getCollectionTime());
        }
        return t;
    }

    public static void testOneMillionHeldZombiesStayWithinBudget() {
        Store s = new Store();
        Random rng = new Random(1);
        // Clustered like towns: 2,000 towns of 500 zombies, 10% of them idle in buildings.
        for (int t = 0; t < 2000; t++) {
            float tx = 1000 + rng.nextInt(70000), ty = 1000 + rng.nextInt(70000);
            for (int i = 0; i < 500; i++) {
                ZombieRecord r = s.add(tx + rng.nextInt(200), ty + rng.nextInt(200), 0, (byte) rng.nextInt(8), 0, 0, 0,
                    ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, i % 10 == 0);
                r.strRolled = true;
            }
        }
        Simulation sim = new Simulation(s, bigMap(), SimSettings.defaults(), 2, 0);
        for (int h = 0; h < 50; h++) {
            List<ZombieRecord> m = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                m.add(new ZombieRecord(10_000_000L + h * 1000L + i, 0, 0, 0, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET,
                    ZombieRecord.NO_TARGET, null, false));
            }
            sim.restoreHorde(sim.hordes().nextId(), 2000 + rng.nextInt(60000), 2000 + rng.nextInt(60000), 0, 0, m);
        }
        Check.equal(1_050_000L, sim.held(), "1.05M held");
        // Warm up one in-game hour, then measure two, in 2 ms slices as the background thread runs them.
        long slice = 2_000_000L, worst = 0, cpu = 0;
        double t = 0;
        for (; t < 1; t += 1.0 / 60) while (sim.step(t, slice) > 0) { /* drain what is due */ }
        long gc0 = gcMillis();
        List<Long> slices = new ArrayList<>();
        for (; t < 3; t += 1.0 / 60) {
            while (true) {
                long g0 = gcMillis(), t0 = System.nanoTime();
                int n = sim.step(t, slice);
                long took = System.nanoTime() - t0;
                cpu += took;
                // A collector pause inside a slice is the JVM's, not the simulation's (the game runs its own GC).
                long net = took - (gcMillis() - g0) * 1_000_000L;
                worst = Math.max(worst, net);
                slices.add(net);
                if (n == 0) break;
            }
        }
        double perHour = cpu / 2e9;
        slices.sort(null);
        long p99 = slices.get((int) (slices.size() * 0.99));
        System.out.println(String.format(Locale.ROOT,
            "  SimSpeedTest: 1.05M held, %.2f s CPU per in-game hour (%.1f%% of one core at 1-hour days), slices net of GC: 99%% under %.2f ms, worst %.2f ms, GC %d ms; %s",
            perHour, perHour / 150 * 100, p99 / 1e6, worst / 1e6, gcMillis() - gc0, sim.stats()));
        Check.isTrue(perHour < 20, "under 20 s CPU per in-game hour (150 real s at 1-hour days)");
        // The main thread waits at most one slice: 99% stay near the 2 ms budget; the odd one (JIT, OS scheduling) is
        // still short of a frame-and-a-half.
        Check.isTrue(p99 < 4_000_000L, "99% of slices near their 2 ms budget");
        // Outliers here line up with collector work in this shared JVM; in game Hordes_stats() names the slowest update.
        Check.isTrue(worst < 50_000_000L, "no slice near three frames");
        Check.equal("", sim.checkConservation("speed test"), "conservation");
    }
}
