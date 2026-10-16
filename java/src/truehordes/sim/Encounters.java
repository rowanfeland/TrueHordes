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
    static final float CHAIN_RADIUS = 40f;
    /** Responders within this distance of a sound when it ends count as gathered there (tiles). */
    static final float GATHER_RADIUS = 8f;
    /** Closer than this counts as bumping into someone (tiles). */
    static final float BUMP_RADIUS = 2f;

    /**
     * How much of the forming chances (bump-follow, and since the user's option "both" also sight-follow, joining a
     * horde in sight and noticing moaning) applies {@code days} in-game days after the start (user, 2026-10-15): day 1 is
     * pure chaos (none), day 2 rises from 1% to 2%, then slowly up to 25% by day 7, 50% by day 14 and the full chance
     * on the population peak day (a peak before day 15 is taken as day 15).
     */
    /** A forming chance (bump, sight-follow, join by sight, moan response) as it applies at {@code nowHours}. */
    public double ramped(double chance, double nowHours) { return chance * bumpRamp(nowHours / 24, s.peakDay()); }

    public static double bumpRamp(double days, int peakDay) {
        if (days < 1) return 0;
        if (days < 2) return 0.01 + 0.01 * (days - 1);
        if (days < 7) return 0.02 + 0.23 * (days - 2) / 5;
        if (days < 14) return 0.25 + 0.25 * (days - 7) / 7;
        int peak = Math.max(peakDay, 15);
        if (days < peak) return 0.5 + 0.5 * (days - 14) / (peak - 14);
        return 1;
    }
    /**
     * Longest follow chain: a chain of HordeFormSize moving together already becomes a horde, so deeper chains are
     * strung-out ones; capping them keeps every chain walk short (in dense towns they grew hundreds deep).
     */
    static final int MAX_CHAIN = 32;

    private final Store store;
    private final Hordes hordes;
    private final SpatialGrid grid;
    private final SimSettings s;
    private final Random rng;
    private final java.util.function.DoubleSupplier random;
    private final Map<Long, java.util.Set<ZombieRecord>> responders = new HashMap<>();
    private final List<ZombieRecord> seenWanderers = new ArrayList<>();
    private final List<Horde> seenHordes = new ArrayList<>();
    private final java.util.function.Consumer<ZombieRecord> addSeenWanderer = seenWanderers::add;
    private final java.util.function.Consumer<Horde> addSeenHorde = seenHordes::add;
    private int formed, joined, merged, brokeAway, dissolved;

    public Encounters(Store store, Hordes hordes, SpatialGrid grid, SimSettings s, Random rng) {
        this.store = store;
        this.hordes = hordes;
        this.grid = grid;
        this.s = s;
        this.rng = rng;
        this.random = rng::nextDouble;
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
        seenHordes.clear();
        grid.nearHordes(r.x(), r.y(), radius, addSeenHorde);
        // Joining takes a little time (user, 2026-10-14): a wanderer that sees a horde joins with a chance per hour.
        double joinP = 1 - Math.pow(1 - ramped(s.joinChancePerHour(), nowHours), dtHours);
        for (int i = 0; i < seenHordes.size(); i++) {
            Horde h = seenHordes.get(i);
            if (h.dormant) continue; // a building horde inside is not seen walking
            // Walking into a horde joins it at once (in-game test 2026-10-15); seeing one is a chance per hour.
            if (Hearing.dist(r.x(), r.y(), h.x, h.y) <= h.spread() + BUMP_RADIUS) {
                join(h, r);
                return;
            }
            if (Vision.sees(r.x(), r.y(), r.heading, h.x, h.y, h.spread(), radius, random)) {
                if (random.getAsDouble() < joinP) {
                    join(h, r);
                    return;
                }
                break;
            }
        }
        if (r.followId > 0 && store.get(r.followId) == null) r.followId = 0;
        // Bumping into another walker: a good chance to fall in behind it (the snowball on roads).
        if (r.followId == 0 && moving(r, nowHours)) {
            seenWanderers.clear();
            grid.nearWanderers(r.x(), r.y(), BUMP_RADIUS, addSeenWanderer);
            long touching = 0;
            for (int i = 0; i < seenWanderers.size(); i++) {
                ZombieRecord w = seenWanderers.get(i);
                if (w == r || w.straggler || !moving(w, nowHours) || leads(r, w) || depth(w) >= MAX_CHAIN - 1) continue;
                touching = w.id;
                // One roll when they first touch (user, 2026-10-15), not a new one on every update they stay touching.
                if (w.id != r.bumpedId && rng.nextDouble() < ramped(s.bumpFollowChance(), nowHours)) r.followId = w.id;
                break;
            }
            r.bumpedId = touching;
        }
        // Following: the chance roll comes first, then the zombie looks for someone walking past to fall in behind
        // (the same odds as rolling once per update with a zombie in sight, without searching on every update).
        double follow = ramped(s.followChancePerHour(), nowHours);
        if (r.followId == 0 && follow > 0 && moving(r, nowHours)
                && rng.nextDouble() < 1 - Math.pow(1 - follow, dtHours)) {
            seenWanderers.clear();
            grid.nearWanderers(r.x(), r.y(), radius, addSeenWanderer);
            for (int i = 0; i < seenWanderers.size(); i++) {
                ZombieRecord w = seenWanderers.get(i);
                if (w == r || w.straggler || !moving(w, nowHours) || leads(r, w) || depth(w) >= MAX_CHAIN - 1) continue;
                if (!Vision.sees(r.x(), r.y(), r.heading, w.x(), w.y(), 0, radius, random)) continue;
                r.followId = w.id;
                break;
            }
        }
        if (r.followId > 0) formChain(r, nowHours);
        else crowdDrift(r, nowHours, dtHours);
    }

    /** How far a milling zombie notices a group of others (hears their shuffling and moaning), in tiles. */
    static final float CROWD_RADIUS = 40f;
    /** Others it takes to make a group worth drifting to. */
    static final int CROWD_MIN = 3;
    /** How long a drifting zombie keeps heading for the group (in-game hours). */
    static final double DRIFT_HOURS = 3;

    /**
     * Round 8: a milling zombie now and then drifts towards a group nearby (a chance per hour, ramped by the days like
     * following): a horde it notices, else the middle of a cluster of millers, snapped to that chunk's centre so drifters
     * from the same cluster meet at one spot, where 10 or more become a horde (gatherAt).
     */
    private void crowdDrift(ZombieRecord r, double nowHours, double dtHours) {
        double p = ramped(s.crowdDriftPerHour(), nowHours);
        if (p <= 0 || r.straggler || !moving(r, nowHours) || (r.hasTarget() && r.investigateUntilHours > nowHours)) return;
        if (Simulation.traveller(r.id, s.travellerShare())) return;
        if (rng.nextDouble() >= 1 - Math.pow(1 - p, dtHours)) return;
        Horde best = null;
        float bestD = Float.MAX_VALUE;
        seenHordes.clear();
        grid.nearHordes(r.x(), r.y(), CROWD_RADIUS, addSeenHorde);
        for (int i = 0; i < seenHordes.size(); i++) {
            Horde h = seenHordes.get(i);
            float d = Hearing.dist(r.x(), r.y(), h.x, h.y);
            if (!h.dormant && d < bestD) {
                bestD = d;
                best = h;
            }
        }
        float tx, ty;
        if (best != null) {
            tx = best.x;
            ty = best.y;
        } else {
            seenWanderers.clear();
            grid.nearWanderers(r.x(), r.y(), CROWD_RADIUS, addSeenWanderer);
            double sx = 0, sy = 0;
            int n = 0;
            for (int i = 0; i < seenWanderers.size(); i++) {
                ZombieRecord w = seenWanderers.get(i);
                if (w == r || w.straggler) continue;
                sx += w.x();
                sy += w.y();
                n++;
            }
            if (n < CROWD_MIN) return;
            // Fall in with the biggest group in reach (the head with the most followers here), else behind the one
            // nearest the middle: groups snowball, and 10 moving together become a horde (formChain). In-game test
            // 2026-10-16: picking only the middle one gave thousands of separate one-follower chains and no hordes.
            float cx = (float) (sx / n), cy = (float) (sy / n);
            ZombieRecord lead = null;
            float leadD = Float.MAX_VALUE;
            int leadN = 0;
            groupCount.clear();
            for (int i = 0; i < seenWanderers.size(); i++) {
                ZombieRecord w = seenWanderers.get(i);
                if (w.followId > 0) groupCount.merge(head(w).id, 1, Integer::sum);
            }
            for (int i = 0; i < seenWanderers.size(); i++) {
                ZombieRecord w = seenWanderers.get(i);
                if (w == r || w.straggler || !moving(w, nowHours) || leads(r, w) || depth(w) >= MAX_CHAIN - 1) continue;
                int g = w.followId == 0 ? groupCount.getOrDefault(w.id, 0) : -1;
                float d = Hearing.dist(cx, cy, w.x(), w.y());
                if (g > leadN || (g == leadN && d < leadD)) {
                    leadN = g;
                    leadD = d;
                    lead = w;
                }
            }
            if (lead == null) return;
            r.followId = lead.id;
            drifted++;
            return;
        }
        r.targetX = (int) Math.floor(tx);
        r.targetY = (int) Math.floor(ty);
        r.targetDb = 0; // any real sound outranks drifting
        r.investigateUntilHours = nowHours + DRIFT_HOURS;
        drifted++;
    }

    int drifted;
    private final Map<Long, Integer> groupCount = new HashMap<>();

    /** True if {@code w} already trails {@code r} (directly or down the chain): following it would make a loop. */
    private boolean leads(ZombieRecord r, ZombieRecord w) {
        ZombieRecord c = w;
        for (int i = 0; i < MAX_CHAIN && c != null && c.followId > 0; i++) {
            if (c.followId == r.id) return true;
            c = store.get(c.followId);
        }
        return false;
    }

    /** How many links lead from {@code w} to the front of its chain (at most MAX_CHAIN). */
    private int depth(ZombieRecord w) {
        int d = 0;
        for (ZombieRecord c = w; d < MAX_CHAIN && c != null && c.followId > 0; d++) c = store.get(c.followId);
        return d;
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
        grid.nearWanderers(head.x(), head.y(), CHAIN_RADIUS, w -> {
            if (!w.straggler && moving(w, nowHours) && (w == head || head(w) == head)) chain.add(w);
        });
        if (chain.size() < s.hordeFormSize()) return;
        for (ZombieRecord w : chain) {
            store.remove(w.id);
            w.followId = 0;
        }
        Horde h = hordes.create(head.x(), head.y(), head.heading, chain);
        grid.putHorde(h);
        formed++;
    }

    /**
     * A horde that walked from (ox, oy) to where it is now rolls up the lone zombies it passed on the way, not only
     * those where it was set down (in-game test 2026-10-16: the wave should pick up what lies in its path). Building
     * zombies (idle) stay inside.
     */
    public void sweep(Horde h, float ox, float oy) {
        float len = Hearing.dist(ox, oy, h.x, h.y);
        if (h.dormant || len < 1) return;
        float reach = h.spread() + BUMP_RADIUS;
        int steps = (int) Math.ceil(len / Math.max(2f, reach));
        seenWanderers.clear();
        for (int i = 0; i < steps; i++) {
            float f = (float) i / steps;
            grid.nearWanderers(ox + (h.x - ox) * f, oy + (h.y - oy) * f, reach, addSeenWanderer);
        }
        for (int i = 0; i < seenWanderers.size(); i++) {
            ZombieRecord w = seenWanderers.get(i);
            if (!w.idle && store.get(w.id) == w) join(h, w);
        }
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
        if (h.dormant) return h;
        seenHordes.clear();
        grid.nearHordes(h.x, h.y, h.spread(), addSeenHorde);
        for (int i = 0; i < seenHordes.size(); i++) {
            Horde o = seenHordes.get(i);
            if (o == h || o.dormant || hordes.get(o.id) == null) continue;
            h = merge(h, o);
        }
        if (tightChunks > 0 && s.breakAwayChance() > 0) {
            float old = h.spread();
            for (int t = 0; t < tightChunks; t++) {
                for (int i = h.members.size() - 1; i >= 0; i--) {
                    if (rng.nextDouble() < s.breakAwayChance()) {
                        ZombieRecord r = hordes.removeMemberAt(h, i); // going backwards: the swapped-in one was seen
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
        r.homeX = Float.NaN; // left behind: home is where it is now
        r.homeY = Float.NaN;
        store.restore(r);
        store.moved(r, (float) (h.x + Math.cos(a) * d), (float) (h.y + Math.sin(a) * d));
    }

    /** A wanderer heard {@code e} and is heading for it. */
    public void responded(SoundEvent e, ZombieRecord r) {
        responders.computeIfAbsent(e.id(), k -> new java.util.LinkedHashSet<>()).add(r);
    }

    /**
     * A zombie arrived where it heard a sound (tile tx, ty): if enough non-stragglers that heard the same sound are
     * gathered there, they become a horde.
     */
    public void gatherAt(float x, float y, int tx, int ty) {
        List<ZombieRecord> gathered = new ArrayList<>();
        grid.nearWanderers(x, y, GATHER_RADIUS, w -> {
            if (!w.straggler && w.targetX == tx && w.targetY == ty) gathered.add(w);
        });
        if (gathered.size() < s.hordeFormSize()) return;
        for (ZombieRecord r : gathered) {
            store.remove(r.id);
            r.followId = 0;
            r.targetX = ZombieRecord.NO_TARGET;
            r.targetY = ZombieRecord.NO_TARGET;
            r.investigateUntilHours = 0;
        }
        Horde h = hordes.create(x, y, (float) (rng.nextDouble() * 2 * Math.PI - Math.PI), gathered);
        grid.putHorde(h);
        formed++;
    }

    /** {@code e} faded: the non-straggler responders gathered at it become a horde if there are enough. */
    public void soundEnded(SoundEvent e) {
        java.util.Set<ZombieRecord> l = responders.remove(e.id());
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
