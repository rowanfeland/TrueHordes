package truehordes.test;

import java.util.List;
import java.util.Random;
import truehordes.population.CellBook;
import truehordes.population.EdgeChunks;
import truehordes.population.Formula;
import truehordes.population.MapSource;
import truehordes.population.PlayerNearby;
import truehordes.population.PopulationSettings;
import truehordes.population.Respawner;
import truehordes.population.Seeder;
import truehordes.store.Store;

public final class RespawnerTest {
    /** Two cells side by side; every chunk intensity 40 (value 26 at the default multiplier), all walkable. */
    static final class Map2 implements MapSource {
        @Override public int minChunkX() { return 0; }
        @Override public int minChunkY() { return 0; }
        @Override public int maxChunkX() { return 63; }
        @Override public int maxChunkY() { return 31; }
        @Override public int intensity(int cx, int cy) { return 40; }
        @Override public List<RoomInfo> rooms() { return List.of(); }
    }

    static final MapSource MAP = new Map2();
    static final PlayerNearby NOBODY = new PlayerNearby() {
        @Override public boolean nearCell(int cellX, int cellY) { return false; }
        @Override public boolean nearChunk(int chunkX, int chunkY) { return false; }
    };
    /** The edge chunks are the first column of cell (0,0). */
    static final EdgeChunks EDGE = new EdgeChunks() {
        @Override public int count() { return 32; }
        @Override public long chunk(int i) { return ((long) 0 << 32) | (i & 0xFFFFFFFFL); }
    };

    static final class World {
        final Store store = new Store();
        final CellBook book = new CellBook();
        final PopulationSettings s;
        final Respawner respawner = new Respawner();

        World(PopulationSettings s) {
            this.s = s;
            book.init(MAP, s, new Random(1)::nextInt, 0.0);
            new Seeder().seedOutdoor(MAP, (x, y) -> true, s, 0, new Random(2), store);
        }

        int count(int cellX) { return store.countInCell(cellX, 0); }

        double last = 0;

        int update(double hours) {
            int n = respawner.update(hours, last, book, (cx, cy) -> store.countInCell(cx, cy), MAP, s, new Random(3), EDGE,
                (x, y) -> true, store, NOBODY);
            last = hours;
            return n;
        }
    }

    /** Zombies walking out of a never-loaded cell (the simulation) must not be refilled: growth follows the curve. */
    public static void testZombiesLeavingAnUnloadedCellAreNotRefilled() {
        World w = new World(PopulationSettings.vanillaDefaults().toBuilder().edgeArrivalMonths(100000f).build());
        w.update(30 * 24.0); // at the peak
        int atPeak = w.count(0);
        java.util.List<truehordes.store.ZombieRecord> inCell = new java.util.ArrayList<>();
        for (truehordes.store.ZombieRecord r : w.store.all()) if (Math.floorDiv(r.chunkX(), 32) == 0) inCell.add(r);
        for (int i = 0; i < inCell.size() / 2; i++) w.store.moved(inCell.get(i), 5000 + i % 100, 5000); // walked away
        int left = w.count(0);
        Check.isTrue(left < atPeak, "half walked out");
        for (int h = 1; h <= 48; h++) w.update(30 * 24.0 + h);
        Check.equal(left, w.count(0), "nobody refills a cell the zombies walked out of");
    }

    private static int curveAt(World w, int cellX, double day) { return Formula.curve(w.book.baseTarget(cellX, 0), day, w.s); }

    public static void testGrowthTopsUpNeverLoadedCells() {
        World w = new World(PopulationSettings.vanillaDefaults().toBuilder().edgeArrivalMonths(100000f).build());
        w.book.seen(40, 5, 0.0); // a chunk of cell (1,0): that cell has been loaded
        int loadedBefore = w.count(1);
        w.update(30 * 24.0);     // after the peak day
        Check.equal(curveAt(w, 0, 30), w.count(0), "never-loaded cell grows to the curve");
        Check.equal(loadedBefore, w.count(1) - w.respawner.lastRespawned(1, 0), "a loaded cell does not grow (only respawn)");
    }

    public static void testPeakMultiplierDrivesGrowth() {
        World normal = new World(PopulationSettings.vanillaDefaults());
        World high = new World(PopulationSettings.vanillaDefaults().toBuilder().peakMultiplier(3f).build());
        normal.update(30 * 24.0);
        high.update(30 * 24.0);
        Check.equal(Formula.curve(normal.book.baseTarget(0, 0), 30, normal.s), normal.count(0), "peak 1.5");
        Check.isTrue(high.count(0) > normal.count(0) * 1.9, "peak 3 gives about twice as many: " + high.count(0) + " vs " + normal.count(0));
    }

    public static void testRespawnRefillsVisitedCellAfterItsTimer() {
        World w = new World(PopulationSettings.vanillaDefaults());
        w.book.seen(40, 5, 0.0);
        removeFromCell(w, 1, 300);              // a deficit of 300 in the visited cell
        int before = w.count(1);
        w.update(10.0);                          // RespawnHours 72 not passed
        Check.equal(before, w.count(1), "nothing before the respawn timer");
        w.update(80.0);
        Check.isTrue(w.count(1) - before >= 10, "a group of at least 10 arrived: " + (w.count(1) - before));
    }

    public static void testRespawnOffAndSmallDeficit() {
        World off = new World(PopulationSettings.vanillaDefaults().toBuilder().respawnHours(0f).build());
        off.book.seen(40, 5, 0.0);
        removeFromCell(off, 1, 300);
        int before = off.count(1);
        off.update(500.0);
        Check.equal(before, off.count(1), "respawn off");
        World small = new World(PopulationSettings.vanillaDefaults());
        small.book.seen(40, 5, 0.0);
        removeFromCell(small, 1, 9);
        int b2 = small.count(1);
        small.update(500.0);
        Check.equal(b2, small.count(1), "deficit 9: nothing");
    }

    public static void testUnseenTimeRespected() {
        World w = new World(PopulationSettings.vanillaDefaults());
        for (int cy = 0; cy < 32; cy++) for (int cx = 32; cx < 64; cx++) w.book.seen(cx, cy, 70.0); // all of cell 1 seen recently
        removeFromCell(w, 1, 300);
        int before = w.count(1);
        w.update(80.0);                          // unseen for 10 h < RespawnUnseenHours 16
        Check.equal(before, w.count(1), "no chunk unseen long enough");
        w.update(160.0);
        Check.isTrue(w.count(1) > before, "after the unseen time");
    }

    public static void testEdgeShareGrowsWithMonths() {
        PopulationSettings s = PopulationSettings.vanillaDefaults().toBuilder().edgeArrivalMonths(12f).build();
        World early = new World(s);
        early.book.seen(40, 5, 0.0);
        removeFromCell(early, 1, 300);
        early.update(80.0);
        Check.equal(0, early.respawner.lastEdgeArrivals(), "at the start, newcomers come in unseen chunks");
        World late = new World(s);
        late.book.seen(40, 5, 0.0);
        removeFromCell(late, 1, 300);
        int edgeBefore = 0;
        for (int i = 0; i < 32; i++) edgeBefore += late.store.inChunk(0, i).size();
        late.update(13 * 30 * 24.0);
        int edgeAfter = 0;
        for (int i = 0; i < 32; i++) edgeAfter += late.store.inChunk(0, i).size();
        Check.isTrue(late.respawner.lastEdgeArrivals() >= 10, "after 13 months newcomers arrive at the edge");
        Check.isTrue(edgeAfter - edgeBefore >= late.respawner.lastEdgeArrivals(), "placed in edge chunks");
    }

    public static void testSaveLoadRoundTrip() {
        World w = new World(PopulationSettings.vanillaDefaults());
        w.book.seen(40, 5, 12.5);
        w.update(80.0);
        CellBook copy = CellBook.load(w.book.save());
        Check.equal(w.book.baseTarget(0, 0), copy.baseTarget(0, 0), "base target");
        Check.equal(w.book.baseTarget(1, 0), copy.baseTarget(1, 0), "base target cell 1");
        Check.isTrue(copy.everLoaded(1, 0) && !copy.everLoaded(0, 0), "loaded flags");
        Check.near(12.5, copy.lastSeen(40, 5), 0, "last seen");
        Check.near(w.book.startHours(), copy.startHours(), 0, "start time");
        Check.near(w.book.lastRespawn(1, 0), copy.lastRespawn(1, 0), 0, "respawn timer");
    }

    private static void removeFromCell(World w, int cellX, int n) {
        int removed = 0;
        for (var r : List.copyOf(w.store.all())) {
            if (removed == n) break;
            if (Math.floorDiv(r.chunkX(), 32) == cellX) {
                w.store.remove(r.id);
                removed++;
            }
        }
    }
}
