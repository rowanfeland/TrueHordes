package truehordes.real;

/**
 * What the mod knows about one real zombie it steers (sub-project 6 spec goal 1): the same state a held record carries
 * (heading, horde, follow, home, lure), plus its current order. Main thread only.
 */
public final class Pilot {
    /** Stable id (the zombie's persistent id) for its speed class and rolls. */
    public final long id;
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
    /** The order it was given (NaN none), when, and when it is due again (wall clock ms). */
    public float wpX = Float.NaN, wpY = Float.NaN;
    public long nextOrderMs;
    /** Where it was when last checked for being stuck, and when. */
    public float checkX, checkY;
    public long checkMs;
    /** Since when (wall clock ms) it has been far and out of sight (0 = it is not). */
    public long unseenSinceMs;
    /** In-game hours until which it stands (milling at a lure, a straggler lingering). */
    public double waitUntilHours;
    /** Its column slot in its horde (0 = front); set when the horde's members are counted. */
    public int slot;

    public Pilot(long id) { this.id = id; }

    public boolean lured(double nowHours) { return !Float.isNaN(lureX) && lureUntilHours > nowHours; }

    public void lure(float lx, float ly, double untilHours, float db) {
        lureX = lx;
        lureY = ly;
        lureUntilHours = untilHours;
        lureDb = db;
        dormant = false;
        waitUntilHours = 0;
        nextOrderMs = 0;
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
