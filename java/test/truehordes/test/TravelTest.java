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

    /** Distance between two points in tiles. */
    static double dist(float ax, float ay, float bx, float by) { return Math.hypot(ax - bx, ay - by); }

    static float[] walk(Movement mv, float x, float y, float h, int updates, float minutes, Random rng) {
        for (int i = 0; i < updates; i++) {
            Movement.Step s = mv.advance(x, y, h, minutes, 1, 1, false, 0, 0, 0, rng::nextDouble);
            x = s.x(); y = s.y(); h = s.heading();
        }
        return new float[] {x, y, h};
    }

    /**
     * In-game test 2026-10-15 (real map): walkers on Muldraugh's two-chunk-wide main road went back and forth instead of
     * down it, because the road's other lane counted as a side road. A wide road is one road: no turning across it.
     */
    public static void testWideRoadIsOneRoad() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
            "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO");
        Movement mv = move(m, 0.15);
        int far = 0;
        for (int seed = 0; seed < 40; seed++) {
            Random rng = new Random(seed * 0x9E3779B97F4A7C15L);
            // One-minute updates: decisions must not be re-rolled on every update inside the same chunk.
            float[] e = walk(mv, MovementTest.tile(2), MovementTest.tile(1 + seed % 2), EAST, 600, 1, rng);
            if (e[0] > MovementTest.tile(40)) far++;
        }
        Check.isTrue(far >= 38, "nearly every walker went on down the wide road (" + far + "/40)");
    }

    /** User, 2026-10-15: on a highway a road leading off it is taken only about 2% of the time when passing it. */
    public static void testHighwaySideRoadRarelyTaken() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
            "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
            "OOOOOOOOOOOOOOOOOOOOOOOOROOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOOOOOOROOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOOOOOOROOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOOOOOOROOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO");
        Movement mv = move(m, 0.0);
        int took = 0, n = 1000;
        for (int seed = 0; seed < n; seed++) {
            Random rng = new Random(seed * 0x9E3779B97F4A7C15L);
            float[] e = walk(mv, MovementTest.tile(2), MovementTest.tile(1 + seed % 2), EAST, 500, 1, rng);
            if (e[1] > MovementTest.tile(3)) took++;
        }
        Check.isTrue(took >= 5 && took <= 45, "about 2% turned off the highway (" + took + "/" + n + ")");
    }

    /** A town street junction is still taken fairly often (only highways got the 2%). */
    public static void testStreetJunctionStillTurns() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
            "OOOOOOOOOOOOROOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOROOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOROOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOROOOOOOOOOOOOOOOOOOOOOOOOOOO");
        Movement mv = move(m, 0.0);
        int took = 0, n = 400;
        for (int seed = 0; seed < n; seed++) {
            Random rng = new Random(seed * 0x9E3779B97F4A7C15L);
            float[] e = walk(mv, MovementTest.tile(2), MovementTest.tile(1), EAST, 300, 1, rng);
            if (e[1] > MovementTest.tile(2)) took++;
        }
        Check.isTrue(took > 80 && took < 160, "about 30% took the side street (" + took + "/" + n + ")");
    }

    /** A wide road turning a corner: the walker goes round it, never back the way it came (waves don't turn round). */
    public static void testWideRoadBendNeverTurnsBack() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOOOOOOOOOOOO",
            "RRRRRRRRRRRRRRRRRROO",
            "RRRRRRRRRRRRRRRRRROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO",
            "OOOOOOOOOOOOOOOORROO");
        Movement mv = move(m, 0.15);
        int round = 0;
        for (int seed = 0; seed < 40; seed++) {
            Random rng = new Random(seed * 0x9E3779B97F4A7C15L);
            float[] e = walk(mv, MovementTest.tile(2), MovementTest.tile(1 + seed % 2), EAST, 400, 1, rng);
            if (e[1] > MovementTest.tile(9)) round++;
        }
        Check.isTrue(round >= 38, "went round the bend and down the road (" + round + "/40)");
    }

    /** User, 2026-10-15: no turning back. A road that just ends: the walker carries straight on across country. */
    public static void testDeadEndCarriesOnAcrossCountry() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
            "RRRRRRRRRROOOOOOOOOOOOOOOOOOOO",
            "OOOOOOOOOOOOOOOOOOOOOOOOOOOOOO");
        Movement mv = move(m, 0.0);
        Random rng = new Random(7);
        float[] e = walk(mv, MovementTest.tile(2), MovementTest.tile(1), EAST, 250, 1, rng);
        Check.isTrue(e[0] > MovementTest.tile(14), "carried on past the road's end (" + e[0] + ")");
    }

    /** In-game probe 2026-10-15: a walker heading diagonally clipped a blocked corner chunk and stood there for good. */
    public static void testDiagonalPastABlockedCornerKeepsGoing() {
        MovementTest.TextMap m = new MovementTest.TextMap(
            "OOOOOOOOOO",
            "OOOOOOOOOO",
            "OOOO~OOOOO",
            "OOOOOOOOOO",
            "OOOOOOOOOO");
        Movement mv = move(m, 0.0);
        Random rng = new Random(8);
        // In chunk (4,3) near its top edge, heading north-east: the straight segment enters (4,2), which is water.
        float x = 4 * 8 + 4.35f, y = 3 * 8 + 1.97f, h = (float) (-Math.PI / 4);
        Movement.Step s = mv.advance(x, y, h, 10, 1, 1, false, 0, 0, 0, rng::nextDouble);
        Check.isTrue(dist(s.x(), s.y(), x, y) > 3, "it moved on (" + s.x() + "," + s.y() + ")");
        Check.isTrue(m.rank(Math.floorDiv((int) s.x(), 8), Math.floorDiv((int) s.y(), 8)) > 0, "not into the water");
    }
}
