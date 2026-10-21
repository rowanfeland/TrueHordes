package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import truehordes.real.BreakIn;
import truehordes.real.Crowd;
import truehordes.real.Pilot;

/** Sub-project 6: the real zone, crowd break-in times, real-horde columns and forming. */
public final class BridgeTest {
    private BridgeTest() {}

    public static void testBreakInTimesFollowD15() {
        Check.near(24 * 0.72, BreakIn.hours(true, 1), 1e-9, "a lone zombie: a window in 28% less than a door");
        Check.near(24, BreakIn.hours(false, 1), 1e-9, "a lone zombie: a door in 24 h");
        Check.near(5 * 0.72, BreakIn.hours(true, 10), 1e-9, "10: a window in 3.6 h");
        Check.near(5, BreakIn.hours(false, 10), 1e-9, "10: a door in 5 h");
        Check.near(10 / 60.0, BreakIn.hours(false, 1000), 1e-9, "1,000: 10 minutes");
        Check.near(10 / 60.0 * 0.72, BreakIn.hours(true, 5000), 1e-9, "more than 1,000: no faster");
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

    public static void testOffScreenGunfireLeavesCorpses() {
        truehordes.store.Store s = new truehordes.store.Store();
        truehordes.sim.Simulation sim = new truehordes.sim.Simulation(s, SimulationTest.openMap(200, -1),
            SimulationTest.quiet(), 12, 24 * 10);
        List<truehordes.store.ZombieRecord> rs = new ArrayList<>();
        for (int i = 0; i < 30; i++) rs.add(SimulationTest.add(s, 400 + (i % 6), 400 + i / 6, 0));
        sim.edgeHorde(rs, 0f);
        for (int i = 0; i < 10; i++) SimulationTest.add(s, 405 + i, 395, 0);
        SimulationTest.add(s, 900, 900, 0); // far away: safe
        int dead = sim.shootDead(405, 400, 25, 12);
        Check.equal(12, dead, "twelve shot");
        Check.equal(41L - 12, sim.held(), "gone from the population");
        Check.equal(12, sim.aftermath().corpseCount(), "each leaves a corpse");
        Check.equal("", sim.checkConservation("test"), "conservation (counted as deaths)");
        Check.equal(28 - 2, sim.shootDead(405, 400, 25, 1000), "everyone in reach but the far one and the last two of the horde");
    }

    public static void testRingHordesWaitUntilASound() {
        truehordes.store.Store s = new truehordes.store.Store();
        truehordes.sim.Simulation sim = new truehordes.sim.Simulation(s, SimulationTest.openMap(200, -1),
            SimulationTest.quiet(), 10, 24 * 10);
        int placed = truehordes.sim.TestHordes.ring(sim, s, 6, 50, 120, 800, 800, new Random(5));
        Check.equal(6, placed, "six hordes placed");
        Check.equal(300, sim.hordes().memberCount(), "of 50 each");
        for (truehordes.sim.Horde h : sim.hordes().all()) {
            Check.isTrue(h.dormant, "dormant");
            Check.near(120, (float) Math.hypot(h.x - 800, h.y - 800), 12, "on the circle");
        }
        Check.equal("", sim.checkConservation("test"), "conservation (a population change, counted)");
    }

    public static void testFarSightSkipsTheLoadedAreaNearestFirst() {
        truehordes.store.Store s = new truehordes.store.Store();
        truehordes.sim.Simulation sim = new truehordes.sim.Simulation(s, SimulationTest.openMap(200, -1),
            SimulationTest.quiet(), 11, 0);
        sim.setPlayers(new truehordes.population.PlayerNearby() {
            @Override public boolean nearCell(int cx, int cy) { return true; }
            @Override public boolean nearChunk(int cx, int cy) { return Math.abs(cx - 100) <= 9 && Math.abs(cy - 100) <= 9; }
        });
        SimulationTest.add(s, 810, 810, 0);       // inside the loaded area: not drawn
        SimulationTest.add(s, 950, 804, 0);       // 150 out
        SimulationTest.add(s, 900, 804, 0);       // 100 out
        float[] f = truehordes.sim.FarSight.figures(sim, 804, 804, 200, 10);
        Check.equal(2 * truehordes.sim.FarSight.STRIDE, f.length, "two figures beyond the loaded area");
        Check.near(900, f[0], 0.01, "nearest first");
    }

    /** A real horde near a player moans like a held one: a held wanderer in earshot heads its way. */
    public static void testHeldZombiesHearRealHordes() {
        truehordes.store.Store s = new truehordes.store.Store();
        truehordes.sim.Simulation sim = new truehordes.sim.Simulation(s, SimulationTest.openMap(200, -1),
            SimulationTest.quiet().toBuilder().moanResponsePerHour(1).travellerShare(1).build(), 12, 24 * 60);
        truehordes.store.ZombieRecord r = SimulationTest.add(s, 800, 800, (float) (-Math.PI / 2)); // walking north, the horde to the east
        sim.setRealHordes(new float[] {4242, 860, 800, 400}); // 400 moan about 75 tiles out
        for (double t = 24 * 60; t < 24 * 60 + 3; t += 1.0 / 60) sim.step(t, Long.MAX_VALUE);
        Check.isTrue(r.x() > 820, "it turned towards the real horde's moaning (x " + r.x() + ")");
    }

    public static void testColumnSlotsBehindTheLeader() {
        float[] lead = Pilot.slot(0, 9, 100, 100, 0);
        Check.near(100, lead[0], 1e-4, "slot 0 is the leader's spot");
        float[] back = Pilot.slot(8, 9, 100, 100, 0);
        Check.isTrue(back[0] < 100, "later slots are behind (heading east, so west)");
    }

    public static void testChaseGoesToTheSpotThenOnAlongTheHeading() {
        Pilot p = at(1, 90, 100);
        p.chase(100, 100, 3, 0, 5, 60);
        Check.near(100, p.lureX, 1e-4, "first to where it last saw the player");
        Check.isTrue(p.chasing(4.9) && !p.chasing(5.1), "for the chase time");
        Check.isTrue(p.chaseOn(), "at the spot it goes on");
        Check.near(100 + Pilot.CHASE_FAR, p.lureX, 1e-3, "on along the player's heading");
        Check.near(100, p.lureY, 1e-3, "same line");
        Check.isTrue(p.chasing(4.9), "still chasing on the second leg");
        Check.isTrue(!p.chaseOn(), "only once");
    }

    public static void testAStandingPlayerLeavesNoHeading() {
        Pilot p = at(1, 90, 100);
        p.chase(100, 100, 0, 0, 5, 60);
        Check.isTrue(!p.chaseOn(), "no heading: it stays at the spot");
    }

    public static void testANewLureEndsTheChase() {
        Pilot p = at(1, 90, 100);
        p.chase(100, 100, 1, 0, 5, 60);
        p.lure(0, 0, 6, 80);
        Check.isTrue(!p.chasing(1), "a louder sound took over");
        Check.isTrue(!p.chaseOn(), "and the second leg is gone");
    }

    public static void testHordeFollowsOnlyWhenMoreThanHalfChase() {
        List<Pilot> ps = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Pilot p = at(i + 1, 100 + i, 100);
            p.hordeId = 7;
            ps.add(p);
        }
        Crowd.Group g = Crowd.groups(ps).get(7L);
        for (int i = 0; i < 5; i++) ps.get(i).chase(200, 100, 1, 0, 5, 60);
        Check.equal(0, Crowd.chaseRule(g, 1), "half is not enough");
        Check.isTrue(!ps.get(9).chasing(1), "the rest stay");
        Check.isTrue(g.leader(1) == ps.get(5), "the first one not chasing leads the rest");
        ps.get(5).chase(200, 100, 1, 0, 5, 60);
        Check.equal(4, Crowd.chaseRule(g, 1), "more than half: the other four follow");
        Check.isTrue(ps.get(9).chasing(1), "all chasing");
        Check.near(200, ps.get(9).lureX, 1e-4, "to the same spot");
        Check.isTrue(ps.get(9).chaseOn(), "and on along the same heading");
    }

    public static void testSiegesMergeByChunkAndKeepTheBiggestCrowd() {
        truehordes.sim.Aftermath a = new truehordes.sim.Aftermath();
        a.linger(100, 100, 20, 1);
        a.linger(102, 101, 50, 2);
        a.linger(140, 100, 10, 1);
        var s = a.takeSieges(12, 12);
        Check.equal(1, s.size(), "two crowds at one spot are one siege");
        Check.equal(50, s.get(0).crowd, "the biggest crowd counts");
        Check.near(3, s.get(0).hours, 1e-9, "hours add up");
        Check.equal(0, a.takeSieges(12, 12).size(), "taken once");
        Check.equal(1, a.siegeCount(), "the other chunk's siege waits");
    }

    public static void testAftermathSurvivesASave() {
        truehordes.sim.Aftermath a = new truehordes.sim.Aftermath();
        a.linger(100, 100, 20, 1.5);
        a.corpse(101, 99, 0, 7);
        truehordes.sim.Aftermath b = new truehordes.sim.Aftermath();
        b.load(a.siegesString(), a.corpsesString());
        Check.near(1.5, b.takeSieges(12, 12).get(0).hours, 1e-9, "siege kept");
        float[] c = b.takeCorpses(12, 12).get(0);
        Check.near(7, c[3], 1e-9, "corpse outfit kept");
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
