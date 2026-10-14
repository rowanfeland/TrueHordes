package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.sim.Horde;
import truehordes.sim.Hordes;
import truehordes.sim.SpatialGrid;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

public final class SpatialGridTest {
    private SpatialGridTest() {}

    static ZombieRecord rec(Store s, float x, float y) {
        return s.add(x, y, 0, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false);
    }

    static List<ZombieRecord> members(Store s, int n) {
        List<ZombieRecord> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(rec(s, 0, 0));
        return out;
    }

    public static void testSpreadAndLoudnessGrowWithSize() {
        Store s = new Store();
        Hordes hs = new Hordes();
        Horde small = hs.create(0, 0, 0, members(s, 10));
        Horde big = hs.create(0, 0, 0, members(s, 10000));
        Check.near(2 + Math.sqrt(10) * 0.5, small.spread(), 1e-4, "spread of 10");
        Check.near(52, big.spread(), 1e-4, "spread of 10,000");
        Check.near(50, small.loudnessDb(), 1e-4, "10 zombies moan at 50 dB");
        Check.near(70, hs.create(0, 0, 0, members(s, 1000)).loudnessDb(), 1e-4, "1,000 at 70 dB");
    }

    public static void testCreateSetsMembersHorde() {
        Store s = new Store();
        Hordes hs = new Hordes();
        Horde h = hs.create(5, 5, 0, members(s, 12));
        for (ZombieRecord r : h.members) Check.equal(h.id, r.hordeId, "member knows its horde");
        Check.equal(12, hs.memberCount(), "member count");
        Check.equal(h, hs.get(h.id), "lookup by id");
        hs.remove(h.id);
        Check.equal(0, hs.memberCount(), "removed");
        Check.isTrue(hs.get(h.id) == null, "gone");
    }

    public static void testNearFindsWanderersByDistance() {
        Store s = new Store();
        SpatialGrid g = new SpatialGrid();
        ZombieRecord a = rec(s, 115, 100), b = rec(s, 125, 100);
        g.putWanderer(a);
        g.putWanderer(b);
        List<ZombieRecord> found = new ArrayList<>();
        g.near(100, 100, 20, found::add, h -> {});
        Check.isTrue(found.contains(a), "15 tiles away is found");
        Check.isTrue(!found.contains(b), "25 tiles away is not");
    }

    public static void testNearFindsBigHordeByItsEdge() {
        Store s = new Store();
        Hordes hs = new Hordes();
        SpatialGrid g = new SpatialGrid();
        Horde h = hs.create(500, 500, 0, members(s, 3136)); // spread 2 + 56*0.5 = 30
        g.putHorde(h);
        List<Horde> found = new ArrayList<>();
        g.near(555, 500, 30, r -> {}, found::add);
        Check.equal(1, found.size(), "horde edge within reach is found once");
        found.clear();
        g.near(600, 500, 30, r -> {}, found::add);
        Check.equal(0, found.size(), "too far");
    }

    public static void testMovesKeepOneEntry() {
        Store s = new Store();
        SpatialGrid g = new SpatialGrid();
        ZombieRecord a = rec(s, 4, 4);
        g.putWanderer(a);
        float ox = a.x(), oy = a.y();
        s.moved(a, 100, 100);
        g.moveWanderer(a, ox, oy);
        List<ZombieRecord> found = new ArrayList<>();
        g.near(4, 4, 5, found::add, h -> {});
        Check.equal(0, found.size(), "left the old chunk");
        g.near(100, 100, 5, found::add, h -> {});
        Check.equal(1, found.size(), "in the new chunk once");
        Check.equal(1, g.wandererCount(), "one entry");
        g.removeWanderer(a);
        Check.equal(0, g.wandererCount(), "removed");
    }

    public static void testRemovingHordeClearsBuckets() {
        Store s = new Store();
        Hordes hs = new Hordes();
        SpatialGrid g = new SpatialGrid();
        Horde h = hs.create(200, 200, 0, members(s, 900));
        g.putHorde(h);
        float ox = h.x, oy = h.y;
        h.x = 400;
        g.moveHorde(h, ox, oy, h.spread());
        List<Horde> found = new ArrayList<>();
        g.near(200, 200, 5, r -> {}, found::add);
        Check.equal(0, found.size(), "moved away");
        g.removeHorde(h);
        g.near(400, 200, 5, r -> {}, found::add);
        Check.equal(0, found.size(), "removed");
        Check.equal(0, g.hordeEntries(), "no bucket entries left");
    }
}
