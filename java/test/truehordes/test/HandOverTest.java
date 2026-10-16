package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.population.Takeover;
import truehordes.population.ZombieSnapshot;
import truehordes.sim.Horde;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

public final class HandOverTest {
    private HandOverTest() {}

    static final Takeover.FloorCheck ALL_FLOOR = (x, y, z) -> true;

    static Horde horde(Simulation sim, float x, float y, float heading, int n) {
        List<ZombieRecord> m = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ZombieRecord r = new ZombieRecord(7_000_000 + sim.hordes().memberCount() + i, x, y, 0, (byte) 0, 1000 + i,
                4, 50_000 + i, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, new byte[] {1, 2, (byte) i}, false);
            r.strRolled = true;
            m.add(r);
        }
        return sim.restoreHorde(sim.hordes().nextId(), x, y, heading, 0, m);
    }

    static Simulation sim(Store s) {
        return new Simulation(s, SimulationTest.openMap(200, -1), SimSettings.defaults(), 1, 0);
    }

    /** A simulation where every chunk counts as loaded (a whole horde can be laid out). */
    static Simulation allLoaded(Store s) {
        Simulation sim = sim(s);
        sim.setPlayers(new truehordes.population.PlayerNearby() {
            @Override public boolean nearCell(int cellX, int cellY) { return true; }
            @Override public boolean nearChunk(int chunkX, int chunkY) { return true; }
        });
        return sim;
    }

    public static void testLayoutIsColumnAlongHeading() {
        Store s = new Store();
        Simulation sim = allLoaded(s);
        Horde h = horde(sim, 500, 500, 0, 100);
        List<ZombieRecord> out = sim.handOver().layOut(h, ALL_FLOOR);
        Check.equal(100, out.size(), "all inside the loaded area are laid out");
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (ZombieRecord r : out) {
            Check.isTrue(Math.abs(r.x() - 500) <= 13, "column centred on the horde (100 zombies: 20 rows of 5)");
            minX = Math.min(minX, r.x()); maxX = Math.max(maxX, r.x());
            minY = Math.min(minY, r.y()); maxY = Math.max(maxY, r.y());
        }
        Check.isTrue(maxX - minX > 2 * (maxY - minY), "a column along the heading");
        Check.equal(0, sim.hordes().size(), "the horde is gone");
        Check.equal(100, s.size(), "its members are held records");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    public static void testLayoutKeepsEveryRecordWhole() {
        Store s = new Store();
        Simulation sim = sim(s);
        Horde h = horde(sim, 500, 500, 1, 100);
        sim.setPlayers(allLoaded(new Store()).players());
        List<ZombieRecord> before = new ArrayList<>(h.members);
        List<ZombieRecord> out = sim.handOver().layOut(h, ALL_FLOOR);
        Check.equal(100, out.size(), "everyone");
        for (int i = 0; i < before.size(); i++) {
            ZombieRecord r = before.get(i);
            Check.isTrue(s.get(r.id) == r, "same record, same id");
            Check.equal(1000 + i, r.outfitId, "outfit kept");
            Check.equal(50_000 + i, r.persistentId, "persistent id kept");
            Check.equal((byte) i, r.modData[2], "mod data kept");
            Check.equal(h.id, r.hordeId, "remembers its horde");
        }
    }

    public static void testHordeEnteringLoadedChunkIsLaidOut() {
        Store s = new Store();
        Simulation sim = sim(s);
        Horde h = horde(sim, 100 * 8 - 30, 100 * 8 + 4, 0, 400); // spread 12: edge 18 tiles short of chunk 100
        sim.setPlayers(SimulationTest.loadedAround(103, 100, 3));
        boolean due = false;
        for (double t = 0; t < 3 && !due; t += 1.0 / 60) {
            sim.step(t, Long.MAX_VALUE);
            due = sim.takeLayoutDue();
        }
        Check.isTrue(due, "the horde reaching the loaded area asks for the hand-over");
        Check.isTrue(sim.hordes().get(h.id) != null, "it waits off screen until then");
        List<ZombieRecord> out = new ArrayList<>();
        for (int cx = 100; cx <= 106; cx++) {
            for (int cy = 97; cy <= 103; cy++) out.addAll(sim.handOver().layOutTouching(cx, cy, ALL_FLOOR));
        }
        Check.isTrue(!out.isEmpty(), "its front is laid out when the arrivals of the chunk it touches run");
        for (ZombieRecord r : out) {
            Check.isTrue(sim.players().nearChunk(Math.floorDiv((int) r.x(), 8), Math.floorDiv((int) r.y(), 8)),
                "inside the loaded area");
        }
        Horde rest = sim.hordes().get(h.id);
        Check.equal(400, out.size() + (rest == null ? 0 : rest.size()), "nobody lost");
    }

    /**
     * Final review C1: a horde just touching the loaded area lays out only the members whose place is inside it (where
     * the game has floors); the rest stay a horde off screen, nobody is dumped on the centre tile.
     */
    public static void testOnlyMembersInsideTheLoadedAreaAreLaidOut() {
        Store s = new Store();
        Simulation sim = sim(s);
        Horde h = horde(sim, 790, 804, 0, 400); // spread 12: reaches x 802, loaded area starts at x 800
        truehordes.population.PlayerNearby loaded = SimulationTest.loadedAround(106, 100, 6); // chunks 100..112
        Takeover.FloorCheck floors = (x, y, z) -> Math.floorDiv(x, 8) >= 100; // the game only has floors loaded
        sim.setPlayers(loaded);
        List<ZombieRecord> out = sim.handOver().layOutTouching(100, 100, floors);
        Check.isTrue(!out.isEmpty(), "the front of the horde becomes real");
        for (ZombieRecord r : out) Check.isTrue(r.x() >= 800, "only members inside the loaded area are laid out");
        Check.isTrue(sim.hordes().get(h.id) != null, "the rest stays a horde off screen");
        Check.equal(400, out.size() + h.size(), "everyone is either laid out or still in the horde");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    public static void testRealMemberRejoinsNearbyHorde() {
        Store s = new Store();
        Simulation sim = sim(s);
        Horde h = horde(sim, 500, 500, 0, 30);
        Takeover t = new Takeover(s);
        t.setRejoin(sim.handOver()::rejoin);
        ZombieRecord r = t.taken(new ZombieSnapshot(520, 500, 0, (byte) 0, 7, 4, 0, ZombieRecord.NO_TARGET,
            ZombieRecord.NO_TARGET, null), h.id);
        Check.equal(31, h.size(), "rejoined its horde");
        Check.isTrue(s.get(r.id) == null, "not a wanderer");
        Check.equal("", sim.checkConservation("test"), "conservation: one zombie came in");
    }

    public static void testFarHordeMeansWanderer() {
        Store s = new Store();
        Simulation sim = sim(s);
        Horde h = horde(sim, 500, 500, 0, 30);
        Takeover t = new Takeover(s);
        t.setRejoin(sim.handOver()::rejoin);
        ZombieRecord r = t.taken(new ZombieSnapshot(900, 500, 0, (byte) 0, 7, 4, 0, ZombieRecord.NO_TARGET,
            ZombieRecord.NO_TARGET, null), h.id);
        Check.equal(30, h.size(), "too far to rejoin");
        Check.isTrue(s.get(r.id) == r, "a wanderer");
    }

    public static void testKilledMemberNeverRejoins() {
        Store s = new Store();
        Simulation sim = sim(s);
        Horde h = horde(sim, 500, 500, 0, 30);
        Takeover t = new Takeover(s);
        t.setRejoin(sim.handOver()::rejoin);
        sim.setPlayers(allLoaded(new Store()).players());
        sim.handOver().layOut(h, ALL_FLOOR);
        int cx = Math.floorDiv(500, 8), cy = Math.floorDiv(500, 8);
        List<ZombieRecord> spawned = new ArrayList<>();
        for (int x = cx - 4; x <= cx + 4; x++) {
            for (int y = cy - 4; y <= cy + 4; y++) spawned.addAll(t.arrivals(x, y, id -> false, ALL_FLOOR));
        }
        Check.isTrue(!spawned.isEmpty(), "members became real");
        long held = sim.held();
        // A real zombie that dies is never taken back: nothing re-enters, and its old horde does not exist anymore.
        Check.isTrue(sim.hordes().get(h.id) == null, "laid-out horde is gone");
        Check.equal(held, sim.held(), "held unchanged by the death");
        Check.equal("", sim.checkConservation("test"), "conservation counts the spawned zombies as gone");
    }

    /**
     * In-game test 2026-10-16: a horde that went fully real and was then left behind came back as lone zombies. Its
     * members now re-form it (same id) as they unload, and it is not broken up while they gather.
     */
    public static void testLeftBehindRealHordeReforms() {
        Store s = new Store();
        Simulation sim = sim(s);
        Horde h = horde(sim, 500, 500, 0, 30);
        long id = h.id;
        Takeover t = new Takeover(s);
        t.setRejoin(sim.handOver()::rejoin);
        sim.setPlayers(allLoaded(new Store()).players());
        sim.handOver().layOut(h, ALL_FLOOR);
        int cx = Math.floorDiv(500, 8), cy = Math.floorDiv(500, 8);
        List<ZombieRecord> spawned = new ArrayList<>();
        for (int x = cx - 4; x <= cx + 4; x++) {
            for (int y = cy - 4; y <= cy + 4; y++) spawned.addAll(t.arrivals(x, y, i2 -> false, ALL_FLOOR));
        }
        Check.equal(30, spawned.size(), "all real");
        Check.isTrue(sim.hordes().get(id) == null, "no held horde left");
        sim.setPlayers(sim(new Store()).players()); // the player left: nothing loaded
        for (int i = 0; i < spawned.size(); i++) {
            ZombieRecord r = spawned.get(i);
            t.taken(new ZombieSnapshot(r.x(), r.y(), 0, (byte) 0, 7, 4, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET,
                null), id);
            if (i == 0) sim.step(sim.currentHours() + 0.25, Long.MAX_VALUE); // a horde update while it gathers
        }
        Horde back = sim.hordes().get(id);
        Check.isTrue(back != null && back.size() == 30, "the horde re-formed (" + (back == null ? 0 : back.size()) + ")");
        Check.equal(0, s.size(), "no lone zombies left over");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }
}
