package truehordes.test;

import java.util.Random;
import truehordes.sim.Movement;
import truehordes.sim.SimSettings;

/**
 * In-game test 2026-10-15: zombies turned at random and circled. They should travel: straight down roads and trails,
 * follow bends, sometimes cut across country in a straight line to the next road, and only turn when blocked.
 */
public final class TravelTest {
    private TravelTest() {}

    static final float EAST = 0f, NORTH = (float) (-Math.PI / 2);

    static Movement move(MovementTest.TextMap m, double offRoad) {
        return new Movement(m, SimSettings.defaults().toBuilder().offRoadChance(offRoad).build());
    }

    static double turn(float a, float b) { return Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b))); }

    public static void testWalksStraightDownARoad() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW",
            "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
            "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW");
        Movement mv = move(m, 0.15);
        Random rng = new Random(1);
        int turns = 0;
        float x = MovementTest.tile(2), y = MovementTest.tile(1), h = EAST;
        for (int i = 0; i < 100; i++) {
            Movement.Step s = mv.advance(x, y, h, 4, 1, 1, false, 0, 0, 0, rng::nextDouble);
            if (turn(s.heading(), h) > 0.3) turns++;
            x = s.x(); y = s.y(); h = s.heading();
            if (x > MovementTest.tile(35)) break;
        }
        Check.isTrue(x > MovementTest.tile(30), "it travelled down the road (" + x + ")");
        Check.isTrue(turns <= 2, "without wandering about (" + turns + " turns)");
    }

    public static void testFollowsARoadAroundABend() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOOOO",
            "RRRRRRRRROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO",
            "OOOOOOOOROOO");
        Movement mv = move(m, 0.0);
        Random rng = new Random(2);
        float x = MovementTest.tile(1), y = MovementTest.tile(1), h = EAST;
        for (int i = 0; i < 60; i++) {
            Movement.Step s = mv.advance(x, y, h, 4, 1, 1, false, 0, 0, 0, rng::nextDouble);
            x = s.x(); y = s.y(); h = s.heading();
        }
        Check.isTrue(y > MovementTest.tile(4), "it went round the bend and down the road (" + x + "," + y + ")");
    }

    public static void testCrossCountryIsAStraightLine() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO");
        Movement mv = move(m, 0.15);
        Random rng = new Random(3);
        float x = MovementTest.tile(1), y = MovementTest.tile(2) + 1, h = 0.1f;
        for (int i = 0; i < 40; i++) {
            Movement.Step s = mv.advance(x, y, h, 4, 1, 1, false, 0, 0, 0, rng::nextDouble);
            Check.isTrue(turn(s.heading(), 0.1f) < 0.05, "keeps its line across open ground");
            x = s.x(); y = s.y(); h = s.heading();
            if (x > MovementTest.tile(30)) break;
        }
    }

    public static void testCrossCountryWalkerTakesTheRoadItMeets() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOO",
            "RRRRRRRRRRRRRRRRRRRR",
            "OOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOO");
        Movement mv = move(m, 0.0);
        int onRoad = 0;
        for (int seed = 0; seed < 50; seed++) {
            Random rng = new Random(seed);
            float x = MovementTest.tile(10), y = MovementTest.tile(6), h = NORTH;
            for (int i = 0; i < 30; i++) {
                Movement.Step s = mv.advance(x, y, h, 4, 1, 1, false, 0, 0, 0, rng::nextDouble);
                x = s.x(); y = s.y(); h = s.heading();
            }
            if (m.rank(Math.floorDiv((int) x, 8), Math.floorDiv((int) y, 8)) == 1) onRoad++;
        }
        Check.isTrue(onRoad >= 35, "most walkers crossing a field turn onto the road they meet (" + onRoad + "/50)");
    }

    public static void testBlockedOffRoadTurnsTheSmallestWay() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOO~~",
            "OOOOOOOOO~~",
            "OOOOOOOOO~~",
            "OOOOOOOOO~~",
            "OOOOOOOOO~~");
        Movement mv = move(m, 0.0);
        Random rng = new Random(5);
        float h = mv.chooseHeading(MovementTest.tile(8), MovementTest.tile(2), EAST, 0, 0, 0, rng::nextDouble);
        Check.isTrue(turn(h, EAST) > 0.5 && turn(h, EAST) < 2.0, "turns aside from the water, not back (" + h + ")");
    }
}
