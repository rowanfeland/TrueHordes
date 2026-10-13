package truehordes.test;

import truehordes.store.Snapshot;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

public final class StoreTest {
    /** A standing record with no outfit, ID, target or mod data. */
    static ZombieRecord add(Store s, float x, float y) {
        return s.add(x, y, 0f, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false);
    }

    public static void testIdsAreStableAndNeverReused() {
        Store s = new Store();
        ZombieRecord a = add(s, 1, 2);
        ZombieRecord b = add(s, 3, 4);
        Check.equal(1L, a.id, "first id");
        Check.equal(2L, b.id, "second id");
        Check.isTrue(s.remove(a.id), "removed");
        Check.equal(3L, add(s, 5, 6).id, "ids are not reused");
        Check.equal(2, s.size(), "size");
    }

    public static void testRestoreKeepsIdsAndAdvancesCounter() {
        Store s = new Store();
        s.restore(new ZombieRecord(40, 0, 0, 0f, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false));
        Check.equal(41L, s.nextId(), "next id after restored max");
        Check.throwsAny(() -> s.restore(new ZombieRecord(40, 0, 0, 0f, (byte) 0, 0, 0, 0,
            ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false)), "duplicate id rejected");
        s.setNextId(10);
        Check.equal(41L, s.nextId(), "never moves backwards");
    }

    public static void testChunkIndexFollowsAddMoveRemove() {
        Store s = new Store();
        ZombieRecord a = add(s, 9.5f, 17.2f);    // chunk (1, 2)
        ZombieRecord b = add(s, -0.5f, -8.1f);   // chunk (-1, -2)
        Check.equal(1, s.inChunk(1, 2).size(), "a indexed");
        Check.equal(1, s.inChunk(-1, -2).size(), "negative coordinates use floor");
        s.moved(a, 30f, 17f);                    // chunk (3, 2)
        Check.equal(0, s.inChunk(1, 2).size(), "left the old chunk");
        Check.equal(a, s.inChunk(3, 2).get(0), "in the new chunk");
        Check.near(30f, a.x(), 0, "position updated");
        s.remove(b.id);
        Check.equal(0, s.inChunk(-1, -2).size(), "removed from the index");
        s.inChunk(3, 2).clear();
        Check.equal(1, s.inChunk(3, 2).size(), "inChunk returns a copy");
    }

    public static void testPersistentIdIndex() {
        Store s = new Store();
        ZombieRecord r = s.add(5, 5, 0f, (byte) 2, 77, 3, 1234, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, null, false);
        add(s, 6, 6); // persistent ID 0 is not indexed
        Check.equal(r, s.byPersistentId(1234), "found by persistent ID");
        Check.equal(null, s.byPersistentId(0), "0 means none");
        s.remove(r.id);
        Check.equal(null, s.byPersistentId(1234), "gone after remove");
    }

    public static void testCountInCell() {
        Store s = new Store();
        add(s, 10, 10);
        add(s, 255, 255);
        add(s, 256, 0);
        ZombieRecord m = add(s, -1, -1);
        Check.equal(2, s.countInCell(0, 0), "two in cell (0,0)");
        Check.equal(1, s.countInCell(1, 0), "one in cell (1,0)");
        Check.equal(1, s.countInCell(-1, -1), "negative cell");
        s.moved(m, 300, 300);
        Check.equal(0, s.countInCell(-1, -1), "moved out");
        Check.equal(1, s.countInCell(1, 1), "moved in");
        s.clear();
        Check.equal(0, s.size(), "cleared");
        Check.equal(0, s.countInCell(0, 0), "counts cleared");
        Check.equal(1L, s.nextId(), "counter reset on clear");
    }

    public static void testSnapshotPointsInsideRectangleOnly() {
        Store s = new Store();
        add(s, 10, 10);
        add(s, 50, 50);
        add(s, 500, 500);
        Snapshot snap = s.snapshot(0, 0, 100, 100, 10);
        Check.isTrue(!snap.density, "points mode");
        Check.equal(2, snap.items, "two points");
        Check.equal(2, snap.total, "total in view");
        Check.near(10, snap.data[0], 0, "x of first");
        Check.near(50, snap.data[3], 0, "y of second");
    }

    public static void testSnapshotSwitchesToDensity() {
        Store s = new Store();
        for (int i = 0; i < 100; i++) add(s, 3 + (i % 2), 3);
        for (int i = 0; i < 20; i++) add(s, 200, 200);
        Snapshot snap = s.snapshot(0, 0, 256, 256, 50);
        Check.isTrue(snap.density, "density mode when too many points");
        Check.equal(120, snap.total, "all counted");
        Check.isTrue(snap.cellSize >= 8, "cell size at least 8");
        Check.isTrue(snap.items * 3 == snap.data.length, "triples");
        Check.equal(100, snap.maxCount, "densest cell");
        int sum = 0;
        for (int i = 2; i < snap.data.length; i += 3) sum += (int) snap.data[i];
        Check.equal(120, sum, "counts add up");
        Check.isTrue(snap.items <= 50, "no more cells than maxPoints");
    }
}
