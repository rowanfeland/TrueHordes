package truehordes.test;

import truehordes.sim.SimSettings;
import truehordes.sim.SoundModel;

/** Sub-project 5a spec section 2: the energy-falloff model and the user's reference table (2026-10-16). */
public final class SoundModelTest {
    private SoundModelTest() {}

    static final SoundModel M = new SoundModel(SimSettings.defaults());

    /** Share that goes (hive mind applied) for an 850-tile gunshot at distance d; n = 1 is a lone zombie. */
    static double goes(int n, float d) {
        float level = SoundModel.levelAt(SoundModel.dbFromRadius(850), d);
        return n == 1 ? M.loneChance(level) : M.hordeShare(level, n);
    }

    static void row(float d, double lone, double h100, double h1000, double h10000) {
        double[] want = {lone, h100, h1000, h10000};
        int[] sizes = {1, 100, 1000, 10000};
        for (int i = 0; i < 4; i++) {
            double got = goes(sizes[i], d) * 100;
            Check.isTrue(Math.abs(got - want[i]) <= 2, "at " + d + " tiles, " + sizes[i] + ": " + got + "% (table "
                + want[i] + "%)");
        }
    }

    public static void testReferenceTable() {
        row(50, 100, 100, 100, 100);
        row(100, 100, 100, 100, 100);
        row(213, 100, 100, 100, 100);
        row(425, 70, 100, 46, 33);
        row(640, 52, 40, 29, 17);
        row(800, 43, 33, 22, 12);
        row(851, 0, 0, 0, 0);
        row(3000, 0, 0, 0, 0);
    }

    public static void testReachEqualsTheGameRadius() {
        float db = SoundModel.dbFromRadius(300);
        Check.isTrue(M.loneChance(SoundModel.levelAt(db, 299)) > 0, "heard just inside the circle");
        Check.isTrue(M.loneChance(SoundModel.levelAt(db, 301)) == 0, "not heard outside it");
    }

    public static void testEdgeFloorAndCertainShareAreOptions() {
        SoundModel m = new SoundModel(SimSettings.defaults().toBuilder().soundEdgeChance(0.1).soundCertainShare(0.5)
            .build());
        float db = SoundModel.dbFromRadius(1000);
        Check.isTrue(Math.abs(m.loneChance(SoundModel.levelAt(db, 999)) - 0.1) < 0.01, "edge floor 10%");
        Check.isTrue(m.loneChance(SoundModel.levelAt(db, 499)) == 1, "everyone inside half the radius");
        Check.isTrue(m.loneChance(SoundModel.levelAt(db, 700)) < 1, "fading beyond it");
    }

    /** No distance cap: a sound big enough to cross the map is heard at the far side. */
    public static void testNoDistanceCap() {
        float db = SoundModel.dbFromRadius(20000);
        Check.isTrue(M.loneChance(SoundModel.levelAt(db, 15000)) > 0.4, "heard 15,000 tiles away");
    }

    public static void testHiveMindAtHalf() {
        Check.isTrue(SoundModel.hive(0.5) == 1 && SoundModel.hive(0.49) == 0.49, "half or more: the whole horde");
    }
}
