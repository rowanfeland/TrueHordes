package truehordes.sim;

import java.util.function.DoubleSupplier;

/**
 * How a wanderer or a horde picks its heading and walks off screen (spec 4.1). Each pick scores a fan of headings on
 * momentum, terrain ahead and an optional pull (moaning, a sound, a followed zombie), then picks by weighted random:
 * roads get used most because they score best, but nothing assigns a route (D4). Water, buildings and the map's edge
 * are never entered. Stateless apart from the map and settings; safe to share on one thread.
 */
public final class Movement {
    /** Heading offsets tried each pick: straight, +-45, +-90 degrees, and turning back (rare). */
    private static final float[] FAN = {0f, (float) (Math.PI / 4), (float) (-Math.PI / 4), (float) (Math.PI / 2),
        (float) (-Math.PI / 2), (float) Math.PI};
    private static final float BACK_WEIGHT = 0.05f;
    private static final float MOMENTUM = 2f;
    /**
     * How much one step of terrain preference counts (road vs open ground is 3 steps). Tuned so roads get roughly half
     * again their share of zombies, not all of them (in-game test 2026-10-14: at 1 every road filled within a day).
     */
    private static final float TERRAIN_WEIGHT = 0.2f;
    private static final int LOOK_AHEAD = 3;
    private static final float CHUNK = 8f;
    /** Longest straight piece walked before the next map check, in tiles (half a chunk). */
    private static final float SEGMENT = 4f;
    private static final float CRAWLER = 0.35f;

    private final MapView map;
    private final SimSettings s;
    // Scratch for chooseHeading (one simulation thread; avoids garbage on every pick).
    private final double[] w = new double[FAN.length];
    private final double[] kept = new double[FAN.length];
    /** Chance roll a heading needs because it leaves a road or trail (1 = none needed). */
    private final double[] leaveChance = new double[FAN.length];

    public Movement(MapView map, SimSettings s) {
        this.map = map;
        this.s = s;
    }

    public MapView map() { return map; }

    /** One leg of walking: where the walker ended, its heading, tight-spot chunks entered, and whether it is boxed in. */
    public record Step(float x, float y, float heading, int tightChunks, boolean boxedIn) {}

    /**
     * Picks the next heading from (x, y). {@code pullWeight} 0 = no pull. Returns NaN when every heading is blocked.
     */
    public float chooseHeading(float x, float y, float heading, float pullX, float pullY, float pullWeight,
                               DoubleSupplier rng) {
        if (pullWeight > 0) return choose(x, y, heading, pullX, pullY, pullWeight, rng, false);
        return travel(x, y, heading, rng);
    }

    // ---- travelling (no pull): straight down roads and trails, straight lines across country ----

    /** Chance per chunk on a straight road or trail to head off across country (times OffRoadChance). */
    static final double STRAIGHT_LEAVE = 0.05;
    /** Chance at a junction to turn onto the side road (trails also need the TrailChance roll). */
    static final double JUNCTION_TURN = 0.3;
    /** Chance per chunk that a walker crossing country turns onto a road or trail beside it. */
    static final double ROAD_JOIN = 0.75;
    /** Straight ahead, then 45 degrees either side, then 90 (no garbage per call: this runs millions of times). */
    private static final int[] AHEAD_AND_SIDES = {0, 1, -1, 2, -2};
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    static int dirIndex(float heading) {
        return Math.floorMod((int) Math.round(heading / (Math.PI / 4)), 8);
    }

    static float dirAngle(int d) { return normalise((float) (d * Math.PI / 4)); }

    /** The chunk one step in direction d can be entered and the one after it is still inside the map. */
    private boolean open(int cx, int cy, int d) {
        int nx = cx + DX[d], ny = cy + DY[d];
        return map.inMap(nx + DX[d], ny + DY[d]) && enterable(cx, cy, nx, ny);
    }

    private boolean way(int cx, int cy, int d) {
        int r = map.rank(cx + DX[d], cy + DY[d]);
        return (r == 1 || r == 2) && open(cx, cy, d);
    }

    /**
     * Where a walker with nothing pulling it goes next (user, in-game test 2026-10-15): on a road or trail it keeps
     * walking down it, follows bends, sometimes turns at junctions, turns back at dead ends, and now and then heads off
     * across country; across country it keeps its straight line, turns onto a road or trail it meets, and only turns
     * aside (the smallest turn that works) when blocked. NaN when boxed in.
     */
    private float travel(float x, float y, float heading, DoubleSupplier rng) {
        int cx = chunk(x), cy = chunk(y);
        int here = map.rank(cx, cy);
        if (here == 1 || here == 2) return alongWay(cx, cy, here, heading, rng);
        // Across country: a road or trail beside the walker, ahead or to the side, is usually taken.
        int f = dirIndex(heading);
        for (int k : AHEAD_AND_SIDES) {
            int d = Math.floorMod(f + k, 8);
            if (way(cx, cy, d) && rng.getAsDouble() < ROAD_JOIN) {
                int r = map.rank(cx + DX[d], cy + DY[d]);
                if (r == 2 && rng.getAsDouble() >= s.trailChance()) continue;
                return dirAngle(d);
            }
        }
        if (straightOpen(x, y, heading)) return heading;
        return smallestTurn(cx, cy, f, rng);
    }

    /** The continuous heading's next two chunks can be walked into. */
    private boolean straightOpen(float x, float y, float heading) {
        int cx = chunk(x), cy = chunk(y);
        int nx = chunk((float) (x + Math.cos(heading) * CHUNK)), ny = chunk((float) (y + Math.sin(heading) * CHUNK));
        int fx = chunk((float) (x + Math.cos(heading) * CHUNK * 2)), fy = chunk((float) (y + Math.sin(heading) * CHUNK * 2));
        if (!map.inMap(fx, fy)) return false;
        if (nx == cx && ny == cy) return true;
        return enterable(cx, cy, nx, ny);
    }

    /** Blocked: the smallest turn (45, 90, 135 degrees, either side at random first) that can be walked; else back. */
    private float smallestTurn(int cx, int cy, int f, DoubleSupplier rng) {
        int first = rng.getAsDouble() < 0.5 ? 1 : -1;
        for (int k = 1; k <= 3; k++) {
            if (open(cx, cy, Math.floorMod(f + first * k, 8))) return dirAngle(Math.floorMod(f + first * k, 8));
            if (open(cx, cy, Math.floorMod(f - first * k, 8))) return dirAngle(Math.floorMod(f - first * k, 8));
        }
        int back = Math.floorMod(f + 4, 8);
        return open(cx, cy, back) ? dirAngle(back) : Float.NaN;
    }

    private float alongWay(int cx, int cy, int here, float heading, DoubleSupplier rng) {
        int f = dirIndex(heading);
        boolean ahead = way(cx, cy, f);
        if (ahead) {
            // A straight stretch: keep going; sometimes take a side road at a junction, rarely head off across country.
            if (rng.getAsDouble() < s.offRoadChance() * STRAIGHT_LEAVE) {
                float off = offWay(cx, cy, f, rng);
                if (!Float.isNaN(off)) return off;
            }
            // Side roads only at a real junction (three or more ways meet): at a corner the "side road" is the
            // road the walker just came along.
            int ways = 0;
            for (int d = 0; d < 8; d += 2) if (way(cx, cy, d)) ways++;
            if (ways < 3) return dirAngle(f);
            int side = rng.getAsDouble() < 0.5 ? 2 : -2;
            for (int j = 0; j < 2; j++) {
                int d = Math.floorMod(f + (j == 0 ? side : -side), 8);
                if (!way(cx, cy, d) || rng.getAsDouble() >= JUNCTION_TURN) continue;
                if (here == 1 && map.rank(cx + DX[d], cy + DY[d]) == 2 && rng.getAsDouble() >= s.trailChance()) continue;
                return dirAngle(d);
            }
            return dirAngle(f);
        }
        // The road bends, splits or ends: at this decision point the walker may leave it for open country.
        if (rng.getAsDouble() < s.offRoadChance()) {
            float off = offWay(cx, cy, f, rng);
            if (!Float.isNaN(off)) return off;
        }
        int first = rng.getAsDouble() < 0.5 ? 1 : -1;
        for (int k = 1; k <= 3; k++) {
            for (int j = 0; j < 2; j++) {
                int d = Math.floorMod(f + (j == 0 ? first : -first) * k, 8);
                if (!way(cx, cy, d)) continue;
                if (here == 1 && map.rank(cx + DX[d], cy + DY[d]) == 2 && rng.getAsDouble() >= s.trailChance()) continue;
                return dirAngle(d);
            }
        }
        // A dead end: straight on across country if it can, else back the way it came.
        if (open(cx, cy, f) && rng.getAsDouble() < s.offRoadChance()) return dirAngle(f);
        int back = Math.floorMod(f + 4, 8);
        if (way(cx, cy, back) || open(cx, cy, back)) return dirAngle(back);
        return smallestTurn(cx, cy, f, rng);
    }

    /** A direction off the road into open country (ahead or to a side), or NaN. */
    private float offWay(int cx, int cy, int f, DoubleSupplier rng) {
        int side = rng.getAsDouble() < 0.5 ? 1 : -1;
        for (int j = 0; j < 5; j++) {
            int k = j == 0 ? 0 : (j % 2 == 1 ? side : -side) * ((j + 1) / 2);
            int d = Math.floorMod(f + k, 8);
            int r = map.rank(cx + DX[d], cy + DY[d]);
            if (r > 2 && open(cx, cy, d)) return dirAngle(d);
        }
        return Float.NaN;
    }

    private float chooseBehind(float x, float y, float heading, float pullX, float pullY, float pullWeight,
                               DoubleSupplier rng) {
        return choose(x, y, normalise(heading + (float) Math.PI), pullX, pullY, pullWeight, rng, true);
    }

    private float choose(float x, float y, float heading, float pullX, float pullY, float pullWeight,
                         DoubleSupplier rng, boolean behind) {
        int cx = chunk(x), cy = chunk(y);
        int here = map.rank(cx, cy);
        float pullAngle = pullWeight > 0 ? (float) Math.atan2(pullY - y, pullX - x) : 0f;
        double total = 0, totalRoadOk = 0;
        for (int i = 0; i < FAN.length; i++) {
            w[i] = 0;
            leaveChance[i] = 1;
            float h = heading + FAN[i];
            double dx = Math.cos(h), dy = Math.sin(h);
            double terrain = 0;
            int seen = 0;
            boolean drop = false;
            for (int k = 1; k <= LOOK_AHEAD; k++) {
                int ax = chunk((float) (x + dx * CHUNK * k)), ay = chunk((float) (y + dy * CHUNK * k));
                if (!map.inMap(ax, ay)) { drop = true; break; }
                int r = map.rank(ax, ay);
                if (r == 0) {
                    if (k == 1) drop = true;
                    break;
                }
                if (k == 1) leaveChance[i] = leaving(here, r);
                terrain += 5 - r;
                seen++;
            }
            if (drop || seen == 0) continue;
            double score = MOMENTUM * Math.cos(FAN[i]) + TERRAIN_WEIGHT * terrain / seen;
            if (pullWeight > 0) score += pullWeight * Math.cos(h - pullAngle);
            w[i] = Math.exp(score) * (i == FAN.length - 1 ? BACK_WEIGHT : 1);
            total += w[i];
        }
        if (total == 0) {
            // Nothing in the fan: look the other way round (the gaps at about +-135 degrees), else truly boxed in.
            if (!behind) return chooseBehind(x, y, heading, pullX, pullY, pullWeight, rng);
            return Float.NaN;
        }
        // On a road or trail, a heading that leaves it is only allowed on a roll (trail, or off-road).
        for (int i = 0; i < FAN.length; i++) {
            kept[i] = w[i];
            if (kept[i] > 0 && leaveChance[i] < 1 && rng.getAsDouble() >= leaveChance[i]) kept[i] = 0;
            totalRoadOk += kept[i];
        }
        double[] use = totalRoadOk > 0 ? kept : w;
        double sum = totalRoadOk > 0 ? totalRoadOk : total;
        double pick = rng.getAsDouble() * sum;
        for (int i = 0; i < FAN.length; i++) {
            if (use[i] <= 0) continue;
            pick -= use[i];
            if (pick <= 0) return normalise(heading + FAN[i]);
        }
        for (int i = FAN.length - 1; i >= 0; i--) if (use[i] > 0) return normalise(heading + FAN[i]);
        return Float.NaN;
    }

    /**
     * Walks for {@code minutes} in-game minutes: a new heading is picked whenever the walker enters a new chunk or is
     * blocked. A walker with nowhere to go stays where it is ({@code boxedIn}).
     */
    public Step advance(float x, float y, float heading, float minutes, int members, long walkerId, boolean crawler,
                        float pullX, float pullY, float pullWeight, DoubleSupplier rng) {
        float remaining = speedTilesPerGameMinute(members, s.speedSetting(), walkerId, crawler) * minutes;
        float h = chooseHeading(x, y, heading, pullX, pullY, pullWeight, rng);
        if (Float.isNaN(h)) return new Step(x, y, heading, 0, true);
        int tight = 0;
        int cx = chunk(x), cy = chunk(y);
        int retries = 0;
        while (remaining > 0) {
            float seg = Math.min(SEGMENT, remaining);
            float nx = (float) (x + Math.cos(h) * seg), ny = (float) (y + Math.sin(h) * seg);
            int ncx = chunk(nx), ncy = chunk(ny);
            if (!enterable(cx, cy, ncx, ncy)) {
                float again = chooseHeading(x, y, h, pullX, pullY, pullWeight, rng);
                if (Float.isNaN(again)) return new Step(x, y, h, tight, true);
                // Only the blocked way on, or no luck after a few picks: wait here until the next update.
                if (again == h || ++retries > 4) return new Step(x, y, again, tight, false);
                h = again;
                continue;
            }
            x = nx;
            y = ny;
            remaining -= seg;
            if (ncx != cx || ncy != cy) {
                cx = ncx;
                cy = ncy;
                if (map.tight(cx, cy)) tight++;
                if (remaining > 0) {
                    float next = chooseHeading(x, y, h, pullX, pullY, pullWeight, rng);
                    if (!Float.isNaN(next)) h = next;
                }
            }
        }
        return new Step(x, y, h, tight, false);
    }

    /** The roll needed to go from terrain rank {@code from} to {@code to}: road to trail, or road/trail to open ground. */
    private double leaving(int from, int to) {
        if (from == 1 && to == 2) return s.trailChance();
        if ((from == 1 || from == 2) && to > 2) return s.offRoadChance();
        return 1;
    }

    private boolean enterable(int cx, int cy, int nx, int ny) {
        if (!map.inMap(nx, ny) || map.rank(nx, ny) == 0) return false;
        // Cutting a corner: at least one of the two side chunks must be passable too.
        if (nx != cx && ny != cy) return map.rank(nx, cy) > 0 || map.rank(cx, ny) > 0;
        return true;
    }

    /**
     * Average off-screen speed in tiles per in-game minute: the game's speed class (Random picks one per walker from a
     * stable hash of its id), the walking duty cycle and day length, crawlers slower, and big hordes slowed by
     * crowding (full speed up to 10, half speed at 10,000).
     */
    public float speedTilesPerGameMinute(int members, int speedSetting, long walkerId, boolean crawler) {
        int cls = speedSetting;
        if (cls < SimSettings.SPEED_SPRINTER || cls > SimSettings.SPEED_SHAMBLER) {
            cls = SimSettings.SPEED_SPRINTER + (int) Math.floorMod(mix(walkerId), 3L);
        }
        double tilesPerSecond = cls == SimSettings.SPEED_SPRINTER ? SimSettings.SPRINTER
            : cls == SimSettings.SPEED_FAST_SHAMBLER ? SimSettings.FAST : SimSettings.SHAMBLER;
        double v = tilesPerSecond * SimSettings.DUTY * s.realSecondsPerGameMinute();
        if (crawler) v *= CRAWLER;
        if (members > 10) v *= Math.max(0.5, 1 - 0.5 * (Math.log10(members) - 1) / 3);
        return (float) v;
    }

    static long mix(long v) {
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        return v;
    }

    static int chunk(float tile) { return Math.floorDiv((int) Math.floor(tile), 8); }

    static float normalise(float a) { return (float) Math.atan2(Math.sin(a), Math.cos(a)); }
}
