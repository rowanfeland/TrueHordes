package truehordes.test;

import java.util.Random;
import truehordes.population.Formula;
import truehordes.population.PopulationSettings;

public final class FormulaTest {
    private static PopulationSettings.Builder base() {
        return PopulationSettings.vanillaDefaults().toBuilder().multiplier(1f).minPerChunk(0f).maxPerChunk(12f);
    }

    public static void testZeroIntensityIsZero() {
        PopulationSettings s = base().minPerChunk(3f).build();
        Check.equal(0, Formula.chunkTarget(0, s, () -> 0), "intensity 0 gives 0 even with a minimum");
        Check.equal(0, Formula.chunkTarget(-1, s, () -> 0), "no data gives 0");
    }

    public static void testFullIntensityReachesMax() {
        Check.equal(12, Formula.chunkTarget(255, base().build(), () -> 99), "255 at multiplier 1 is the max");
    }

    public static void testBelowOneIsAChance() {
        PopulationSettings s = base().maxPerChunk(1f).build(); // value = intensity / 255
        int intensity = 77; // 0.302
        Check.equal(1, Formula.chunkTarget(intensity, s, () -> 10), "roll 10 < 30.2");
        Check.equal(0, Formula.chunkTarget(intensity, s, () -> 50), "roll 50 >= 30.2");
    }

    public static void testOneAndABitIsFiftyFifty() {
        PopulationSettings s = base().maxPerChunk(2f).build(); // value = 2 * intensity / 255
        int intensity = 179; // 1.40
        Check.equal(1, Formula.chunkTarget(intensity, s, () -> 49), "roll under 50 gives 1");
        Check.equal(0, Formula.chunkTarget(intensity, s, () -> 50), "roll 50 gives 0");
        Check.equal(5, Formula.chunkTarget(120, base().build(), () -> 0), "otherwise the whole part (5.64)");
    }

    public static void testUniformIgnoresIntensity() {
        PopulationSettings s = base().uniformDistribution(true).uniformPerChunk(255f).build();
        Check.equal(Formula.chunkTarget(255, base().build(), () -> 0), Formula.chunkTarget(3, s, () -> 0), "uniform value used");
    }

    public static void testPopulationMultiplierScalesTargets() {
        PopulationSettings one = base().maxPerChunk(255f).build();
        PopulationSettings two = base().maxPerChunk(255f).multiplier(2f).build();
        Check.near(10.0, Formula.chunkValue(10, one), 1e-4, "multiplier 1: value = intensity (min 0, max 255)");
        Check.near(20.0, Formula.chunkValue(10, two), 1e-4, "multiplier 2 doubles it");
        Check.near(0.65 * 40, Formula.chunkValue(40, base().maxPerChunk(255f).multiplier(0.65f).build()), 1e-4, "vanilla default 0.65");
    }

    public static void testCurve() {
        PopulationSettings s = base().startMultiplier(1f).peakMultiplier(1.5f).peakDay(28).build();
        Check.equal(1000, Formula.curve(1000, 0, s), "day 0 is the start multiplier");
        Check.equal(1250, Formula.curve(1000, 14, s), "half way to the peak day");
        Check.equal(1500, Formula.curve(1000, 28, s), "peak day");
        Check.equal(1500, Formula.curve(1000, 400, s), "after the peak day");
    }

    public static void testPeakMultiplierAndDayShapeCurve() {
        PopulationSettings low = base().startMultiplier(1f).peakMultiplier(1.5f).peakDay(28).build();
        PopulationSettings high = base().startMultiplier(1f).peakMultiplier(3f).peakDay(28).build();
        PopulationSettings late = base().startMultiplier(1f).peakMultiplier(1.5f).peakDay(56).build();
        Check.isTrue(Formula.curve(1000, 10, high) > Formula.curve(1000, 10, low), "a higher peak raises the curve");
        Check.equal(3000, Formula.curve(1000, 30, high), "peak 3 after the peak day");
        Check.equal(1250, Formula.curve(1000, 28, late), "a later peak day moves the midpoint");
        Check.equal(2000, Formula.curve(1000, 0, base().startMultiplier(2f).build()), "start multiplier at day 0");
    }

    public static void testDistributeKeepsTotalAndRespectsTargets() {
        int[] targets = {4, 0, 2, 0, 6};
        Random rnd = new Random(7);
        int[] out = Formula.distribute(targets, 18, rnd::nextInt);
        int sum = 0;
        for (int i = 0; i < out.length; i++) {
            sum += out[i];
            if (targets[i] == 0) Check.equal(0, out[i], "no zombies where the target is 0 (chunk " + i + ")");
        }
        Check.equal(18, sum, "total kept");
        int[] grow = Formula.distribute(new int[] {1, 1, 1, 0}, 6, rnd::nextInt);
        for (int i = 0; i < 3; i++) Check.isTrue(grow[i] >= 1, "at least 1 when N >= T");
        int[] shrink = Formula.distribute(new int[] {1, 1, 1, 1}, 2, rnd::nextInt);
        Check.equal(2, shrink[0] + shrink[1] + shrink[2] + shrink[3], "shrinking keeps the total");
        Check.equal(0, Formula.distribute(new int[] {0, 0}, 5, rnd::nextInt)[0], "no targets, nothing placed");
    }

    public static void testRespawnGroup() {
        PopulationSettings s = base().respawnHours(72f).respawnMultiplier(0.1f).build();
        Check.equal(0, Formula.respawnGroup(1000, 9, s), "deficit under 10");
        Check.equal(0, Formula.respawnGroup(1000, 50, base().respawnHours(0f).build()), "respawn off");
        Check.equal(10, Formula.respawnGroup(1000, 50, s), "small amount: at least 10");
        Check.equal(15, Formula.respawnGroup(100000, 15, base().respawnHours(72f).respawnMultiplier(1f).build()), "capped by the deficit");
        Check.equal(27, Formula.respawnGroup(100000, 500, base().respawnHours(72f).respawnMultiplier(1f).build()), "amount / 36");
    }
}
