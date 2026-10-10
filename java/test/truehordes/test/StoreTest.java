package truehordes.test;

import truehordes.store.Entity;
import truehordes.store.Snapshot;
import truehordes.store.Store;

public final class StoreTest {
    public static void testIdsAreStableAndNeverReused() {
        Store s = new Store();
        Entity a = s.create(Entity.KIND_PLACEHOLDER, 1, 2, (byte) 0);
        Entity b = s.create(Entity.KIND_PLACEHOLDER, 3, 4, (byte) 0);
        Check.equal(1L, a.id, "first id");
        Check.equal(2L, b.id, "second id");
        Check.isTrue(s.remove(a.id), "removed");
        Entity c = s.create(Entity.KIND_PLACEHOLDER, 5, 6, (byte) 0);
        Check.equal(3L, c.id, "ids are not reused");
        Check.equal(2, s.size(), "size");
    }

    public static void testRestoreKeepsIdsAndAdvancesCounter() {
        Store s = new Store();
        s.restore(new Entity(40, Entity.KIND_PLACEHOLDER, 0, 0, (byte) 0));
        Check.equal(41L, s.nextId(), "next id after restored max");
        Check.throwsAny(() -> s.restore(new Entity(40, Entity.KIND_PLACEHOLDER, 0, 0, (byte) 0)), "duplicate id rejected");
        s.setNextId(10);
        Check.equal(41L, s.nextId(), "never moves backwards");
    }

    public static void testRemoveKindAndClear() {
        Store s = new Store();
        s.create(Entity.KIND_PLACEHOLDER, 0, 0, (byte) 0);
        s.create((byte) 5, 0, 0, (byte) 0);
        Check.equal(1, s.removeKind(Entity.KIND_PLACEHOLDER), "removed placeholders");
        Check.equal(1, s.size(), "other kind kept");
        s.clear();
        Check.equal(0, s.size(), "cleared");
        Check.equal(1L, s.nextId(), "counter reset on clear");
    }

    public static void testSnapshotPointsInsideRectangleOnly() {
        Store s = new Store();
        s.create(Entity.KIND_PLACEHOLDER, 10, 10, (byte) 0);
        s.create(Entity.KIND_PLACEHOLDER, 50, 50, (byte) 0);
        s.create(Entity.KIND_PLACEHOLDER, 500, 500, (byte) 0);
        Snapshot snap = s.snapshot(0, 0, 100, 100, 10);
        Check.isTrue(!snap.density, "points mode");
        Check.equal(2, snap.items, "two points");
        Check.equal(2, snap.total, "total in view");
        Check.near(10, snap.data[0], 0, "x of first");
        Check.near(50, snap.data[3], 0, "y of second");
    }

    public static void testSnapshotSwitchesToDensity() {
        Store s = new Store();
        for (int i = 0; i < 100; i++) s.create(Entity.KIND_PLACEHOLDER, 3 + (i % 2), 3, (byte) 0);
        for (int i = 0; i < 20; i++) s.create(Entity.KIND_PLACEHOLDER, 200, 200, (byte) 0);
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
