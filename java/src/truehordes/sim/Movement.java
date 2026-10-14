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
    private static final int LOOK_AHEAD = 3;
    private static final float CHUNK = 8f;
    /** Longest straight piece walked before the next map check, in tiles (half a chunk). */
    private static final float SEGMENT = 4f;
    private static final float CRAWLER = 0.35f;
    /** Chance per in-game minute that a walking walker starts a short pause. */
    private static final double PAUSE_PER_MINUTE = 1.0 / 120.0;

    private final MapView map;
    private final SimSettings s;
    // Scratch for chooseHeading (one simulation thread; avoids garbage on every pick).
    private final double[] w = new double[FAN.length];
    private final double[] kept = new double[FAN.length];
    private final boolean[] offRoad = new boolean[FAN.length];

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
        return choose(x, y, heading, pullX, pullY, pullWeight, rng, false);
    }

    private float chooseBehind(float x, float y, float heading, float pullX, float pullY, float pullWeight,
                               DoubleSupplier rng) {
        return choose(x, y, normalise(heading + (float) Math.PI), pullX, pullY, pullWeight, rng, true);
    }

    private float choose(float x, float y, float heading, float pullX, float pullY, float pullWeight,
                         DoubleSupplier rng, boolean behind) {
        int cx = chunk(x), cy = chunk(y);
        boolean onRoad = map.rank(cx, cy) == 1;
        float pullAngle = pullWeight > 0 ? (float) Math.atan2(pullY - y, pullX - x) : 0f;
        double total = 0, totalRoadOk = 0;
        for (int i = 0; i < FAN.length; i++) {
            w[i] = 0;
            offRoad[i] = false;
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
                if (k == 1) offRoad[i] = onRoad && r != 1;
                terrain += 5 - r;
                seen++;
            }
            if (drop || seen == 0) continue;
            double score = MOMENTUM * Math.cos(FAN[i]) + terrain / seen;
            if (pullWeight > 0) score += pullWeight * Math.cos(h - pullAngle);
            w[i] = Math.exp(score) * (i == FAN.length - 1 ? BACK_WEIGHT : 1);
            total += w[i];
        }
        if (total == 0) {
            // Nothing in the fan: look the other way round (the gaps at about +-135 degrees), else truly boxed in.
            if (!behind) return chooseBehind(x, y, heading, pullX, pullY, pullWeight, rng);
            return Float.NaN;
        }
        // On a road, a heading that leaves it is only allowed on an off-road roll.
        for (int i = 0; i < FAN.length; i++) {
            kept[i] = w[i];
            if (kept[i] > 0 && offRoad[i] && rng.getAsDouble() >= s.offRoadChance()) kept[i] = 0;
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

    /** Length in in-game minutes of a pause that starts during {@code minutes} of walking, or 0 (2 to 10 minutes). */
    public static float rollPause(float minutes, DoubleSupplier rng) {
        double p = 1 - Math.pow(1 - PAUSE_PER_MINUTE, minutes);
        if (rng.getAsDouble() >= p) return 0f;
        return (float) (2 + 8 * rng.getAsDouble());
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
