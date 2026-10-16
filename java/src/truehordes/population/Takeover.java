package truehordes.population;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/**
 * Hand-over between the game and the mod, with counts. Out: a real zombie leaving becomes a held record. In: a loaded
 * chunk's records come back real, except a record whose persistent ID is already alive (the game's own chunk save
 * brought it back: the real one wins) and records on a tile without a floor (kept held). Main thread only.
 */
public final class Takeover {
    /** Whether the game has a tile with a floor at (x, y, z) loaded. */
    @FunctionalInterface
    public interface FloorCheck {
        boolean floor(int x, int y, int z);
    }

    private final Store store;
    private int out, in, duplicates, refused, keptNoFloor, retried;
    private java.util.function.Predicate<ZombieRecord> rejoin = r -> false;

    /** What to try with a zombie taken back from a horde (the simulation's hand-over: rejoin the horde if near). */
    public void setRejoin(java.util.function.Predicate<ZombieRecord> p) { rejoin = p == null ? r -> false : p; }

    public Takeover(Store store) { this.store = store; }

    public void taken(ZombieSnapshot z) { taken(z, 0); }

    /** A real zombie leaving; {@code hordeId} is the horde it was laid out from (0 none). */
    public ZombieRecord taken(ZombieSnapshot z, long hordeId) {
        ZombieRecord r = store.add(z.x(), z.y(), z.z(), z.dir(), z.outfitId(), z.stateFlags(), z.persistentId(),
            z.targetX(), z.targetY(), z.modData(), false);
        r.hordeId = hordeId;
        out++;
        if (hordeId != 0) rejoin.test(r);
        return r;
    }

    /** The chunk's records to make real; they leave the store. */
    public List<ZombieRecord> arrivals(int chunkX, int chunkY, IntPredicate liveIds, FloorCheck floors) {
        List<ZombieRecord> spawn = new ArrayList<>();
        for (ZombieRecord r : store.inChunk(chunkX, chunkY)) {
            if (r.persistentId != 0 && liveIds.test(r.persistentId)) {
                store.remove(r.id);
                duplicates++;
            } else if (!floors.floor((int) Math.floor(r.x()), (int) Math.floor(r.y()), (int) Math.floor(r.z))
                    && !toNearestFloor(r, chunkX, chunkY, floors)) {
                keptNoFloor++;
            } else {
                store.remove(r.id);
                spawn.add(r);
                in++;
            }
        }
        return spawn;
    }

    /**
     * Moves a record standing where there is no floor to the nearest floor tile of its chunk (in-game test 2026-10-16:
     * walkers that came in on a wall or water tile stayed held at the loaded area's border). False if the chunk has none.
     */
    private boolean toNearestFloor(ZombieRecord r, int chunkX, int chunkY, FloorCheck floors) {
        int z = (int) Math.floor(r.z), bx = -1, by = -1;
        float best = Float.MAX_VALUE;
        for (int x = chunkX * 8; x < chunkX * 8 + 8; x++) {
            for (int y = chunkY * 8; y < chunkY * 8 + 8; y++) {
                float dx = x + 0.5f - r.x(), dy = y + 0.5f - r.y(), d = dx * dx + dy * dy;
                if (d < best && floors.floor(x, y, z)) {
                    best = d;
                    bx = x;
                    by = y;
                }
            }
        }
        if (bx < 0) return false;
        store.moved(r, bx + 0.5f, by + 0.5f);
        return true;
    }

    /** The game's spawn-point rule deleted it, as vanilla does. */
    public void refused(ZombieRecord r) {
        refused++;
        in--;
    }

    /** The spawn failed for another reason: held again with its own record. */
    public void failed(ZombieRecord r) {
        store.restore(r);
        retried++;
        in--;
    }

    public int out() { return out; }

    public int in() { return in; }

    public int duplicates() { return duplicates; }

    public int refused() { return refused; }

    public int keptNoFloor() { return keptNoFloor; }

    public int retried() { return retried; }

    public String counts() {
        return "out " + out + ", in " + in + ", duplicates dropped " + duplicates + ", refused near spawn " + refused
            + ", kept (no floor) " + keptNoFloor + ", retried " + retried;
    }

    public void reset() {
        out = in = duplicates = refused = keptNoFloor = retried = 0;
    }
}
