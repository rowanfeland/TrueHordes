package truehordes.events;

/**
 * Gunshot sizes and firefights (sub-project 5b spec goals 1 and 3). A gunshot's world radius is a rifle's under the
 * user's Louder Gunshots mod (200 x its multiplier) when that mod is active, else vanilla's meta gunshot (600).
 */
public final class Gunfire {
    private Gunfire() {}

    public static final int VANILLA_META_RADIUS = 600, RIFLE_RADIUS = 200;
    /** Seconds between shots of one burst (a few tenths of a second). */
    public static final double SHOT_GAP_MIN = 0.15, SHOT_GAP_MAX = 0.45;
    /** An off-screen A-Life fight: 6 shots plus 4 per casualty, at most 60, over about a minute. */
    public static final int FIGHT_BASE = 6, FIGHT_PER_CASUALTY = 4, FIGHT_MAX = 60;
    public static final double FIGHT_SECONDS = 60;

    /** {@code louderMultiplier} is Louder Gunshots' multiplier, or NaN when that mod is not active. */
    public static int radius(double louderMultiplier) {
        if (Double.isNaN(louderMultiplier) || louderMultiplier <= 0) return VANILLA_META_RADIUS;
        return (int) Math.round(RIFLE_RADIUS * louderMultiplier);
    }

    public static int fightShots(int casualties) {
        return Math.min(FIGHT_MAX, FIGHT_BASE + FIGHT_PER_CASUALTY * Math.max(0, casualties));
    }

    /**
     * Seconds after the start for each of {@code n} shots: a burst a few tenths apart, or (spread) scattered over
     * {@code seconds} in short bursts. Rolls come from {@code u}; times are ascending and start at 0.
     */
    public static double[] times(int n, double seconds, java.util.function.DoubleSupplier u) {
        double[] t = new double[n];
        double now = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                double gap = SHOT_GAP_MIN + u.getAsDouble() * (SHOT_GAP_MAX - SHOT_GAP_MIN);
                // In a long fight, a pause between bursts now and then keeps the whole thing about {@code seconds} long.
                if (seconds > 0 && u.getAsDouble() < 0.25) gap += u.getAsDouble() * 4 * seconds / n;
                now += gap;
            }
            t[i] = now;
        }
        if (seconds > 0 && now > seconds) for (int i = 0; i < n; i++) t[i] *= seconds / now;
        return t;
    }

    /**
     * The player's volume (0..1) for an event of world radius {@code radius} at {@code d} tiles: the same energy
     * falloff as zombies hear, with the player's ear 18 dB keener (as the horde moan), floored so it stays faintly
     * audible across the band.
     */
    public static float volume(float radius, float d) {
        float level = truehordes.sim.SoundModel.levelAt(truehordes.sim.SoundModel.dbFromRadius(radius) + 18f, d);
        return Math.max(0.15f, Math.min(1f, (level - 30f) / 30f));
    }
}
