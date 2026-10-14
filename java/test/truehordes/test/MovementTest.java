package truehordes.test;

import java.util.Random;
import truehordes.sim.MapView;
import truehordes.sim.Movement;
import truehordes.sim.SimSettings;

public final class MovementTest {
    private MovementTest() {}

    /** A map from text rows, one char per chunk: R road, T trail, F farm, O open, W forest, B building, ~ water, # void. */
    static final class TextMap implements MapView {
        final String[] rows;
        TextMap(String... rows) { this.rows = rows; }
        char at(int cx, int cy) {
            if (cy < 0 || cy >= rows.length || cx < 0 || cx >= rows[cy].length()) return '#';
            return rows[cy].charAt(cx);
        }
        @Override public int rank(int cx, int cy) {
            switch (at(cx, cy)) {
                case 'R': return 1;
                case 'T': return 2;
                case 'F': return 3;
                case 'O': case 'W': return 4;
                default: return 0;
            }
        }
        @Override public boolean inMap(int cx, int cy) { return at(cx, cy) != '#'; }
        @Override public boolean tight(int cx, int cy) {
            if (rank(cx, cy) == 0) return false;
            int b = 0;
            if (at(cx + 1, cy) == 'B') b++;
            if (at(cx - 1, cy) == 'B') b++;
            if (at(cx, cy + 1) == 'B') b++;
            if (at(cx, cy - 1) == 'B') b++;
            return b >= 2;
        }
    }

    static float tile(int chunk) { return chunk * 8 + 4; }

    static Movement move(MapView m, double offRoad) {
        return new Movement(m, SimSettings.defaults().toBuilder().offRoadChance(offRoad).build());
    }

    static final float NORTH = (float) (-Math.PI / 2), EAST = 0f, WEST = (float) Math.PI;

    static boolean near(float a, float b) {
        double d = Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b)));
        return d < 0.2;
    }

    public static void testPrefersRoadOverForest() {
        // Walker on a north-south road reaching an east-west road with forest ahead (a T junction).
        TextMap m = new TextMap(
            "WWWWWWWWW",
            "WWWWWWWWW",
            "WWWWWWWWW",
            "RRRRRRRRR",
            "WWWWRWWWW",
            "WWWWRWWWW",
            "WWWWRWWWW");
        Movement mv = move(m, 0.10);
        Random rng = new Random(1);
        int road = 0, n = 10000;
        for (int i = 0; i < n; i++) {
            float h = mv.chooseHeading(tile(4), tile(3), NORTH, 0, 0, 0, rng::nextDouble);
            if (near(h, EAST) || near(h, WEST)) road++;
        }
        Check.isTrue(road >= n * 0.8, "turns onto the road at a T junction (" + road + ")");
    }

    static int[] leftRight(char left, char right) {
        TextMap m = new TextMap(
            "OOOOOOOOO",
            "OOOOOOOOO",
            "OOOOOOOOO",
            String.valueOf(left).repeat(4) + "O" + String.valueOf(right).repeat(4),
            "OOOOOOOOO",
            "OOOOOOOOO",
            "OOOOOOOOO");
        Movement mv = move(m, 1.0);
        Random rng = new Random(2);
        int l = 0, r = 0;
        for (int i = 0; i < 20000; i++) {
            float h = mv.chooseHeading(tile(4), tile(3), NORTH, 0, 0, 0, rng::nextDouble);
            if (near(h, WEST)) l++;
            if (near(h, EAST)) r++;
        }
        return new int[] {l, r};
    }

    public static void testRankOrder() {
        int[] a = leftRight('R', 'T');
        Check.isTrue(a[0] > a[1], "road over trail " + a[0] + " vs " + a[1]);
        a = leftRight('T', 'F');
        Check.isTrue(a[0] > a[1], "trail over farmland " + a[0] + " vs " + a[1]);
        a = leftRight('F', 'O');
        Check.isTrue(a[0] > a[1], "farmland over open " + a[0] + " vs " + a[1]);
        a = leftRight('O', 'W');
        Check.isTrue(Math.abs(a[0] - a[1]) < 0.15 * Math.max(a[0], a[1]), "open equals forest " + a[0] + " vs " + a[1]);
    }

    public static void testNeverEntersWater() {
        TextMap m = new TextMap(
            "~~~~~~~~~",
            "~~~~~~~~~",
            "~~~~O~~~~",
            "~~~~O~~~~",
            "~~~~O~~~~",
            "~~~~O~~~~");
        Movement mv = move(m, 0.1);
        Random rng = new Random(3);
        float x = tile(4), y = tile(5), h = NORTH;
        for (int i = 0; i < 400; i++) {
            Movement.Step s = mv.advance(x, y, h, 5, 1, 1, false, 0, 0, 0, rng::nextDouble);
            x = s.x(); y = s.y(); h = s.heading();
            Check.isTrue(m.rank(Math.floorDiv((int) x, 8), Math.floorDiv((int) y, 8)) > 0, "never in water");
        }
    }

    public static void testTurnsBackAtMapEdgeDeadEnd() {
        // A road running east off the map's edge, forest around it.
        TextMap m = new TextMap(
            "WWWWWWWWWW",
            "WWWWWWWWWW",
            "RRRRRRRRRR",
            "WWWWWWWWWW",
            "WWWWWWWWWW");
        Movement mv = move(m, 0.0);
        Random rng = new Random(4);
        float x = tile(6), y = tile(2), h = EAST;
        boolean turned = false;
        for (int i = 0; i < 200; i++) {
            Movement.Step s = mv.advance(x, y, h, 2, 1, 1, false, 0, 0, 0, rng::nextDouble);
            x = s.x(); y = s.y(); h = s.heading();
            Check.isTrue(m.inMap(Math.floorDiv((int) x, 8), Math.floorDiv((int) y, 8)), "never leaves the map");
            if (Math.cos(h) < -0.5) turned = true;
        }
        Check.isTrue(turned, "turns back along the road at the dead end");
    }

    public static void testOffRoadChanceRespected() {
        TextMap m = new TextMap(
            "WWWWWWWWW",
            "WWWWWWWWW",
            "RRRRRRRRR",
            "WWWWWWWWW",
            "WWWWWWWWW");
        Random rng = new Random(5);
        Movement never = move(m, 0.0);
        for (int i = 0; i < 5000; i++) {
            float h = never.chooseHeading(tile(4), tile(2), EAST, 0, 0, 0, rng::nextDouble);
            Check.isTrue(Math.abs(Math.sin(h)) < 0.2, "never leaves the road with chance 0");
        }
        Movement always = move(m, 1.0);
        int off = 0;
        for (int i = 0; i < 5000; i++) {
            float h = always.chooseHeading(tile(4), tile(2), EAST, 0, 0, 0, rng::nextDouble);
            if (Math.abs(Math.sin(h)) > 0.5) off++;
        }
        Check.isTrue(off > 50, "leaves the road sometimes with chance 1 (" + off + ")");
    }

    public static void testCrowdingSlowsBigHordes() {
        Movement mv = move(new TextMap("O"), 0.1);
        float one = mv.speedTilesPerGameMinute(1, SimSettings.SPEED_SHAMBLER, 1, false);
        Check.near(one, mv.speedTilesPerGameMinute(10, SimSettings.SPEED_SHAMBLER, 1, false), 1e-6, "10 at full speed");
        Check.near(one * 0.5, mv.speedTilesPerGameMinute(10000, SimSettings.SPEED_SHAMBLER, 1, false), one * 0.02,
            "10,000 at half speed");
        Check.near(SimSettings.SHAMBLER * SimSettings.DUTY * 2.5, one, 1e-6, "shambler tiles per in-game minute");
        Check.isTrue(mv.speedTilesPerGameMinute(1, SimSettings.SPEED_SHAMBLER, 1, true) < one * 0.5, "crawlers are slow");
    }

    public static void testBoxedInWalkerPausesInsteadOfSpinning() {
        TextMap m = new TextMap(
            "~~~",
            "~O~",
            "~~~");
        Movement mv = move(m, 0.1);
        Random rng = new Random(6);
        Movement.Step s = mv.advance(tile(1), tile(1), EAST, 30, 1, 1, false, 0, 0, 0, rng::nextDouble);
        Check.isTrue(s.boxedIn(), "boxed in");
        Check.near(tile(1), s.x(), 1e-6, "x unchanged");
        Check.near(tile(1), s.y(), 1e-6, "y unchanged");
        Check.near(EAST, s.heading(), 1e-6, "heading kept");
        Check.isTrue(Float.isNaN(mv.chooseHeading(tile(1), tile(1), EAST, 0, 0, 0, rng::nextDouble)), "no heading");
    }

    /** Final review I4: the only way out is behind and to the side (about 135 degrees): the walker must find it. */
    public static void testFindsAWayOutBehindTheFan() {
        TextMap m = new TextMap(
            "~~~~~~~",
            "~~~O~~~",
            "~~O~~~~",
            "~O~~~~~",
            "~~~~~~~");
        // At (3,1) heading east: east, NE/SE, N/S and west are water; the only way on is south-west.
        Movement mv = move(m, 0.1);
        Random rng = new Random(9);
        float h = mv.chooseHeading(tile(3), tile(1), EAST, 0, 0, 0, rng::nextDouble);
        Check.isTrue(!Float.isNaN(h), "a way out is found");
        Check.isTrue(Math.cos(h) < 0 && Math.sin(h) > 0, "south-west");
    }

    public static void testTightChunksCounted() {
        TextMap alley = new TextMap(
            "BBBBBBBBB",
            "RRRRRRRRR",
            "BBBBBBBBB");
        Movement mv = move(alley, 0.0);
        Random rng = new Random(7);
        Movement.Step s = mv.advance(tile(0), tile(1), EAST, 40, 1, 1, false, 0, 0, 0, rng::nextDouble);
        Check.isTrue(s.tightChunks() > 0, "alley between buildings is a tight spot");
        TextMap open = new TextMap(
            "OOOOOOOOO",
            "RRRRRRRRR",
            "OOOOOOOOO");
        Movement.Step o = move(open, 0.0).advance(tile(0), tile(1), EAST, 40, 1, 1, false, 0, 0, 0, rng::nextDouble);
        Check.equal(0, o.tightChunks(), "open road has no tight spots");
    }

    public static void testPullBendsHeading() {
        TextMap m = new TextMap("OOOOOOOOOOOO", "OOOOOOOOOOOO", "OOOOOOOOOOOO", "OOOOOOOOOOOO", "OOOOOOOOOOOO");
        Movement mv = move(m, 0.1);
        Random rng = new Random(8);
        int east = 0;
        for (int i = 0; i < 4000; i++) {
            float h = mv.chooseHeading(tile(4), tile(2), NORTH, tile(10), tile(2), 3f, rng::nextDouble);
            if (Math.cos(h) > 0.6) east++;
        }
        Check.isTrue(east > 2000, "a strong pull to the east wins over momentum north (" + east + ")");
    }
}
