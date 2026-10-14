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
    private int laidOut, rejoined;

    public HandOver(Simulation sim, Store store) {
        this.sim = sim;
        this.store = store;
    }

    /** Lays out {@code h}: members back in the Store (positions in a column), the horde removed. Returns them. */
    public List<ZombieRecord> layOut(Horde h, Takeover.FloorCheck floors) {
        List<ZombieRecord> out = new ArrayList<>(h.members);
        int n = out.size();
        int width = Math.max(1, (int) Math.ceil(Math.sqrt(n) / 2));
        float fx = (float) Math.cos(h.heading), fy = (float) Math.sin(h.heading);
        float sx = -fy, sy = fx; // to the side
        sim.internal(() -> {
            sim.grid().removeHorde(h);
            sim.hordes().remove(h.id);
            for (int i = 0; i < n; i++) {
                ZombieRecord r = out.get(i);
                int row = i / width, col = i % width;
                float side = (col - (width - 1) / 2f) * SPACING, back = row * SPACING;
                float x = h.x - fx * back + sx * side, y = h.y - fy * back + sy * side;
                float[] spot = floorNear(x, y, (int) Math.floor(r.z), floors);
                if (spot == null) spot = new float[] {h.x, h.y};
                r.followId = 0;
                r.pauseMinutes = 0;
                r.lingerUntilHours = 0;
                r.heading = h.heading;
                store.restore(r);
                store.moved(r, spot[0], spot[1]);
            }
        });
        laidOut += n;
        return out;
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

    /** Whether any loaded chunk lies inside the horde's circle. */
    public static boolean touchesLoaded(Horde h, PlayerNearby players) {
        float r = h.spread();
        int x0 = Math.floorDiv((int) Math.floor(h.x - r), 8), x1 = Math.floorDiv((int) Math.floor(h.x + r), 8);
        int y0 = Math.floorDiv((int) Math.floor(h.y - r), 8), y1 = Math.floorDiv((int) Math.floor(h.y + r), 8);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                if (!players.nearChunk(cx, cy)) continue;
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
        if (h == null) return false;
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
