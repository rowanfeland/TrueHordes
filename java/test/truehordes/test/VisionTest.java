package truehordes.test;

import truehordes.sim.Vision;

public final class VisionTest {
    private VisionTest() {}

    public static void testRadiusFollowsTheGame() {
        Check.near(20, Vision.radius(2, 0, 0, 0), 1e-6, "normal, clear day");
        Check.near(15, Vision.radius(2, 1, 0, 0), 1e-6, "normal, full dark");
        Check.near(10, Vision.radius(3, 0, 0, 0), 1e-6, "poor sight clamps to 10");
        Check.near(20, Vision.radius(1, 0, 0, 1), 1e-6, "eagle in fog: (20-7)*1.75 clamps to 20");
        Check.near(10.5, Vision.radius(2, 0, 1, 1), 1e-6, "rain and fog add: 20-(2.5+7)");
    }

    public static void testConeBands() {
        float[] cos = {1f, 0.7f, 0.5f, 0.3f, 0.1f, -0.1f, -0.3f, -0.5f};
        float[] w = {32f, 16f, 8f, 2f, 0.5f, 0.25f, 0.125f, 0f};
        for (int i = 0; i < cos.length; i++) Check.near(w[i], Vision.coneWeight(cos[i]), 1e-6, "band at cos " + cos[i]);
    }

    public static void testSees() {
        float r = 20;
        Check.isTrue(!Vision.sees(0, 0, 0, 21, 0, 0, r, () -> 0.0), "21 tiles ahead is out of range");
        Check.isTrue(Vision.sees(0, 0, 0, 5, 0, 0, r, () -> 0.99), "5 tiles straight ahead is seen");
        Check.isTrue(!Vision.sees(0, 0, 0, -5, 0, 0, r, () -> 0.0), "directly behind is never seen");
        Check.isTrue(Vision.sees(0, 0, 0, 24, 0, 6, r, () -> 0.99), "a horde circle whose edge is 18 tiles ahead");
        Check.isTrue(Vision.sees(0, 0, 0, -2, 0, 6, r, () -> 0.99), "standing inside a horde's circle");
    }

    public static void testRandomSightPerZombie() {
        Check.equal(1, Vision.perZombieSight(4, n -> 0), "random: lowest roll is eagle");
        Check.equal(3, Vision.perZombieSight(4, n -> n - 1), "random: highest roll is poor");
        Check.equal(2, Vision.perZombieSight(5, n -> 0), "random normal/poor: lowest roll is normal");
        Check.equal(2, Vision.perZombieSight(2, n -> 0), "fixed setting kept");
    }
}
