package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import truehordes.population.Formula;
import truehordes.population.MapSource;
import truehordes.population.PopulationSettings;
import truehordes.population.Seeder;
import truehordes.population.TilePicker;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

public final class SeederTest {
    /** Two cells (64x32 chunks) of one intensity; chunk (5,5) has intensity 0 (Voronoi), chunk (10,10) is all blocked. */
    static final class FakeMap implements MapSource {
        final int intensity;
        final List<RoomInfo> rooms = new ArrayList<>();

        FakeMap(int intensity) { this.intensity = intensity; }

        @Override public int minChunkX() { return 0; }
        @Override public int minChunkY() { return 0; }
        @Override public int maxChunkX() { return 63; }
        @Override public int maxChunkY() { return 31; }
        @Override public int intensity(int cx, int cy) { return cx == 5 && cy == 5 ? 0 : intensity; }
        @Override public List<RoomInfo> rooms() { return rooms; }
    }

    static final TilePicker TILES = (x, y) -> !(x / 8 == 10 && y / 8 == 10);

    private static int expected(FakeMap map, PopulationSettings s, long seed) {
        // Same random sequence as the seeder: per cell, 1024 chunk targets (rand100 each), then distribute.
        Random rnd = new Random(seed);
        int total = 0;
        for (int cell = 0; cell < 2; cell++) {
            int t = 0;
            for (int i = 0; i < 1024; i++) {
                int cx = cell * 32 + i % 32, cy = i / 32;
                t += Formula.chunkTarget(map.intensity(cx, cy), s, () -> rnd.nextInt(100));
            }
            total += Formula.curve(t, 0, s);
        }
        return total;
    }

    public static void testSeededTotalFollowsTheFormula() {
        FakeMap map = new FakeMap(100); // value 65 per chunk at multiplier 0.65
        PopulationSettings s = PopulationSettings.vanillaDefaults();
        Store store = new Store();
        Seeder seeder = new Seeder();
        int added = seeder.seedOutdoor(map, TILES, s, 0, new Random(5), store);
        int per = Formula.chunkTarget(100, s, () -> 0);
        Check.equal(added, store.size(), "every added record is in the store");
        Check.equal(expected(map, s, 5), added + seeder.lastSkipped(), "seeded + skipped = vanilla's cell totals");
        Check.equal(0, store.inChunk(5, 5).size(), "no zombies where the Voronoi mask is 0");
        Check.equal(0, store.inChunk(10, 10).size(), "none on blocked tiles");
        Check.isTrue(seeder.lastSkipped() >= per - 1 && seeder.lastSkipped() <= per + 5,
            "about the blocked chunk's share skipped and counted: " + seeder.lastSkipped());
        Check.isTrue(Math.abs(store.inChunk(3, 3).size() - per) <= 3, "about " + per + " in an ordinary chunk");
        for (ZombieRecord r : store.inChunk(3, 3)) {
            Check.isTrue(!r.hasTarget() && r.outfitId == 0 && r.persistentId == 0 && !r.building, "standing, undressed, no ID");
        }
    }

    public static void testPopulationMultiplierChangesSeededTotal() {
        FakeMap map = new FakeMap(100);
        PopulationSettings normal = PopulationSettings.vanillaDefaults();
        PopulationSettings doubled = normal.toBuilder().multiplier(1.3f).build();
        PopulationSettings start2 = normal.toBuilder().startMultiplier(2f).build();
        Seeder sa = new Seeder();
        int a = sa.seedOutdoor(map, TILES, normal, 0, new Random(1), new Store());
        int b = new Seeder().seedOutdoor(map, TILES, doubled, 0, new Random(1), new Store());
        int c = new Seeder().seedOutdoor(map, TILES, start2, 0, new Random(1), new Store());
        Check.near(2.0, (double) b / a, 0.01, "doubling Population doubles the seeded zombies");
        Check.near(2.0, (double) c / a, 0.01, "start multiplier 2 doubles them too");
        Check.equal(expected(map, normal, 1), a + sa.lastSkipped(), "matches the vanilla formula");
    }

    public static void testSeedingIsDeterministic() {
        FakeMap map = new FakeMap(3); // value 1.95: the 1.x coin flip per chunk
        Store x = new Store(), y = new Store();
        new Seeder().seedOutdoor(map, TILES, PopulationSettings.vanillaDefaults(), 0, new Random(9), x);
        new Seeder().seedOutdoor(map, TILES, PopulationSettings.vanillaDefaults(), 0, new Random(9), y);
        Check.equal(x.size(), y.size(), "same seed, same population");
        Check.isTrue(x.size() > 300 && x.size() < 1800, "about half the chunks get one: " + x.size());
    }

    public static void testRoomsSeededInsideTheirRects() {
        FakeMap map = new FakeMap(0);
        // A room in a 130-room building on level 1 always gets next(20, 30) (RoomCountTest), and an empty-outside room.
        map.rooms.add(new MapSource.RoomInfo(1, 90, 130, 0, false, new int[] {100, 200, 10, 9}));
        map.rooms.add(new MapSource.RoomInfo(0, 90, 10, 255, true, new int[] {300, 300, 10, 10}));
        Store store = new Store();
        int added = new Seeder().seedRooms(map, PopulationSettings.vanillaDefaults(), new Random(3), store);
        Check.equal(added, store.size(), "all in the store");
        Check.isTrue(added >= 20 && added < 30, "the big building's room count: " + added);
        for (ZombieRecord r : store.all()) {
            Check.isTrue(r.x() >= 100 && r.x() < 110 && r.y() >= 200 && r.y() < 209, "inside the rect: " + r.x() + "," + r.y());
            Check.near(1f, r.z, 0, "on the room's level");
            Check.isTrue(r.building, "marked as a building zombie");
        }
    }
}
