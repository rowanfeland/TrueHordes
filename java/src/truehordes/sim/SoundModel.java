package truehordes.sim;

/**
 * Energy falloff for every zombie (sub-project 5a spec section 2, design D20). A sound's loudness comes from the game's
 * radius so that the circle's edge is where it fades to the 30 dB threshold; it loses 6 dB per doubling of distance.
 * A lone zombie hears it with a chance that is certain inside the inner share of the circle and fades to the edge
 * floor at the edge; a horde sends that chance times the part of it not drowned out by its own moaning, and the whole
 * horde goes once half or more hear. No distance cap: only the sound's energy limits it.
 */
public final class SoundModel {
    public static final float THRESHOLD_DB = 30f;
    /** Margin (dB above the threshold) at which a horde's own moaning no longer drowns the sound. */
    static final float DROWN_GONE_DB = 20f;
    /** How much more of a horde its moaning drowns per tenfold size (1,000 zombies: half). */
    static final double DROWN = 0.25;
    /** A walker heading for a sound switches only for one at least this much louder where it stands. */
    public static final float SWITCH_DB = 6f;

    private final double edge;
    private final double fullMarginDb;

    public SoundModel(SimSettings s) {
        edge = s.soundEdgeChance();
        fullMarginDb = 20 * Math.log10(1 / s.soundCertainShare());
    }

    /** Source loudness of a sound the game gives as a radius in tiles. */
    public static float dbFromRadius(float radius) {
        return THRESHOLD_DB + 20f * (float) Math.log10(Math.max(1f, radius));
    }

    public static float levelAt(float sourceDb, float distance) {
        return sourceDb - 20f * (float) Math.log10(Math.max(1f, distance));
    }

    /** Chance a lone zombie hears a sound arriving at {@code level} dB. */
    public double loneChance(float level) {
        double m = level - THRESHOLD_DB;
        if (m < 0) return 0;
        return edge + (1 - edge) * Math.min(1, m / fullMarginDb);
    }

    /** Share of a horde of {@code n} that goes for a sound arriving at {@code level} dB (hive mind applied). */
    public double hordeShare(float level, int n) {
        double p = loneChance(level);
        if (p == 0) return 0;
        double b = Math.max(0.25, Math.min(1, 1 - DROWN * Math.log10(Math.max(10, n) / 10.0)));
        double h = b + (1 - b) * Math.max(0, Math.min(1, (level - THRESHOLD_DB) / DROWN_GONE_DB));
        return hive(p * h);
    }

    /** Half or more of a horde hearing a sound takes the rest with it. */
    public static double hive(double share) { return share >= 0.5 ? 1 : share; }
}
