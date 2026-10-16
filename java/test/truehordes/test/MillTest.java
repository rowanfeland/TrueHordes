package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.sim.SoundEvent;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/**
 * Round 8 (user, 2026-10-16: "walkers and herds"): most lone zombies mill within about 150 tiles of home, a soft pull
 * that never walls them in; 5% are travellers who roam the roads; a sound they follow moves their home.
 */
public final class MillTest {
    private MillTest() {}

    static SimSettings settings(double travellers) {
        return SimulationTest.quiet().toBuilder().stragglerOneIn(1_000_000).bumpFollowChance(0).joinChancePerHour(0)
            .travellerShare(travellers).build();
    }

    static List<ZombieRecord> line(Store s, int n) {
        List<ZombieRecord> rs = new ArrayList<>();
        for (int i = 0; i < n; i++) rs.add(SimulationTest.add(s, 200 * 8 + 4, 50 * 8 + 4, i % 2 == 0 ? 0 : (float) Math.PI));
        return rs;
    }

    static double far(List<ZombieRecord> rs, boolean max) {
        double m = 0;
        for (ZombieRecord r : rs) {
            double d = Math.hypot(r.x() - (200 * 8 + 4), r.y() - (50 * 8 + 4));
            m = max ? Math.max(m, d) : m + d / rs.size();
        }
        return m;
    }

    public static void testMillersStayInTheirNeighbourhood() {
        Store s = new Store();
        List<ZombieRecord> rs = line(s, 40);
        Simulation sim = new Simulation(s, SimulationTest.roadMap(400, 100, 50), settings(0), 1, 0);
        SimulationTest.run(sim, 0, 48);
        double m = far(rs, true);
        Check.isTrue(m < 450, "after two days no miller is far from home (furthest " + m + " tiles)");
        Check.isTrue(far(rs, false) > 40, "but they do move about (mean " + far(rs, false) + ")");
    }

    public static void testTravellersRoam() {
        Store s = new Store();
        List<ZombieRecord> rs = line(s, 40);
        Simulation sim = new Simulation(s, SimulationTest.roadMap(400, 100, 50), settings(1), 2, 0);
        SimulationTest.run(sim, 0, 48);
        Check.isTrue(far(rs, false) > 700, "travellers walk on down the roads (mean " + far(rs, false) + " tiles)");
    }

    public static void testAboutOneInTwentyIsATraveller() {
        int n = 0;
        for (long id = 1; id <= 20000; id++) if (Simulation.traveller(id, 0.05)) n++;
        Check.isTrue(n > 850 && n < 1150, "about 5% (" + n + "/20000)");
    }

    /** A miller that follows a sound settles where it went: the sound moved its home. */
    public static void testASoundMovesHome() {
        Store s = new Store();
        ZombieRecord r = SimulationTest.add(s, 200 * 8 + 4, 50 * 8 + 4, 0);
        Simulation sim = new Simulation(s, SimulationTest.roadMap(400, 100, 50), settings(0), 3, 0);
        // 180 tiles away (a walk within the 6 hours it keeps investigating), loud enough to be certain.
        sim.offerSound(200 * 8 + 4 + 180, 50 * 8 + 4, 3000, false, 1, SoundEvent.GUNFIRE);
        SimulationTest.run(sim, 0.001, 60);
        double fromSpot = Math.hypot(r.x() - (200 * 8 + 4 + 180), r.y() - (50 * 8 + 4));
        double fromOld = Math.hypot(r.x() - (200 * 8 + 4), r.y() - (50 * 8 + 4));
        Check.isTrue(fromSpot < 200 && fromSpot < fromOld, "two days on it mills near where the sound was (" + fromSpot + " tiles)");
    }
}
