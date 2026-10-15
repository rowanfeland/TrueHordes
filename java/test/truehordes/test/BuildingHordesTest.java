package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.sim.Horde;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/** User design 2026-10-15: idle building zombies wait as dormant building hordes until a sound sets them loose. */
public final class BuildingHordesTest {
    private BuildingHordesTest() {}

    static ZombieRecord inside(Store s, float x, float y) {
        ZombieRecord r = s.add(x, y, 0, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, true);
        return r;
    }

    static SimSettings noStragglers() {
        return SimSettings.defaults().toBuilder().followChancePerHour(0).stragglerOneIn(100000)
            .buildingStragglerOneIn(100000).build();
    }

    static Horde only(Simulation sim) {
        Check.equal(1, sim.hordes().size(), "one group");
        return sim.hordes().all().iterator().next();
    }

    public static void testBuildingZombiesGroupPerChunk() {
        Store s = new Store();
        for (int i = 0; i < 6; i++) inside(s, 801 + i, 803);   // one house (chunk 100,100)
        ZombieRecord alone = inside(s, 900, 900);            // a lone zombie elsewhere
        Simulation sim = new Simulation(s, SimulationTest.openMap(200, -1), noStragglers(), 1, 0);
        sim.step(0.1, Long.MAX_VALUE);
        Horde g = only(sim);
        Check.isTrue(g.dormant, "the house's zombies wait as a dormant group");
        Check.equal(6, g.size(), "all six");
        Check.isTrue(s.get(alone.id) == alone && alone.idle, "a lone zombie stays an idle zombie");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    public static void testBuildingStragglersStayInside() {
        Store s = new Store();
        for (int i = 0; i < 3000; i++) inside(s, 800 + (i % 8), 800 + (i / 8) % 8);
        SimSettings st = noStragglers().toBuilder().buildingStragglerOneIn(10).build();
        Simulation sim = new Simulation(s, SimulationTest.openMap(200, -1), st, 2, 0);
        sim.step(0.1, Long.MAX_VALUE);
        int stay = 0;
        for (ZombieRecord r : s.all()) if (r.straggler && r.idle) stay++;
        Check.isTrue(stay > 200 && stay < 400, "about 1 in 10 are building stragglers (" + stay + ")");
        Check.equal(3000 - stay, only(sim).size(), "the rest are in the group");
    }

    public static void testSoundSetsABigGroupLooseAsAHorde() {
        Store s = new Store();
        for (int i = 0; i < 12; i++) inside(s, 801 + (i % 6), 802 + i / 6);
        Simulation sim = new Simulation(s, SimulationTest.openMap(200, -1), noStragglers(), 3, 0);
        sim.step(0.1, Long.MAX_VALUE);
        Horde g = only(sim);
        sim.addSound(900, 804, 90, 0.5);
        Check.isTrue(!g.dormant, "woken: an active horde");
        SimulationTest.run(sim, 0.2, 3);
        Check.isTrue(g.x > 830, "heading for the sound (" + g.x + ")");
    }

    public static void testSoundSetsASmallGroupLooseAsWanderers() {
        Store s = new Store();
        for (int i = 0; i < 4; i++) inside(s, 801 + i, 803);
        Simulation sim = new Simulation(s, SimulationTest.openMap(200, -1), noStragglers(), 4, 0);
        sim.step(0.1, Long.MAX_VALUE);
        only(sim);
        sim.addSound(900, 804, 90, 0.5);
        Check.equal(0, sim.hordes().size(), "four come out as wanderers");
        Check.equal(4, s.size(), "back in the store");
        for (ZombieRecord r : s.all()) Check.isTrue(!r.idle && r.hasTarget(), "awake and heading for the sound");
    }

    public static void testPassingHordeMoaningWakesAGroup() {
        Store s = new Store();
        for (int i = 0; i < 6; i++) inside(s, 801 + i, 803);
        SimSettings st = noStragglers().toBuilder().moanResponsePerHour(1).build();
        Simulation sim = new Simulation(s, SimulationTest.openMap(200, -1), st, 5, 0);
        sim.step(0.1, Long.MAX_VALUE);
        Horde g = only(sim);
        List<ZombieRecord> m = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            m.add(new ZombieRecord(9_000_000 + i, 860, 803, 0, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET,
                ZombieRecord.NO_TARGET, null, false));
        }
        sim.restoreHorde(sim.hordes().nextId(), 860, 803, 0, 0, m);
        SimulationTest.run(sim, 0.2, 2);
        Check.isTrue(sim.hordes().get(g.id) == null || !g.dormant, "the big horde's moaning set them loose");
    }

    public static void testDormantGroupIsNotJoinedOrMerged() {
        Store s = new Store();
        for (int i = 0; i < 6; i++) inside(s, 801 + i, 803);
        Simulation sim = new Simulation(s, SimulationTest.openMap(200, -1), noStragglers(), 6, 0);
        sim.step(0.1, Long.MAX_VALUE);
        Horde g = only(sim);
        sim.encounters().horde(g, 1, 0);
        List<ZombieRecord> m = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            m.add(new ZombieRecord(9_100_000 + i, 804, 804, 0, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET,
                ZombieRecord.NO_TARGET, null, false));
        }
        Horde passing = sim.restoreHorde(sim.hordes().nextId(), 804, 804, 0, 0, m);
        sim.encounters().horde(passing, 1, 0);
        Check.equal(2, sim.hordes().size(), "a horde touching a building group does not absorb it");
        Check.isTrue(g.dormant && g.size() == 6, "the group is untouched");
    }

    public static void testDormantMembersAppearWhereTheyStand() {
        Store s = new Store();
        for (int i = 0; i < 6; i++) inside(s, 801 + i, 803);
        Simulation sim = new Simulation(s, SimulationTest.openMap(200, -1), noStragglers(), 7, 0);
        sim.step(0.1, Long.MAX_VALUE);
        only(sim);
        sim.setPlayers(SimulationTest.loadedAround(100, 100, 3));
        List<ZombieRecord> out = sim.handOver().layOutTouching(100, 100, (x, y, z) -> true);
        Check.equal(6, out.size(), "all laid out");
        for (int i = 0; i < out.size(); i++) {
            ZombieRecord r = out.get(i);
            Check.near(803, r.y(), 1e-4, "in its own spot (same row)");
            Check.isTrue(r.idle, "still idle inside until something wakes it");
        }
    }
}
