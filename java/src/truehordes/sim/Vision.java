package truehordes.sim;

import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;

/**
 * The game's zombie sight (IsoZombie.updateVisionRadius / getVisionRadiusAdjusted and the spotting angle bands,
 * 42.21), used off screen. A real zombie's spot chance is multiplied by a weight that depends on the angle between its
 * facing and the target; off screen the chance of seeing per check is that weight over the best weight (32).
 */
public final class Vision {
    public static final float BASE_RADIUS = 20f;
    public static final float MIN_RADIUS = 10f;
    public static final float MAX_RADIUS = 20f;
    private static final float DARK_PENALTY = 5f;
    private static final float RAIN_PENALTY = 2.5f;
    private static final float FOG_PENALTY = 7f;
    private static final float BEST_WEIGHT = 32f;

    private Vision() {}

    /** Vision radius in tiles for a sight setting (1 eagle, 2 normal, 3 poor) and 0..1 darkness, rain and fog. */
    public static float radius(int sight, float dark01, float rain01, float fog01) {
        float r = BASE_RADIUS - Math.max(dark01 * DARK_PENALTY, rain01 * RAIN_PENALTY + fog01 * FOG_PENALTY);
        if (sight == 1) r *= 1.75f;
        else if (sight == 3) r *= 0.35f;
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r));
    }

    /** The game's spotting weight for the cosine of the angle between facing and target. */
    public static float coneWeight(float cos) {
        if (cos < -0.4f) return 0f;
        if (cos < -0.2f) return 1f / 8f;
        if (cos < 0f) return 1f / 4f;
        if (cos < 0.2f) return 1f / 2f;
        if (cos <= 0.4f) return 2f;
        if (cos <= 0.6f) return 8f;
        if (cos <= 0.8f) return 16f;
        return 32f;
    }

    /**
     * Whether a walker at (fromX, fromY) facing {@code heading} sees a target circle (radius {@code toRadius}; 0 for a
     * single zombie): the circle's nearest point is within {@code radius}, then a cone roll. Inside the circle = seen.
     */
    public static boolean sees(float fromX, float fromY, float heading, float toX, float toY, float toRadius,
                               float radius, DoubleSupplier rng) {
        float dx = toX - fromX, dy = toY - fromY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist <= toRadius) return true;
        if (dist - toRadius > radius) return false;
        float cos = (float) ((dx * Math.cos(heading) + dy * Math.sin(heading)) / dist);
        float w = coneWeight(cos);
        return w > 0 && rng.getAsDouble() < w / BEST_WEIGHT;
    }

    /** The game's per-zombie sight for the Random settings (4: any of 1..3; 5: 2..3); {@code rand.applyAsInt(n)} is 0..n-1. */
    public static int perZombieSight(int setting, IntUnaryOperator rand) {
        if (setting == 4) return rand.applyAsInt(3) + 1;
        if (setting == 5) return rand.applyAsInt(2) + 2;
        return setting;
    }
}
