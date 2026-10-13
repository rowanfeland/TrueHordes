package truehordes.population;

import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Vanilla's population formulas, as read from PZPopMan64.dll (42.21); see the sub-project 3 spec, section 5. Float
 * arithmetic matches the DLL's single-precision steps.
 */
public final class Formula {
    private Formula() {}

    /** A chunk's population value before rounding (0x18000c200); 0 for no data or zero intensity. */
    public static float chunkValue(int intensity, PopulationSettings s) {
        if (intensity < 0) return 0f;
        float base = s.uniformDistribution ? s.uniformPerChunk : intensity;
        if (base == 0f) return 0f;
        return s.minPerChunk + base * s.multiplier / 255f * (s.maxPerChunk - s.minPerChunk);
    }

    /** A chunk's target (0x18000c0c0): a chance below 1, a coin flip for 1.x, otherwise the whole part. */
    public static int chunkTarget(int intensity, PopulationSettings s, IntSupplier rand100) {
        float v = chunkValue(intensity, s);
        if (v <= 0f) return 0;
        if (v < 1f) return v * 100f > rand100.getAsInt() ? 1 : 0;
        int n = (int) v;
        if (n == 1) return rand100.getAsInt() < 50 ? 1 : 0;
        return n;
    }

    /** A cell's population for its summed chunk targets t on this day (0x18000b470): start to peak by the peak day. */
    public static int curve(int t, double day, PopulationSettings s) {
        float d = (float) day;
        float total = t;
        if (s.peakDay > d) {
            float start = total * s.startMultiplier;
            return (int) (start + (total * s.peakMultiplier - start) * (d / s.peakDay));
        }
        return (int) (total * s.peakMultiplier);
    }

    /**
     * Spreads n zombies over chunks in proportion to their targets (0x180008b30): trunc(t / T * n) each, at least 1 when
     * n >= T, the remainder to random chunks that have a target. randBelow(k) returns 0..k-1.
     */
    public static int[] distribute(int[] targets, int n, IntUnaryOperator randBelow) {
        int[] out = new int[targets.length];
        int total = 0, withTarget = 0;
        for (int t : targets) {
            if (t > 0) {
                total += t;
                withTarget++;
            }
        }
        if (total == 0 || n <= 0) return out;
        int[] targeted = new int[withTarget];
        int placed = 0, k = 0;
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] <= 0) continue;
            targeted[k++] = i;
            int c = (int) ((float) targets[i] / total * n);
            if (n >= total && c < 1) c = 1;
            out[i] = c;
            placed += c;
        }
        for (int r = n - placed; r > 0; r--) out[targeted[randBelow.applyAsInt(withTarget)]]++;
        return out;
    }

    /** One respawn group (0x180020e70): 0 if respawn is off or the deficit is under 10, else clamp(amount / 36, 10, deficit). */
    public static int respawnGroup(int curveTarget, int deficit, PopulationSettings s) {
        if (s.respawnHours <= 0f || deficit < 10) return 0;
        int amount = (int) (curveTarget * s.respawnMultiplier / 100f);
        return Math.min(Math.max(amount / 36, 10), deficit);
    }
}
