package truehordes.test;

import truehordes.sim.SimSettings;
import truehordes.sim.SoundModel;
import truehordes.sound.RealHearing;

/** Sub-project 5a spec section 5: how a real zombie near a player scores a sound, without the game. */
public final class RealHearingTest {
    private RealHearingTest() {}

    static final RealHearing H = new RealHearing(new SoundModel(SimSettings.defaults()));

    /** At the edge of an 850-tile shot about 43% of real zombies hear it; inside the inner quarter all do. */
    public static void testRealZombiesUseTheEnergyCurve() {
        int heard = 0;
        java.util.Random rng = new java.util.Random(3);
        for (int i = 0; i < 2000; i++) {
            if (H.rollHeard(SoundModel.levelAt(SoundModel.dbFromRadius(850), 800), rng.nextDouble())) heard++;
        }
        Check.isTrue(heard > 780 && heard < 940, "about 43% at 800 tiles (" + heard + "/2000)");
        Check.isTrue(H.rollHeard(SoundModel.levelAt(SoundModel.dbFromRadius(850), 150), 0.999), "certain inside");
    }

    /** Walls: another room costs about 0.8 dB, inside to outside about 2.3 dB (vanilla's 1.2 and 1.4 factors). */
    public static void testRoomLoss() {
        Check.near(0, RealHearing.roomLossDb(false, false), 1e-6, "same room");
        Check.near(0.79, RealHearing.roomLossDb(true, false), 0.02, "another room");
        Check.near(2.25, RealHearing.roomLossDb(true, true), 0.02, "inside to outside");
    }

    /** Score is sound pressure: twice the score is 6 dB, so vanilla's "twice as strong" switch is the 6 dB rule. */
    public static void testScoreIsPressure() {
        float a = RealHearing.score(50f), b = RealHearing.score(56f);
        Check.isTrue(b > a * 1.99 && b < a * 2.01, "6 dB louder scores twice as much (" + a + ", " + b + ")");
    }

    /** One roll per sound per zombie: remembered, so a sound not heard stays not heard. */
    public static void testRollsAreRemembered() {
        RealHearing.Memory m = new RealHearing.Memory();
        Check.isTrue(m.get(5) == null, "unknown");
        m.put(5, false);
        m.put(6, true);
        Check.isTrue(Boolean.FALSE.equals(m.get(5)) && Boolean.TRUE.equals(m.get(6)), "remembered");
        for (int i = 100; i < 100 + RealHearing.Memory.SIZE; i++) m.put(i, true);
        Check.isTrue(m.get(5) == null, "old sounds drop out of the small memory");
    }
}
