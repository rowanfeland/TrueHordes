package truehordes.test;

import truehordes.sim.Horde;
import truehordes.sim.MapView;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.store.Store;

/**
 * Round 8 (user, 2026-10-16): town hordes stay in their town until they "run out of food": a hunger day per horde by
 * size (500 zombies about day 7, bigger sooner, smaller later, each +-20%), then they leave along the roads.
 */
public final class LockdownTest {
    private LockdownTest() {}

    /** A town of 30x30 chunks (roads every 4th row and column, a building chunk in each block), open ground round it. */
    static MapView town() {
        return new MapView() {
            boolean inTown(int cx, int cy) { return cx >= 80 && cx < 110 && cy >= 80 && cy < 110; }
            @Override public int rank(int cx, int cy) {
                if (!inMap(cx, cy)) return 0;
                if (building(cx, cy)) return 0;
                return inTown(cx, cy) && (cx % 4 == 0 || cy % 4 == 0) ? 1 : 4;
            }
            @Override public boolean building(int cx, int cy) { return inTown(cx, cy) && cx % 4 == 2 && cy % 4 == 2; }
            @Override public boolean inMap(int cx, int cy) { return cx >= 0 && cy >= 0 && cx < 200 && cy < 200; }
            @Override public boolean tight(int cx, int cy) { return false; }
        };
    }

    public static void testHungerDayBySize() {
        for (long id = 1; id <= 50; id++) {
            double d500 = Simulation.hungerDay(500, 7, id);
            Check.isTrue(d500 >= 7 * 0.8 - 1e-9 && d500 <= 7 * 1.2 + 1e-9, "500: about day 7 (" + d500 + ")");
            double d10 = Simulation.hungerDay(10, 7, id), d5000 = Simulation.hungerDay(5000, 7, id);
            Check.isTrue(d10 > d500 && d500 > d5000, "bigger hordes run out of food sooner");
        }
        double d10 = Simulation.hungerDay(10, 7, 3);
        Check.isTrue(d10 > 12.6 * 0.8 - 1e-6 && d10 < 12.6 * 1.2 + 1e-6, "10 zombies: about day 12.6 (" + d10 + ")");
    }

    static double spreadFromTown(Simulation sim, Horde h) {
        return Math.max(Math.abs(h.x - 95 * 8), Math.abs(h.y - 95 * 8));
    }

    /** Before its day a town horde roams its town; after it, it leaves. */
    public static void testTownHordeStaysUntilItsDay() {
        for (int late = 0; late <= 1; late++) {
            Store s = new Store();
            double start = late == 0 ? 24 : 24 * 20;
            Simulation sim = new Simulation(s, town(), SimulationTest.quiet().toBuilder().breakAwayChance(0).build(), 7, start);
            Horde h = SimulationTest.horde(sim, 96 * 8 + 4, 96 * 8 + 4, 0, 500);
            double furthest = 0;
            for (double t = start; t < start + 48; t += 1.0 / 60) {
                sim.step(t, Long.MAX_VALUE);
                if (sim.hordes().get(h.id) == null) break;
                furthest = Math.max(furthest, spreadFromTown(sim, h));
            }
            if (late == 0) Check.isTrue(furthest < 15 * 8 + 24, "on day 2 it stays in its town (" + furthest + " tiles out)");
            else Check.isTrue(furthest > 15 * 8 + 80, "on day 20 it has left (" + furthest + " tiles out)");
        }
    }
}
