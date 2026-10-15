package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import truehordes.sim.Encounters;
import truehordes.sim.Horde;
import truehordes.sim.Hordes;
import truehordes.sim.SimSettings;
import truehordes.sim.SoundEvent;
import truehordes.sim.SpatialGrid;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

public final class EncountersTest {
    private EncountersTest() {}

    /** Store, hordes and grid wired the way the simulation wires them. */
    static final class Rig {
        final Store store = new Store();
        final Hordes hordes = new Hordes();
        final SpatialGrid grid = new SpatialGrid();
        final Encounters enc;

        Rig(SimSettings s, long seed) {
            store.setListener(grid);
            enc = new Encounters(store, hordes, grid, s, new Random(seed));
        }

        Rig() { this(SimSettings.defaults().toBuilder().followChancePerHour(0).joinChancePerHour(1).build(), 1); }

        ZombieRecord wanderer(float x, float y, float heading, boolean building) {
            ZombieRecord r = store.add(x, y, 0, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET,
                null, building);
            r.heading = heading;
            r.strRolled = true;
            return r;
        }

        Horde horde(float x, float y, float heading, int n) {
            List<ZombieRecord> m = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                m.add(new ZombieRecord(1_000_000 + hordes.memberCount() + i, x, y, 0, (byte) 0, 0, 0, 0,
                    ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false));
            }
            Horde h = hordes.create(x, y, heading, m);
            grid.putHorde(h);
            return h;
        }

        int total() { return store.size() + hordes.memberCount(); }
    }

    public static void testStandingCrowdNeverForms() {
        Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(1).build(), 2);
        List<ZombieRecord> crowd = new ArrayList<>();
        for (int i = 0; i < 250; i++) crowd.add(rig.wanderer(100 + (i % 8), 100 + (i / 32), 0, true));
        for (int hour = 0; hour < 30 * 24; hour++) {
            for (ZombieRecord r : crowd) rig.enc.wanderer(r, hour, 1.0);
        }
        Check.equal(0, rig.hordes.size(), "idle zombies standing together never form a horde");
        Check.equal(250, rig.total(), "nobody lost");
    }

    public static void testFollowChainFormsAtTen() {
        Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(0).joinChancePerHour(1)
            .bumpFollowChance(0).build(), 1);
        List<ZombieRecord> line = new ArrayList<>();
        for (int i = 0; i < 10; i++) line.add(rig.wanderer(100 - i * 1.5f, 100, 0, false));
        for (int i = 1; i < 9; i++) line.get(i).followId = line.get(i - 1).id;
        for (ZombieRecord r : line) rig.enc.wanderer(r, LATE, 0.1);
        Check.equal(0, rig.hordes.size(), "a chain of 9 is not a horde");
        line.get(9).followId = line.get(8).id;
        rig.enc.wanderer(line.get(9), LATE, 0.1);
        Check.equal(1, rig.hordes.size(), "the 10th follower makes a horde");
        Horde h = rig.hordes.all().iterator().next();
        Check.equal(10, h.size(), "all ten are members");
        Check.equal(0, rig.store.size(), "no wanderers left");
        Check.near(100, h.x, 1e-3, "horde starts at the head");
    }

    public static void testRandomFollowingStartsChains() {
        Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(1).build(), 3);
        ZombieRecord a = rig.wanderer(100, 100, 0, false);
        ZombieRecord b = rig.wanderer(95, 100, 0, false);
        rig.enc.wanderer(b, LATE, 1.0);
        Check.equal(a.id, b.followId, "a zombie that sees another walking past falls in behind it");
    }

    public static void testWandererJoinsSeenHorde() {
        Rig rig = new Rig();
        Horde h = rig.horde(110, 100, 0, 20);
        ZombieRecord r = rig.wanderer(100, 100, 0, false);
        rig.enc.wanderer(r, LATE, 0.1);
        Check.equal(21, h.size(), "joined");
        Check.equal(h.id, r.hordeId, "knows its horde");
        Check.equal(0, rig.store.size(), "no longer a wanderer");
    }

    /** User decision 2026-10-14 (A): a wanderer that sees a horde joins with a chance per hour, not at once. */
    public static void testJoiningTakesTime() {
        Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(0).joinChancePerHour(0.2).build(), 8);
        Horde h = rig.horde(5000, 5000, 0, 20);
        int joined = 0;
        for (int i = 0; i < 2000; i++) {
            ZombieRecord r = rig.wanderer(4982, 5000, 0, false); // in sight, not touching even as the horde grows
            rig.enc.wanderer(r, LATE, 1.0);
            if (rig.store.get(r.id) == null) joined++;
            else rig.store.remove(r.id);
        }
        Check.isTrue(joined > 300 && joined < 500, "about 20% join within an hour of seeing it (" + joined + ")");
        Check.isTrue(h.size() == 20 + joined, "joined ones are members");
    }

    /** In-game test 2026-10-15: zombies walked into hordes without joining. Touching a horde joins it at once. */
    public static void testTouchingAHordeJoinsAtOnce() {
        Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(0).joinChancePerHour(0).build(), 9);
        Horde h = rig.horde(5000, 5000, 0, 100); // spread 7
        ZombieRecord r = rig.wanderer(5003, 5000, (float) Math.PI, false); // inside its circle, facing away
        rig.enc.wanderer(r, LATE, 0.01);
        Check.equal(101, h.size(), "bumped into it: joined");
    }

    /** In-game test 2026-10-15: no snowball on roads. Bumping into another walker usually means falling in behind. */
    public static void testBumpingIntoAWalkerOftenFollows() {
        int followed = 0;
        for (int seed = 0; seed < 200; seed++) {
            // Spread the seeds: java.util.Random's first number is nearly the same for small consecutive seeds.
            Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(0).bumpFollowChance(0.5).build(),
                seed * 0x9E3779B97F4A7C15L);
            ZombieRecord a = rig.wanderer(5000, 5000, 0, false);
            ZombieRecord b = rig.wanderer(4998.5f, 5000, 0, false);
            rig.enc.wanderer(b, LATE, 0.01);
            if (b.followId == a.id) followed++;
        }
        Check.isTrue(followed > 70 && followed < 130, "about half fall in behind on a bump (" + followed + "/200)");
    }

    /** The bump-follow chance is a sandbox option: 0 turns falling in behind on a bump off. */
    public static void testBumpFollowChanceIsAnOption() {
        for (int seed = 0; seed < 50; seed++) {
            Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(0).bumpFollowChance(0).build(),
                seed * 0x9E3779B97F4A7C15L);
            ZombieRecord a = rig.wanderer(5000, 5000, 0, false);
            ZombieRecord b = rig.wanderer(4998.5f, 5000, 0, false);
            rig.enc.wanderer(b, LATE, 0.01);
            Check.equal(0L, b.followId, "never follows with the chance at 0");
            Check.isTrue(a != null, "added");
        }
    }

    public static void testWandererBehindHordeDoesNotSee() {
        Rig rig = new Rig();
        Horde h = rig.horde(115, 100, 0, 20);
        ZombieRecord r = rig.wanderer(100, 100, (float) Math.PI, false);
        for (int i = 0; i < 200; i++) rig.enc.wanderer(r, LATE, 0.1);
        Check.equal(20, h.size(), "a horde behind it is not seen");
    }

    public static void testStragglerNeverJoinsOrFollows() {
        Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(1).build(), 4);
        Horde h = rig.horde(105, 100, 0, 20);
        ZombieRecord r = rig.wanderer(100, 100, 0, false);
        r.straggler = true;
        rig.wanderer(103, 102, 0, false);
        for (int i = 0; i < 100; i++) rig.enc.wanderer(r, i, 1.0);
        Check.equal(20, h.size(), "straggler never joins");
        Check.equal(0L, r.followId, "straggler never follows");
    }

    public static void testStragglerRateAboutOneIn75() {
        Rig rig = new Rig();
        Random rng = new Random(5);
        int n = 100_000, s = 0;
        for (int i = 0; i < n; i++) {
            ZombieRecord r = new ZombieRecord(i + 1, 0, 0, 0, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET,
                ZombieRecord.NO_TARGET, null, false);
            rig.enc.rollStraggler(r);
            Check.isTrue(r.strRolled, "rolled once");
            if (r.straggler) s++;
        }
        Check.isTrue(s > n / 95 && s < n / 60, "about 1 in 75 (" + s + ")");
    }

    public static void testHordesMergeOnlyByTouch() {
        Rig rig = new Rig();
        Horde big = rig.horde(0, 0, 0, 2000);
        float gap = big.spread() + Horde.spreadOf(50) + 1;
        Horde small = rig.horde(gap, 0, (float) (Math.PI / 2), 50);
        rig.enc.horde(big, 1, 0);
        rig.enc.horde(small, 1, 0);
        Check.equal(2, rig.hordes.size(), "1 tile apart: no merge, even in sight");
        small.x = 25;
        rig.grid.moveHorde(small, gap, 0, small.spread());
        Horde survivor = rig.enc.horde(small, 1, 0);
        Check.equal(1, rig.hordes.size(), "touching: merged");
        Check.equal(big, survivor, "the bigger horde keeps its identity");
        Check.equal(2050, big.size(), "all members kept");
        Check.isTrue(Math.abs(big.heading) < Math.toRadians(5), "a 50-horde barely turns a 2,000-horde");
        Check.equal(2050, rig.total(), "conservation");
    }

    public static void testBreakAwayOnlyInTightSpots() {
        Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(0).breakAwayChance(0.005).build(), 6);
        Horde h = rig.horde(100, 100, 0, 100);
        for (int i = 0; i < 1000; i++) rig.enc.horde(h, 1, 0);
        Check.equal(100, h.size(), "open road sheds nobody");
        for (int i = 0; i < 20; i++) rig.enc.horde(h, 1 + i, 1);
        Check.isTrue(h.size() < 100, "an alley leaves some behind (" + h.size() + ")");
        Check.isTrue(rig.store.size() > 0, "left-behind zombies are wanderers");
        for (ZombieRecord r : rig.store.all()) {
            Check.isTrue(r.lingerUntilHours > 1, "they linger there");
            Check.equal(h.id, r.hordeId, "they remember the horde");
        }
        Check.equal(100, rig.total(), "conservation");
    }

    public static void testDissolveAtFour() {
        Rig rig = new Rig();
        Horde five = rig.horde(100, 100, 0, 5);
        Check.isTrue(rig.enc.horde(five, 1, 0) != null, "5 stays a horde");
        Horde four = rig.horde(500, 500, 0, 4);
        Check.isTrue(rig.enc.horde(four, 1, 0) == null, "4 dissolves");
        Check.isTrue(rig.hordes.get(four.id) == null, "gone");
        Check.equal(4, rig.store.size(), "its zombies are wanderers again");
        Check.equal(9, rig.total(), "conservation");
    }

    public static void testSoundConvergenceFormsHorde() {
        Rig rig = new Rig();
        SoundEvent e = new SoundEvent(7, 200, 200, 90, 0, 1, false, 0);
        for (int i = 0; i < 12; i++) rig.enc.responded(e, rig.wanderer(200 + (i % 4), 200 + (i / 4), 0, false));
        rig.enc.soundEnded(e);
        Check.equal(1, rig.hordes.size(), "12 that gathered at the sound become a horde");
        Check.equal(12, rig.hordes.all().iterator().next().size(), "all twelve");
        SoundEvent f = new SoundEvent(8, 900, 900, 90, 0, 1, false, 0);
        for (int i = 0; i < 8; i++) rig.enc.responded(f, rig.wanderer(900 + i, 900, 0, false));
        rig.enc.soundEnded(f);
        Check.equal(1, rig.hordes.size(), "8 is too few");
        Check.equal(20, rig.total(), "conservation");
    }

    /** After the population peak (default day 28) the bump-follow ramp is at full strength. */
    static final double LATE = 24 * 40;

    /**
     * User, 2026-10-15: day 1 is pure chaos (no falling in behind on a bump), day 2 about 1-2%, then slowly up to 25% by
     * day 7, 50% by day 14 and the full chance on the population peak day.
     */
    public static void testBumpFollowRampsUpOverTheDays() {
        Check.isTrue(Encounters.bumpRamp(0.5, 28) == 0, "day 1: none");
        double d2 = Encounters.bumpRamp(1.5, 28);
        Check.isTrue(d2 >= 0.01 && d2 <= 0.02, "day 2: 1-2% (" + d2 + ")");
        Check.isTrue(Math.abs(Encounters.bumpRamp(7, 28) - 0.25) < 1e-9, "day 7: 25%");
        Check.isTrue(Math.abs(Encounters.bumpRamp(14, 28) - 0.5) < 1e-9, "day 14: 50%");
        Check.isTrue(Math.abs(Encounters.bumpRamp(21, 28) - 0.75) < 1e-9, "half way to the peak: 75%");
        Check.isTrue(Encounters.bumpRamp(28, 28) == 1 && Encounters.bumpRamp(90, 28) == 1, "peak day on: full");
        double prev = 0;
        for (double d = 0; d < 40; d += 0.25) {
            double v = Encounters.bumpRamp(d, 28);
            Check.isTrue(v >= prev, "never goes down");
            prev = v;
        }
        Check.isTrue(Encounters.bumpRamp(20, 10) == 1, "a peak day before day 14 still ends at full");
    }

    public static void testNoBumpFollowingOnDayOne() {
        for (int seed = 0; seed < 100; seed++) {
            Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(0).build(), seed * 0x9E3779B97F4A7C15L);
            ZombieRecord a = rig.wanderer(5000, 5000, 0, false);
            ZombieRecord b = rig.wanderer(4998.5f, 5000, 0, false);
            rig.enc.wanderer(b, 12, 0.01);
            Check.equal(0L, b.followId, "day 1: nobody falls in behind");
            Check.isTrue(a != null, "added");
        }
    }

    /** User, 2026-10-15 (option "both"): on day 1 nobody falls in behind a zombie it sees walking past either. */
    public static void testNoSightFollowingOnDayOne() {
        Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(1).build(), 3);
        rig.wanderer(100, 100, 0, false);
        ZombieRecord b = rig.wanderer(95, 100, 0, false);
        for (int i = 0; i < 20; i++) rig.enc.wanderer(b, 0.5 * i, 1.0);
        Check.equal(0L, b.followId, "day 1: no sight-following");
    }

    /** Joining a horde seen (not touched) is on the same ramp: none on day 1. */
    public static void testNoJoiningBySightOnDayOne() {
        Rig rig = new Rig();
        Horde h = rig.horde(110, 100, 0, 20);
        ZombieRecord r = rig.wanderer(100, 100, 0, false);
        for (int i = 0; i < 20; i++) rig.enc.wanderer(r, 0.5 * i, 1.0);
        Check.equal(20, h.size(), "day 1: seeing a horde does not pull it in");
    }

    /** A bump is one roll when two zombies first touch, not a new roll on every update they stay touching. */
    public static void testBumpRolledOncePerContact() {
        int followed = 0;
        for (int seed = 0; seed < 200; seed++) {
            Rig rig = new Rig(SimSettings.defaults().toBuilder().followChancePerHour(0).bumpFollowChance(0.1).build(),
                seed * 0x9E3779B97F4A7C15L);
            ZombieRecord a = rig.wanderer(5000, 5000, 0, false);
            ZombieRecord b = rig.wanderer(4998.5f, 5000, 0, false);
            for (int i = 0; i < 30; i++) rig.enc.wanderer(b, LATE, 0.01);
            if (b.followId == a.id) followed++;
        }
        Check.isTrue(followed > 5 && followed < 40, "about 10% over 30 updates touching (" + followed + "/200)");
    }
}
