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

        Rig() { this(SimSettings.defaults().toBuilder().followChancePerHour(0).build(), 1); }

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
        Rig rig = new Rig();
        List<ZombieRecord> line = new ArrayList<>();
        for (int i = 0; i < 10; i++) line.add(rig.wanderer(100 - i * 1.5f, 100, 0, false));
        for (int i = 1; i < 9; i++) line.get(i).followId = line.get(i - 1).id;
        for (ZombieRecord r : line) rig.enc.wanderer(r, 1, 0.1);
        Check.equal(0, rig.hordes.size(), "a chain of 9 is not a horde");
        line.get(9).followId = line.get(8).id;
        rig.enc.wanderer(line.get(9), 1, 0.1);
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
        rig.enc.wanderer(b, 1, 1.0);
        Check.equal(a.id, b.followId, "a zombie that sees another walking past falls in behind it");
    }

    public static void testWandererJoinsSeenHorde() {
        Rig rig = new Rig();
        Horde h = rig.horde(110, 100, 0, 20);
        ZombieRecord r = rig.wanderer(100, 100, 0, false);
        rig.enc.wanderer(r, 1, 0.1);
        Check.equal(21, h.size(), "joined");
        Check.equal(h.id, r.hordeId, "knows its horde");
        Check.equal(0, rig.store.size(), "no longer a wanderer");
    }

    public static void testWandererBehindHordeDoesNotSee() {
        Rig rig = new Rig();
        Horde h = rig.horde(115, 100, 0, 20);
        ZombieRecord r = rig.wanderer(100, 100, (float) Math.PI, false);
        for (int i = 0; i < 200; i++) rig.enc.wanderer(r, 1, 0.1);
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
}
