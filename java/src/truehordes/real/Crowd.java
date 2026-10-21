package truehordes.real;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.LongPredicate;

/**
 * Real zombies form, join, merge and break up hordes by the simulation's own rules (sub-project 6 spec goal 4):
 * walking into a horde joins it at once; seeing one is a chance per hour; touching another walker is one roll per pair
 * to fall in behind it; following someone in sight is a chance per hour; a milling zombie now and then drifts to a group
 * nearby; a follow chain of HordeFormSize becomes a horde; a horde whose real members fall below HordeMinSize breaks up
 * unless part of it is still held off screen. Chances are the settings' chances times the day ramp. Pure.
 */
public final class Crowd {
    private Crowd() {}

    static final float BUMP = 2f;
    static final float SIGHT = 40f;
    static final float CHAIN_RADIUS = 40f;
    static final float LOST = 48f;
    /** How long a drifting zombie heads for a group (in-game hours), as off screen. */
    static final double DRIFT_HOURS = 3;
    static final int CROWD_MIN = 3;
    /** Neighbour cells: SIGHT wide, so the 3x3 round a zombie covers everything in sight. */
    static final int CELL = 40;

    /** The chances, already ramped for the day, and the horde sizes. */
    public record Rules(double joinPerHour, double bumpChance, double followPerHour, double driftPerHour,
                        int formSize, int minSize) {}

    /** Makes a new horde id for a chain that became a horde (registered so it re-forms off screen); 0 = none. */
    public interface NewHorde {
        long create(float heading);
    }

    /** A group of real zombies in one horde: its members by column slot (leader first) and its middle. */
    public static final class Group {
        public final long id;
        public final List<Pilot> members = new ArrayList<>();
        public float cx, cy;

        Group(long id) { this.id = id; }

        public Pilot leader() { return members.get(0); }

        /**
         * The leader the rest walk behind: the first member not off chasing a player alone and not held by vanilla
         * (in-game test 2026-10-21: a horde stood around a sitting or lying leader).
         */
        public Pilot leader(double nowHours) {
            Pilot free = null;
            for (Pilot p : members) {
                if (p.chasing(nowHours)) continue;
                if (!p.busy) return p;
                if (free == null) free = p;
            }
            return free != null ? free : members.get(0);
        }

        public float spread() { return (float) (2 + Math.sqrt(members.size()) * 0.5); }
    }

    /**
     * Chase memory (user, 2026-10-20): only the zombies that saw the player chase, unless more than half of the horde
     * did; then the rest follow the latest chase. Returns how many joined.
     */
    public static int chaseRule(Group g, double nowHours) {
        Pilot latest = null;
        int n = 0;
        for (Pilot p : g.members) {
            if (!p.chasing(nowHours)) continue;
            n++;
            if (latest == null || p.chaseUntilHours > latest.chaseUntilHours) latest = p;
        }
        if (latest == null || n * 2 <= g.members.size() || n == g.members.size()) return 0;
        int joined = 0;
        for (Pilot p : g.members) {
            if (p.chasing(nowHours)) continue;
            p.chaseWith(latest);
            joined++;
        }
        return joined;
    }

    /** The real hordes among {@code ps}: members ordered by id (the oldest leads), slots set, middles computed. */
    public static Map<Long, Group> groups(List<Pilot> ps) {
        Map<Long, Group> out = new HashMap<>();
        for (Pilot p : ps) if (p.hordeId != 0) out.computeIfAbsent(p.hordeId, Group::new).members.add(p);
        for (Group g : out.values()) {
            g.members.sort((a, b) -> Long.compare(a.id, b.id));
            double sx = 0, sy = 0;
            for (int i = 0; i < g.members.size(); i++) {
                Pilot p = g.members.get(i);
                p.slot = i;
                sx += p.x;
                sy += p.y;
            }
            g.cx = (float) (sx / g.members.size());
            g.cy = (float) (sy / g.members.size());
        }
        return out;
    }

    static float dist(float ax, float ay, float bx, float by) { return (float) Math.hypot(ax - bx, ay - by); }

    static double perHour(double p, double dtHours) { return p <= 0 ? 0 : 1 - Math.pow(1 - Math.min(1, p), dtHours); }

    /**
     * One pass over every real zombie {@code ps} (those vanilla is busy with included: membership does not need a
     * free zombie), {@code dtHours} since the last pass. {@code heldPart} says whether a horde still has members held
     * off screen. Returns the groups after the pass.
     */
    public static Map<Long, Group> update(List<Pilot> ps, double dtHours, double nowHours, Rules r,
                                          LongPredicate heldPart, NewHorde ids, DoubleSupplier rng) {
        Map<Long, Group> gs = groups(ps);
        // Break up: too few real members and nothing of the horde left off screen.
        for (Group g : new ArrayList<>(gs.values())) {
            if (g.members.size() < r.minSize() && !heldPart.test(g.id)) {
                for (Pilot p : g.members) p.hordeId = 0;
                gs.remove(g.id);
            }
        }
        // Merge: two real hordes touching become the bigger one (one with members off screen keeps its id).
        List<Group> list = new ArrayList<>(gs.values());
        for (int i = 0; i < list.size(); i++) {
            Group a = list.get(i);
            if (!gs.containsKey(a.id)) continue;
            for (int j = i + 1; j < list.size(); j++) {
                Group b = list.get(j);
                if (!gs.containsKey(b.id) || dist(a.cx, a.cy, b.cx, b.cy) > a.spread() + b.spread()) continue;
                Group big = a.members.size() >= b.members.size() ? a : b, small = big == a ? b : a;
                if (heldPart.test(small.id) && !heldPart.test(big.id)) {
                    Group t = big;
                    big = small;
                    small = t;
                }
                for (Pilot p : small.members) p.hordeId = big.id;
                big.members.addAll(small.members);
                gs.remove(small.id);
                if (small == a) break;
            }
        }
        gs = groups(ps);
        Map<Long, Pilot> byId = new HashMap<>();
        Map<Long, List<Pilot>> cells = new HashMap<>();
        Map<Long, List<Pilot>> memberCells = new HashMap<>();
        List<Pilot> loose = new ArrayList<>();
        for (Pilot p : ps) {
            byId.put(p.id, p);
            if (p.hordeId != 0 && gs.containsKey(p.hordeId)) {
                memberCells.computeIfAbsent(cell(p.x, p.y), k -> new ArrayList<>()).add(p);
            }
            if (p.hordeId != 0 || p.dormant || p.straggler) continue;
            loose.add(p);
            cells.computeIfAbsent(cell(p.x, p.y), k -> new ArrayList<>()).add(p);
        }
        double join = perHour(r.joinPerHour(), dtHours), follow = perHour(r.followPerHour(), dtHours),
            drift = perHour(r.driftPerHour(), dtHours);
        for (Pilot p : loose) {
            if (p.followId != 0) {
                Pilot t = byId.get(p.followId);
                if (t == null || dist(p.x, p.y, t.x, t.y) > LOST) p.followId = 0;
            }
            // A horde touched joins at once; one in sight is a chance per hour.
            Group seen = null;
            boolean joined = false;
            for (Group g : gs.values()) {
                float d = dist(p.x, p.y, g.cx, g.cy);
                if (d <= g.spread() + BUMP) {
                    p.hordeId = g.id;
                    p.followId = 0;
                    joined = true;
                    break;
                }
                if (d <= SIGHT && seen == null) seen = g;
            }
            if (!joined) {
                // Touching any member (in-game test 2026-10-21: a long column walked past loaded zombies, as only
                // its middle counted): the horde picks it up as it goes.
                for (Pilot m : near(memberCells, p.x, p.y)) {
                    if (dist(p.x, p.y, m.x, m.y) <= BUMP) {
                        p.hordeId = m.hordeId;
                        p.followId = 0;
                        joined = true;
                        break;
                    }
                }
            }
            if (joined) continue;
            if (seen != null && rng.getAsDouble() < join) {
                p.hordeId = seen.id;
                p.followId = 0;
                continue;
            }
            if (p.followId == 0) {
                // Nearest first; the chain-loop check only for the one picked (performance pass: checking every pair
                // walked chains for each of up to a thousand neighbours every second).
                Pilot touching = null, inSight = null;
                float best = Float.MAX_VALUE;
                for (Pilot w : near(cells, p.x, p.y)) {
                    if (w == p) continue;
                    float d = dist(p.x, p.y, w.x, w.y);
                    if (d <= SIGHT && d < best) {
                        best = d;
                        inSight = w;
                    }
                }
                if (inSight != null && leads(p, inSight, byId)) inSight = null;
                if (inSight != null && best <= BUMP) touching = inSight;
                if (touching != null && touching.id != p.bumpedId && rng.getAsDouble() < r.bumpChance()) {
                    p.followId = touching.id;
                } else if (inSight != null && rng.getAsDouble() < follow) {
                    p.followId = inSight.id;
                }
                p.bumpedId = touching == null ? 0 : touching.id;
            }
            if (p.followId == 0 && !p.lured(nowHours) && rng.getAsDouble() < drift) driftTo(p, gs, cells, nowHours);
        }
        formChains(ps, byId, r.formSize(), ids);
        return groups(ps);
    }

    private static void driftTo(Pilot p, Map<Long, Group> gs, Map<Long, List<Pilot>> cells, double nowHours) {
        Group best = null;
        for (Group g : gs.values()) {
            float d = dist(p.x, p.y, g.cx, g.cy);
            if (d <= SIGHT && (best == null || d < dist(p.x, p.y, best.cx, best.cy))) best = g;
        }
        if (best != null) {
            p.lure(best.cx, best.cy, nowHours + DRIFT_HOURS, 0);
            return;
        }
        double sx = 0, sy = 0;
        int n = 0;
        for (Pilot w : near(cells, p.x, p.y)) {
            if (w == p || dist(p.x, p.y, w.x, w.y) > SIGHT) continue;
            sx += w.x;
            sy += w.y;
            n++;
        }
        if (n >= CROWD_MIN) p.lure((float) (sx / n), (float) (sy / n), nowHours + DRIFT_HOURS, 0);
    }

    /** Whether {@code p} is ahead of {@code w} in a chain (following w would make a loop). */
    static boolean leads(Pilot p, Pilot w, Map<Long, Pilot> byId) {
        Pilot c = w;
        for (int i = 0; i < 64 && c != null && c.followId != 0; i++) {
            if (c.followId == p.id) return true;
            c = byId.get(c.followId);
        }
        return false;
    }

    /** Chains of loose followers: a chain of {@code formSize} or more around its head becomes a horde. */
    static void formChains(List<Pilot> ps, Map<Long, Pilot> byId, int formSize, NewHorde ids) {
        Map<Long, List<Pilot>> followers = new HashMap<>();
        for (Pilot p : ps) {
            if (p.hordeId == 0 && p.followId != 0) followers.computeIfAbsent(p.followId, k -> new ArrayList<>()).add(p);
        }
        for (Pilot head : ps) {
            if (head.hordeId != 0 || head.followId != 0 || !followers.containsKey(head.id)) continue;
            List<Pilot> chain = new ArrayList<>();
            chain.add(head);
            for (int i = 0; i < chain.size(); i++) {
                for (Pilot f : followers.getOrDefault(chain.get(i).id, List.of())) {
                    if (f.hordeId == 0 && dist(f.x, f.y, head.x, head.y) <= CHAIN_RADIUS) chain.add(f);
                }
            }
            if (chain.size() < formSize) continue;
            long id = ids.create(head.heading);
            if (id == 0) continue;
            for (Pilot p : chain) {
                p.hordeId = id;
                p.followId = 0;
            }
        }
    }

    static long cell(float x, float y) {
        return ((long) Math.floorDiv((int) Math.floor(x), CELL) << 32) | (Math.floorDiv((int) Math.floor(y), CELL) & 0xFFFFFFFFL);
    }

    static List<Pilot> near(Map<Long, List<Pilot>> cells, float x, float y) {
        List<Pilot> out = new ArrayList<>();
        int cx = Math.floorDiv((int) Math.floor(x), CELL), cy = Math.floorDiv((int) Math.floor(y), CELL);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<Pilot> l = cells.get(((long) (cx + dx) << 32) | ((cy + dy) & 0xFFFFFFFFL));
                if (l != null) out.addAll(l);
            }
        }
        return out;
    }
}
