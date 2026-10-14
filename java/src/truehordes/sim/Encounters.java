package truehordes.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/**
 * The horde rules decided by the user (spec section 2, 4.2 to 4.6), applied as walkers are updated:
 * wanderers join a horde they see, sometimes fall in behind a zombie they see walking, and a chain of 10 moving
 * together becomes a horde; hordes merge only when they touch; hordes in tight spots leave members behind; a horde
 * below the minimum size breaks up; zombies that gathered at a sound become a horde when it ends. Stragglers and idle
 * zombies take no part. Wanderers live in the Store (the grid follows it through Store.Listener), horde members only
 * in their horde, so held = store + horde members at all times. Used under the core's lock.
 */
public final class Encounters {
    /** Followers further than this from the head of their chain do not count as moving together (tiles). */
    static final float CHAIN_RADIUS = 16f;
    /** Responders within this distance of a sound when it ends count as gathered there (tiles). */
    static final float GATHER_RADIUS = 8f;
    private static final int MAX_CHAIN = 1000;

    private final Store store;
    private final Hordes hordes;
    private final SpatialGrid grid;
    private final SimSettings s;
    private final Random rng;
    private final Map<Long, List<ZombieRecord>> responders = new HashMap<>();
    private final List<ZombieRecord> seenWanderers = new ArrayList<>();
    private final List<Horde> seenHordes = new ArrayList<>();
    private int formed, joined, merged, brokeAway, dissolved;

    public Encounters(Store store, Hordes hordes, SpatialGrid grid, SimSettings s, Random rng) {
        this.store = store;
        this.hordes = hordes;
        this.grid = grid;
        this.s = s;
        this.rng = rng;
    }

    /** Rolls the straggler flag once per zombie (1 in StragglerOneIn). */
    public void rollStraggler(ZombieRecord r) {
        if (r.strRolled) return;
        r.strRolled = true;
        r.straggler = rng.nextInt(s.stragglerOneIn()) == 0;
    }

    /** A wanderer is walking along right now (followable, can be part of a chain). */
    public static boolean moving(ZombieRecord r, double nowHours) {
        return !r.idle && r.pauseMinutes <= 0 && r.lingerUntilHours <= nowHours;
    }

    /** Sight radius for this zombie (clear daylight; the game's Random sight settings picked per zombie). */
    float sightRadius(ZombieRecord r) {
        int sight = Vision.perZombieSight(s.sightSetting(), n -> (int) Math.floorMod(Movement.mix(r.id ^ 0x5157L), (long) n));
        return Vision.radius(sight, 0, 0, 0);
    }

    /** Encounters for one wanderer after it moved; {@code dtHours} is the time since its last update. */
    public void wanderer(ZombieRecord r, double nowHours, double dtHours) {
        rollStraggler(r);
        if (r.straggler || r.idle) return;
        float radius = sightRadius(r);
        seenWanderers.clear();
        seenHordes.clear();
        grid.near(r.x(), r.y(), radius, seenWanderers::add, seenHordes::add);
        for (Horde h : seenHordes) {
            if (Vision.sees(r.x(), r.y(), r.heading, h.x, h.y, h.spread(), radius, rng::nextDouble)) {
                join(h, r);
                return;
            }
        }
        if (r.followId > 0 && store.get(r.followId) == null) r.followId = 0;
        if (r.followId == 0 && moving(r, nowHours) && s.followChancePerHour() > 0) {
            double p = 1 - Math.pow(1 - s.followChancePerHour(), dtHours);
            for (ZombieRecord w : seenWanderers) {
                if (w == r || w.straggler || !moving(w, nowHours) || leads(r, w)) continue;
                if (!Vision.sees(r.x(), r.y(), r.heading, w.x(), w.y(), 0, radius, rng::nextDouble)) continue;
                if (rng.nextDouble() < p) r.followId = w.id;
                break;
            }
        }
        if (r.followId > 0) formChain(r, nowHours);
    }

    /** True if {@code w} already trails {@code r} (directly or down the chain): following it would make a loop. */
    private boolean leads(ZombieRecord r, ZombieRecord w) {
        ZombieRecord c = w;
        for (int i = 0; i < MAX_CHAIN && c != null && c.followId > 0; i++) {
            if (c.followId == r.id) return true;
            c = store.get(c.followId);
        }
        return false;
    }

    /** The zombie at the front of r's follow chain (r itself if it follows nobody). */
    ZombieRecord head(ZombieRecord r) {
        ZombieRecord c = r;
        for (int i = 0; i < MAX_CHAIN && c.followId > 0; i++) {
            ZombieRecord next = store.get(c.followId);
            if (next == null || next == r) break;
            c = next;
        }
        return c;
    }

    private void formChain(ZombieRecord r, double nowHours) {
        ZombieRecord head = head(r);
        if (head == r) return;
        List<ZombieRecord> chain = new ArrayList<>();
        grid.near(head.x(), head.y(), CHAIN_RADIUS, w -> {
            if (!w.straggler && moving(w, nowHours) && (w == head || head(w) == head)) chain.add(w);
        }, h -> {});
        if (chain.size() < s.hordeFormSize()) return;
        for (ZombieRecord w : chain) {
            store.remove(w.id);
            w.followId = 0;
        }
        Horde h = hordes.create(head.x(), head.y(), head.heading, chain);
        grid.putHorde(h);
        formed++;
    }

    private void join(Horde h, ZombieRecord r) {
        float old = h.spread();
        store.remove(r.id);
        r.followId = 0;
        hordes.addMember(h, r);
        grid.moveHorde(h, h.x, h.y, old);
        joined++;
    }

    /**
     * Encounters for one horde after it moved through {@code tightChunks} tight spots: merging with any horde it
     * touches, break-aways, and breaking up below the minimum size. Returns the horde that carries on (this one, the
     * one it merged into) or null if it broke up.
     */
    public Horde horde(Horde h, double nowHours, int tightChunks) {
        seenHordes.clear();
        grid.near(h.x, h.y, h.spread(), w -> {}, seenHordes::add);
        for (Horde o : seenHordes) {
            if (o == h || hordes.get(o.id) == null) continue;
            h = merge(h, o);
        }
        if (tightChunks > 0 && s.breakAwayChance() > 0) {
            float old = h.spread();
            for (int t = 0; t < tightChunks; t++) {
                for (int i = h.members.size() - 1; i >= 0; i--) {
                    if (rng.nextDouble() < s.breakAwayChance()) {
                        ZombieRecord r = h.members.get(i);
                        hordes.removeMember(h, r);
                        leaveAsWanderer(h, r, old, nowHours + 1 + 5 * rng.nextDouble());
                        brokeAway++;
                    }
                }
            }
            if (h.spread() != old) grid.moveHorde(h, h.x, h.y, old);
        }
        if (h.size() < s.hordeMinSize()) {
            dissolve(h, nowHours);
            return null;
        }
        return h;
    }

    /** Merges two touching hordes: the bigger keeps its identity, its heading nudged by the smaller in proportion. */
    private Horde merge(Horde a, Horde b) {
        Horde big = a.size() >= b.size() ? a : b, small = big == a ? b : a;
        float old = big.spread();
        double hx = big.size() * Math.cos(big.heading) + small.size() * Math.cos(small.heading);
        double hy = big.size() * Math.sin(big.heading) + small.size() * Math.sin(small.heading);
        grid.removeHorde(small);
        hordes.absorb(big, small);
        if (hx != 0 || hy != 0) big.heading = (float) Math.atan2(hy, hx);
        grid.moveHorde(big, big.x, big.y, old);
        merged++;
        return big;
    }

    private void dissolve(Horde h, double nowHours) {
        grid.removeHorde(h);
        float spread = h.spread();
        hordes.remove(h.id);
        for (ZombieRecord r : h.members) leaveAsWanderer(h, r, spread, nowHours);
        dissolved++;
    }

    /** Puts a horde member back in the Store as a wanderer somewhere inside the horde's circle. */
    private void leaveAsWanderer(Horde h, ZombieRecord r, float spread, double lingerUntilHours) {
        double a = rng.nextDouble() * 2 * Math.PI, d = Math.sqrt(rng.nextDouble()) * spread;
        r.followId = 0;
        r.pauseMinutes = 0;
        r.heading = h.heading;
        r.lingerUntilHours = lingerUntilHours;
        store.restore(r);
        store.moved(r, (float) (h.x + Math.cos(a) * d), (float) (h.y + Math.sin(a) * d));
    }

    /** A wanderer heard {@code e} and is heading for it. */
    public void responded(SoundEvent e, ZombieRecord r) {
        List<ZombieRecord> l = responders.computeIfAbsent(e.id(), k -> new ArrayList<>());
        if (!l.contains(r)) l.add(r);
    }

    /** {@code e} faded: the non-straggler responders gathered at it become a horde if there are enough. */
    public void soundEnded(SoundEvent e) {
        List<ZombieRecord> l = responders.remove(e.id());
        if (l == null) return;
        List<ZombieRecord> gathered = new ArrayList<>();
        double sx = 0, sy = 0;
        for (ZombieRecord r : l) {
            if (r.straggler || store.get(r.id) != r) continue;
            if (Hearing.dist(r.x(), r.y(), e.x(), e.y()) > GATHER_RADIUS) continue;
            gathered.add(r);
            sx += r.x();
            sy += r.y();
        }
        if (gathered.size() < s.hordeFormSize()) return;
        for (ZombieRecord r : gathered) {
            store.remove(r.id);
            r.followId = 0;
        }
        Horde h = hordes.create((float) (sx / gathered.size()), (float) (sy / gathered.size()),
            (float) (rng.nextDouble() * 2 * Math.PI - Math.PI), gathered);
        grid.putHorde(h);
        formed++;
    }

    /** Sounds whose responders are still tracked (each is dropped when its sound ends). */
    public int respondersTracked() { return responders.size(); }

    public int formed() { return formed; }
    public int joined() { return joined; }
    public int merged() { return merged; }
    public int brokeAway() { return brokeAway; }
    public int dissolved() { return dissolved; }
}
