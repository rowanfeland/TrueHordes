package truehordes.sim;

import java.util.ArrayList;
import java.util.List;
import truehordes.population.PlayerNearby;
import truehordes.population.Takeover;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/**
 * Hordes meeting the loaded area (spec 6, until sub-project 6 walks them across): a horde whose circle touches a
 * loaded chunk is laid out as a column behind its centre, along its heading, its members becoming ordinary held
 * records the game's spawn path makes real. A real zombie that leaves again rejoins its horde if that horde is still
 * close by off screen. Used under the core's lock.
 */
public final class HandOver {
    /** Spacing between zombies in the column, in tiles. */
    static final float SPACING = 1.2f;
    /** How far from its spot a member may be placed to find a floor, in tiles. */
    static final int FLOOR_SEARCH = 4;
    /** A real zombie rejoins its horde if it is within this much more than twice the horde's spread (tiles). */
    static final float REJOIN_MARGIN = 32f;

    private final Simulation sim;
    private final Store store;
    /** Hordes wholly made real, by id, with their heading: waiting for members to unload and re-form them. */
    private final java.util.Map<Long, Float> realHordes = new java.util.HashMap<>();
    /** How long a horde whose members are coming back off screen is not broken up for being small (in-game hours). */
    static final double GATHER_HOURS = 1;

    /** A horde that has been real broke up off screen: members still real re-form it when they unload. */
    public void rememberReal(Horde h) { realHordes.put(h.id, h.heading); }

    /** A horde formed among real zombies (sub-project 6): its members re-form it off screen as they leave. */
    public void rememberReal(long id, float heading) { realHordes.put(id, heading); }
    private int laidOut, rejoined;

    public HandOver(Simulation sim, Store store) {
        this.sim = sim;
        this.store = store;
    }

    /**
     * Lays out {@code h} as a column along its heading (front row first, the column centred on the horde): every
     * member whose place falls in a loaded chunk with a floor nearby leaves the horde as a held record there (the
     * arrivals then make it real); the others stay in the horde off screen, which carries on (or breaks up into
     * wanderers at their places if it is left below the minimum size). Returns the members laid out.
     */
    public List<ZombieRecord> layOut(Horde h, Takeover.FloorCheck floors) {
        PlayerNearby loaded = sim.players();
        if (h.dormant) return layOutDormant(h, floors, loaded);
        List<ZombieRecord> members = new ArrayList<>(h.members);
        int n = members.size();
        int width = Math.max(1, (int) Math.ceil(Math.sqrt(n) / 2));
        int rows = (n + width - 1) / width;
        float fx = (float) Math.cos(h.heading), fy = (float) Math.sin(h.heading);
        float sx = -fy, sy = fx; // to the side
        float front = (rows - 1) * SPACING / 2f;
        List<ZombieRecord> out = new ArrayList<>();
        java.util.Set<ZombieRecord> gone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        float[][] places = new float[n][];
        float oldSpread = h.spread();
        sim.internal(() -> {
            for (int i = 0; i < n; i++) {
                ZombieRecord r = members.get(i);
                int row = i / width, col = i % width;
                float side = (col - (width - 1) / 2f) * SPACING, ahead = front - row * SPACING;
                float x = h.x + fx * ahead + sx * side, y = h.y + fy * ahead + sy * side;
                places[i] = new float[] {x, y};
                if (!loaded.realAt(x, y)) continue;
                float[] spot = floorNear(x, y, (int) Math.floor(r.z), floors);
                if (spot == null) continue;
                gone.add(r);
                leave(r, h.heading, spot[0], spot[1]);
                out.add(r);
            }
            if (!gone.isEmpty()) {
                sim.hordes().removeMembers(h, gone);
                h.wasReal = true; // its members re-form it when they unload (user, 2026-10-16)
                h.keepUntilHours = sim.currentHours() + GATHER_HOURS;
            }
            if (h.size() == 0) {
                // Wholly real: its members re-form it under the same id as they unload (rejoin).
                sim.grid().removeHorde(h, h.x, h.y, oldSpread);
                sim.hordes().remove(h.id);
                realHordes.put(h.id, h.heading);
            } else if (!out.isEmpty()) {
                sim.grid().moveHorde(h, h.x, h.y, oldSpread);
            }
        });
        laidOut += out.size();
        return out;
    }

    /** A building horde's zombies appear where they stand inside, still idle; the rest keep waiting. */
    private List<ZombieRecord> layOutDormant(Horde h, Takeover.FloorCheck floors, PlayerNearby loaded) {
        List<ZombieRecord> out = new ArrayList<>();
        java.util.Set<ZombieRecord> gone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        float oldSpread = h.spread();
        sim.internal(() -> {
            for (ZombieRecord r : new ArrayList<>(h.members)) {
                if (!loaded.realAt(r.x(), r.y())) continue;
                if (!floors.floor((int) Math.floor(r.x()), (int) Math.floor(r.y()), (int) Math.floor(r.z))) continue;
                gone.add(r);
                r.followId = 0;
                store.restore(r); // same spot, still idle
                out.add(r);
            }
            if (!gone.isEmpty()) sim.hordes().removeMembers(h, gone);
            if (h.size() == 0) {
                sim.grid().removeHorde(h, h.x, h.y, oldSpread);
                sim.hordes().remove(h.id);
            } else if (!out.isEmpty()) {
                sim.grid().moveHorde(h, h.x, h.y, oldSpread);
            }
        });
        laidOut += out.size();
        return out;
    }

    private void leave(ZombieRecord r, float heading, float x, float y) {
        r.followId = 0;
        r.pauseMinutes = 0;
        r.lingerUntilHours = 0;
        r.idle = false;
        r.heading = heading;
        store.restore(r);
        store.moved(r, x, y);
    }

    private static float[] floorNear(float x, float y, int z, Takeover.FloorCheck floors) {
        int tx = (int) Math.floor(x), ty = (int) Math.floor(y);
        if (floors.floor(tx, ty, z)) return new float[] {x, y};
        for (int r = 1; r <= FLOOR_SEARCH; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    if (floors.floor(tx + dx, ty + dy, z)) return new float[] {tx + dx + 0.5f, ty + dy + 0.5f};
                }
            }
        }
        return null;
    }

    /** Lays out every horde whose circle touches chunk (cx, cy); returns all members laid out. */
    public List<ZombieRecord> layOutTouching(int cx, int cy, Takeover.FloorCheck floors) {
        List<Horde> touching = new ArrayList<>();
        float x0 = cx * 8f, y0 = cy * 8f;
        for (Horde h : sim.hordes().all()) {
            float nx = Math.max(x0, Math.min(h.x, x0 + 8)), ny = Math.max(y0, Math.min(h.y, y0 + 8));
            float dx = h.x - nx, dy = h.y - ny, r = h.spread();
            if (dx * dx + dy * dy <= r * r) touching.add(h);
        }
        List<ZombieRecord> out = new ArrayList<>();
        for (Horde h : touching) out.addAll(layOut(h, floors));
        return out;
    }

    /** Lays out every horde touching the loaded area, each once (not once per chunk it touches). */
    public List<ZombieRecord> layOutInLoaded(Takeover.FloorCheck floors) {
        List<Horde> touching = new ArrayList<>();
        for (Horde h : sim.hordes().all()) if (touchesLoaded(h, sim.players())) touching.add(h);
        List<ZombieRecord> out = new ArrayList<>();
        for (Horde h : touching) out.addAll(layOut(h, floors));
        return out;
    }

    /** Whether any real chunk lies inside the horde's circle. */
    public static boolean touchesLoaded(Horde h, PlayerNearby players) {
        float r = h.spread();
        int x0 = Math.floorDiv((int) Math.floor(h.x - r), 8), x1 = Math.floorDiv((int) Math.floor(h.x + r), 8);
        int y0 = Math.floorDiv((int) Math.floor(h.y - r), 8), y1 = Math.floorDiv((int) Math.floor(h.y + r), 8);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                if (!players.realChunk(cx, cy)) continue;
                float nx = Math.max(cx * 8f, Math.min(h.x, cx * 8f + 8)), ny = Math.max(cy * 8f, Math.min(h.y, cy * 8f + 8));
                float dx = h.x - nx, dy = h.y - ny;
                if (dx * dx + dy * dy <= r * r) return true;
            }
        }
        return false;
    }

    /**
     * A real zombie just became a held record again: if it came from a horde that is still held close by, it rejoins
     * it (true); otherwise it stays a wanderer.
     */
    public boolean rejoin(ZombieRecord taken) {
        if (taken.hordeId == 0 || store.get(taken.id) != taken) return false;
        Horde h = sim.hordes().get(taken.hordeId);
        if (h == null) {
            Float heading = realHordes.remove(taken.hordeId);
            if (heading == null) return false;
            // The first member back re-forms its wholly real horde where it stands; the rest rejoin it.
            sim.internal(() -> {
                store.remove(taken.id);
                taken.followId = 0;
                taken.idle = false;
                Horde back = sim.reformHorde(taken.hordeId, taken.x(), taken.y(), heading, taken);
                back.wasReal = true;
                back.keepUntilHours = sim.currentHours() + GATHER_HOURS;
            });
            rejoined++;
            return true;
        }
        h.keepUntilHours = Math.max(h.keepUntilHours, sim.currentHours() + GATHER_HOURS);
        float reach = 2 * h.spread() + REJOIN_MARGIN;
        if (Hearing.dist(taken.x(), taken.y(), h.x, h.y) > reach) return false;
        sim.internal(() -> {
            float old = h.spread();
            store.remove(taken.id);
            taken.followId = 0;
            taken.idle = false;
            sim.hordes().addMember(h, taken);
            sim.grid().moveHorde(h, h.x, h.y, old);
        });
        rejoined++;
        return true;
    }

    public int laidOut() { return laidOut; }

    public int rejoined() { return rejoined; }
}
