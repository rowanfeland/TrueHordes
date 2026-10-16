package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import truehordes.population.Takeover;
import truehordes.population.ZombieSnapshot;
import truehordes.save.LoadResult;
import truehordes.save.SaveFile;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

public final class TakeoverTest {
    private static ZombieSnapshot zombie(float x, float y, int persistentId) {
        return new ZombieSnapshot(x, y, 0f, (byte) 5, 321, 0x9, persistentId, 400, 500, new byte[] {1, 2});
    }

    private static final Takeover.FloorCheck FLOOR = (x, y, z) -> true;

    public static void testOutThenInKeepsEverything() {
        Store store = new Store();
        Takeover t = new Takeover(store);
        t.taken(zombie(12.5f, 20.5f, 77));
        Check.equal(1, store.size(), "held");
        List<ZombieRecord> in = t.arrivals(1, 2, id -> false, FLOOR);
        Check.equal(1, in.size(), "arrives");
        ZombieRecord r = in.get(0);
        Check.near(12.5f, r.x(), 0, "x");
        Check.equal((byte) 5, r.dir, "direction");
        Check.equal(321, r.outfitId, "outfit");
        Check.equal(0x9, r.stateFlags, "state");
        Check.equal(77, r.persistentId, "ID");
        Check.equal(400, r.targetX, "target");
        Check.isTrue(Arrays.equals(new byte[] {1, 2}, r.modData), "mod data");
        Check.equal(0, store.size(), "no longer held once real");
        Check.isTrue(t.counts().contains("out 1") && t.counts().contains("in 1"), t.counts());
    }

    public static void testLiveIdIsDroppedAsDuplicate() {
        Store store = new Store();
        Takeover t = new Takeover(store);
        t.taken(zombie(1, 1, 55));
        List<ZombieRecord> in = t.arrivals(0, 0, id -> id == 55, FLOOR);
        Check.equal(0, in.size(), "not spawned twice");
        Check.equal(0, store.size(), "the held copy is dropped");
        Check.equal(1, t.duplicates(), "counted");
    }

    public static void testBlockedTileKeepsRecordHeld() {
        Store store = new Store();
        Takeover t = new Takeover(store);
        t.taken(zombie(3, 3, 0));
        Check.equal(0, t.arrivals(0, 0, id -> false, (x, y, z) -> false).size(), "no floor: not spawned");
        Check.equal(1, store.size(), "still held");
        Check.equal(1, t.keptNoFloor(), "counted");
        Check.equal(1, t.arrivals(0, 0, id -> false, FLOOR).size(), "arrives once the tile is clear");
    }

    /** In-game test 2026-10-16 (zombies piled at the loaded area's border): it steps to the nearest floor in its chunk. */
    public static void testNoFloorStepsToNearestFloorInChunk() {
        Store store = new Store();
        Takeover t = new Takeover(store);
        t.taken(zombie(3.5f, 3.5f, 0));
        List<ZombieRecord> in = t.arrivals(0, 0, id -> false, (x, y, z) -> x == 5 && y == 6);
        Check.equal(1, in.size(), "spawned on the floor nearby");
        Check.near(5.5f, in.get(0).x(), 0.01f, "x");
        Check.near(6.5f, in.get(0).y(), 0.01f, "y");
    }

    public static void testRefusedAndFailed() {
        Store store = new Store();
        Takeover t = new Takeover(store);
        t.taken(zombie(3, 3, 0));
        t.taken(zombie(4, 4, 0));
        List<ZombieRecord> in = t.arrivals(0, 0, id -> false, FLOOR);
        t.refused(in.get(0));
        t.failed(in.get(1));
        Check.equal(1, t.refused(), "refused by the spawn-point rule");
        Check.equal(1, store.size(), "the failed one is held again");
        Check.equal(in.get(1).id, store.all().iterator().next().id, "with its own record");
    }

    public static void testTakenZombieSurvivesSaveBeforeChunkSave() throws Exception {
        // The mod takes a zombie and saves; the game's chunk save still has the real zombie (crash between the two).
        Store store = new Store();
        new Takeover(store).taken(zombie(9, 9, 4242));
        Path f = Files.createTempDirectory("th-takeover").resolve("state.bin");
        SaveFile.write(f, store, 1.0);
        LoadResult r = SaveFile.read(f);
        Store reloaded = new Store();
        for (ZombieRecord rec : r.records) reloaded.restore(rec);
        Takeover t = new Takeover(reloaded);
        List<ZombieRecord> in = t.arrivals(1, 1, id -> id == 4242, FLOOR); // the chunk save brought the real one back
        Check.equal(0, in.size(), "exactly one zombie: the real one");
        Check.equal(0, reloaded.size(), "held copy dropped");
    }
}
