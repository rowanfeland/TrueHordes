package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import truehordes.real.BreakIn;
import truehordes.real.Crowd;
import truehordes.real.Pilot;
import truehordes.real.RealZone;

/** Sub-project 6: the real zone, crowd break-in times, real-horde columns and forming. */
public final class BridgeTest {
    private BridgeTest() {}

    public static void testRealZoneChunksAndMargin() {
        RealZone z = new RealZone();
        z.set(new float[] {1000, 1000}, 50);
        Check.isTrue(z.on(), "on with a radius");
        Check.isTrue(z.chunkInside(125, 125), "the player's own chunk");
        Check.isTrue(!z.chunkInside(131, 125), "a chunk reaching past 50 tiles is not wholly inside");
        Check.isTrue(z.near(1060, 1000, RealZone.DEMAT_MARGIN), "60 tiles: still inside radius + margin");
        Check.isTrue(!z.near(1070, 1000, RealZone.DEMAT_MARGIN), "70 tiles: beyond radius + margin");
        z.set(new float[] {1000, 1000}, 0);
        Check.isTrue(!z.on(), "radius 0: off");
    }

    public static void testBreakInTimesFollowD15() {
        Check.near(5, BreakIn.hours(true, 1), 1e-9, "a lone zombie: a window in 5 h");
        Check.near(24, BreakIn.hours(false, 1), 1e-9, "a lone zombie: a door in 24 h");
        Check.near(1, BreakIn.hours(true, 10), 1e-9, "10: a window in 1 h");
        Check.near(5, BreakIn.hours(false, 10), 1e-9, "10: a door in 5 h");
        Check.near(10 / 60.0, BreakIn.hours(false, 1000), 1e-9, "1,000: 10 minutes");
        Check.near(10 / 60.0, BreakIn.hours(true, 5000), 1e-9, "more than 1,000: no faster");
        double h100 = BreakIn.hours(false, 100);
        Check.isTrue(h100 < 5 && h100 > 10 / 60.0, "100 in between (" + h100 + ")");
        // A door of 1,000 health, 1-hour days (2.5 real s per game minute): the whole crowd's thumps add up to it.
        double per = BreakIn.damagePerThump(1000, 10, false, 2.5);
        double seconds = 5 * 60 * 2.5;
        Check.near(1000, per * 10 * BreakIn.THUMPS_PER_SECOND * seconds, 1e-6, "10 zombies deal the health in 5 h");
    }

    public static void testEdgeBatchGrowsWithTheWorld() {
        Check.equal(50, truehordes.population.Respawner.edgeBatch(0), "day 0: 50");
        Check.equal(200, truehordes.population.Respawner.edgeBatch(30), "a month: 200");
        Check.equal(2000, truehordes.population.Respawner.edgeBatch(1000), "never more than 2,000");
    }

    public static void testEdgeArrivalsBecomeOneHorde() {
        truehordes.store.Store s = new truehordes.store.Store();
        truehordes.sim.Simulation sim = new truehordes.sim.Simulation(s, SimulationTest.openMap(200, -1),
            SimulationTest.quiet(), 9, 0);
        List<truehordes.store.ZombieRecord> rs = new ArrayList<>();
        for (int i = 0; i < 60; i++) rs.add(SimulationTest.add(s, 20 + (i % 8), 20 + i / 8, 0));
        sim.edgeHorde(rs, 0.7f);
        Check.equal(1, sim.hordes().size(), "one horde");
        Check.equal(60, sim.hordes().memberCount(), "all 60 in it");
        Check.equal(0, s.size(), "none left as wanderers");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    public static void testColumnSlotsBehindTheLeader() {
        float[] lead = Pilot.slot(0, 9, 100, 100, 0);
        Check.near(100, lead[0], 1e-4, "slot 0 is the leader's spot");
        float[] back = Pilot.slot(8, 9, 100, 100, 0);
        Check.isTrue(back[0] < 100, "later slots are behind (heading east, so west)");
    }

    static Pilot at(long id, float x, float y) {
        Pilot p = new Pilot(id);
        p.x = x;
        p.y = y;
        return p;
    }

    public static void testChainBecomesAHorde() {
        List<Pilot> ps = new ArrayList<>();
        for (int i = 0; i < 10; i++) ps.add(at(i + 1, 100 + i * 1.5f, 100));
        for (int i = 1; i < 10; i++) ps.get(i).followId = i; // each follows the one before
        long[] next = {500};
        Crowd.Rules r = new Crowd.Rules(0, 0, 0, 0, 10, 5);
        var gs = Crowd.update(ps, 0.01, 100, r, id -> false, h -> next[0]++, new Random(1)::nextDouble);
        Check.equal(1, gs.size(), "a chain of 10 is a horde");
        Check.equal(500L, ps.get(5).hordeId, "every member carries the new id");
        Check.equal(0L, ps.get(5).followId, "and follows the horde, not a zombie");
    }

    public static void testWalkingIntoAHordeJoins() {
        List<Pilot> ps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Pilot p = at(i + 1, 200 + i, 200);
            p.hordeId = 7;
            ps.add(p);
        }
        Pilot loner = at(99, 203, 201);
        ps.add(loner);
        Crowd.Rules r = new Crowd.Rules(0, 0, 0, 0, 10, 5);
        Crowd.update(ps, 0.01, 100, r, id -> false, h -> 0, new Random(2)::nextDouble);
        Check.equal(7L, loner.hordeId, "touching a real horde joins it");
    }

    public static void testSmallRealHordeBreaksUpUnlessPartIsHeld() {
        List<Pilot> ps = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Pilot p = at(i + 1, 300 + i * 30, 300);
            p.hordeId = 9;
            ps.add(p);
        }
        Crowd.Rules r = new Crowd.Rules(0, 0, 0, 0, 10, 5);
        Crowd.update(ps, 0.01, 100, r, id -> true, h -> 0, new Random(3)::nextDouble);
        Check.equal(9L, ps.get(0).hordeId, "three real members of a horde still held off screen stay in it");
        Crowd.update(ps, 0.01, 100, r, id -> false, h -> 0, new Random(3)::nextDouble);
        Check.equal(0L, ps.get(0).hordeId, "with nothing held, three break up");
    }

    public static void testBumpIsOneRollPerPair() {
        Pilot a = at(1, 400, 400), b = at(2, 401, 400);
        List<Pilot> ps = new ArrayList<>(List.of(a, b));
        Crowd.Rules never = new Crowd.Rules(0, 0, 0, 0, 10, 5);
        Crowd.update(ps, 0.01, 100, never, id -> false, h -> 0, new Random(4)::nextDouble);
        Check.equal(0L, a.followId, "a failed bump roll");
        Crowd.Rules always = new Crowd.Rules(0, 1, 0, 0, 10, 5);
        Crowd.update(ps, 0.01, 100, always, id -> false, h -> 0, new Random(4)::nextDouble);
        Check.equal(0L, a.followId, "still touching the same one: no new roll");
    }
}
