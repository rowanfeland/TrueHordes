package truehordes.real;

/**
 * What the mod knows about one real zombie it steers (sub-project 6 spec goal 1): the same state a held record carries
 * (heading, horde, follow, home, lure), plus its current order. Main thread only.
 */
public final class Pilot {
    /** Stable id (the zombie's persistent id) for its speed class and rolls. */
    public long id;
    public float x, y;
    public float heading;
    public long hordeId;
    /** Persistent id of the real zombie it follows (0 none). */
    public long followId;
    /** The zombie it last touched (one bump roll per pair). */
    public long bumpedId;
    public float homeX = Float.NaN, homeY = Float.NaN;
    public float lureX = Float.NaN, lureY = Float.NaN;
    public double lureUntilHours;
    public float lureDb;
    /** A building zombie waiting inside (or a test ring horde): not steered until a sound wakes it. */
    public boolean dormant;
    public boolean straggler;
    public boolean crawler;
    /** Vanilla has it (a target, attacking, sitting, lying...); set each steering and crowd pass. It does not lead. */
    public boolean busy;
    /** The order it was given (NaN none), when, and when it is due again (wall clock ms). */
    public float wpX = Float.NaN, wpY = Float.NaN;
    public long nextOrderMs;
    /** Where it was when last checked for being stuck, and when. */
    public float checkX, checkY;
    public long checkMs;
    /** Stuck checks in a row it failed (0: moving); a follower's slot is jittered by it. */
    public int stuck;
    /** In-game hours until which it stands (milling at a lure, a straggler lingering). */
    public double waitUntilHours;
    /** Its column slot in its horde (0 = front); set when the horde's members are counted. */
    public int slot;
    /**
     * Chase memory (user, 2026-10-20): after losing a player it goes to where it last saw them, then on along their
     * heading. Until when, and the far point of the second leg (NaN once it is on it, or with no heading).
     */
    public double chaseUntilHours;
    public float chaseFarX = Float.NaN, chaseFarY = Float.NaN;
    /** While vanilla has it after a player: where it last saw them (NaN none) and the way they were going. */
    public float seenX = Float.NaN, seenY = Float.NaN, seenDx, seenDy;

    /** How far along the player's heading the second leg aims; the chase time ends it long before. */
    public static final float CHASE_FAR = 500f;

    public Pilot(long id) { this.id = id; }

    public boolean lured(double nowHours) { return !Float.isNaN(lureX) && lureUntilHours > nowHours; }

    public void lure(float lx, float ly, double untilHours, float db) {
        lureX = lx;
        lureY = ly;
        lureUntilHours = untilHours;
        lureDb = db;
        chaseUntilHours = 0;
        chaseFarX = Float.NaN;
        dormant = false;
        waitUntilHours = 0;
        nextOrderMs = 0;
    }

    public boolean chasing(double nowHours) { return chaseUntilHours > nowHours; }

    /** Lost the player: to (sx, sy), where it last saw them, then on along (dx, dy) until {@code untilHours}. */
    public void chase(float sx, float sy, float dx, float dy, double untilHours, float db) {
        lure(sx, sy, untilHours, db);
        float len = (float) Math.hypot(dx, dy);
        if (len > 1e-3f) {
            chaseFarX = sx + dx / len * CHASE_FAR;
            chaseFarY = sy + dy / len * CHASE_FAR;
        }
        chaseUntilHours = untilHours;
    }

    /** At the spot: on along the player's heading (once). False with no second leg. */
    public boolean chaseOn() {
        if (Float.isNaN(chaseFarX)) return false;
        float fx = chaseFarX, fy = chaseFarY;
        double until = chaseUntilHours;
        lure(fx, fy, until, lureDb);
        chaseUntilHours = until;
        return true;
    }

    /** Joins {@code o}'s chase (its horde followed it): the same spot, heading and time. */
    public void chaseWith(Pilot o) {
        float fx = o.chaseFarX, fy = o.chaseFarY;
        double until = o.chaseUntilHours;
        lure(o.lureX, o.lureY, until, o.lureDb);
        chaseFarX = fx;
        chaseFarY = fy;
        chaseUntilHours = until;
    }

    /** Spacing between zombies in a real horde's column, in tiles (as the hand-over lays them out). */
    public static final float SPACING = 1.2f;

    /**
     * Slot {@code i} of a column of {@code n} walking behind its leader at (lx, ly) with heading {@code h}: the column
     * is about half the square root of n wide, rows {@code SPACING} apart. Returns {x, y}.
     */
    public static float[] slot(int i, int n, float lx, float ly, float h) {
        int width = Math.max(1, (int) Math.ceil(Math.sqrt(n) / 2));
        int row = i / width, col = i % width;
        float fx = (float) Math.cos(h), fy = (float) Math.sin(h);
        float side = (col - (width - 1) / 2f) * SPACING, back = row * SPACING;
        return new float[] {lx - fx * back - fy * side, ly - fy * back + fx * side};
    }
}
