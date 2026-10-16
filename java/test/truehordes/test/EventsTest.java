package truehordes.test;

import java.util.Random;
import truehordes.events.EventKind;
import truehordes.events.Gunfire;
import truehordes.events.Placement;
import truehordes.events.Schedule;
import truehordes.sim.MapView;

/** Sub-project 5b spec goals 1-3: when, what and where meta events happen, and how loud a gunshot is. */
public final class EventsTest {
    private EventsTest() {}

    static double meanGap(Schedule.Rule rule, double day, int n) {
        Random r = new Random(1);
        double sum = 0;
        for (int i = 0; i < n; i++) sum += Schedule.next(rule, day, r::nextDouble).atDays() - day;
        return sum / n;
    }

    /** Month 1: one far event every 1-3 days, leaning towards 3 by the month's end; then up to about monthly. */
    public static void testFarScheduleQuietsOverTheYear() {
        Random r = new Random(2);
        for (int i = 0; i < 2000; i++) {
            double g = Schedule.next(Schedule.Rule.FAR, 5, r::nextDouble).atDays() - 5;
            Check.isTrue(g >= 1 && g <= 3, "month 1: 1-3 days (" + g + ")");
        }
        double early = meanGap(Schedule.Rule.FAR, 0, 4000), late = meanGap(Schedule.Rule.FAR, 29.9, 4000);
        Check.near(2.0, early, 0.05, "day 0: about every 2 days");
        Check.isTrue(late > 2.4, "end of month 1: leaning towards 3 (" + late + ")");
        Check.near(3 + 28 * 0.5, meanGap(Schedule.Rule.FAR, 195, 4000), 0.5, "half a year: gap half way to 31");
        Check.near(31, meanGap(Schedule.Rule.FAR, 400, 4000), 0.8, "after a year: about monthly");
        Check.isTrue(Schedule.next(Schedule.Rule.FAR, 400, r::nextDouble).fires(), "far events always fire");
    }

    /** Near gunshot: month 1 every 1-3 days; after, a 10% chance per 31 days, on a random day. */
    public static void testNearGunshotSchedule() {
        Random r = new Random(3);
        Schedule.Next n = Schedule.next(Schedule.Rule.NEAR_GUN, 10, r::nextDouble);
        Check.isTrue(n.fires() && n.atDays() >= 11 && n.atDays() <= 13, "month 1: 1-3 days, fires");
        int fired = 0;
        for (int i = 0; i < 10000; i++) {
            Schedule.Next m = Schedule.next(Schedule.Rule.NEAR_GUN, 40, r::nextDouble);
            Check.isTrue(m.atDays() >= 62 && m.atDays() < 93, "the next 31-day period (" + m.atDays() + ")");
            if (m.fires()) fired++;
        }
        Check.isTrue(fired > 850 && fired < 1150, "10% of periods (" + fired + "/10000)");
    }

    /** Car alarm: month 1 every 2-3 days; then 10% per 20 days; never after month 12. */
    public static void testCarAlarmSchedule() {
        Random r = new Random(4);
        Schedule.Next n = Schedule.next(Schedule.Rule.CAR_ALARM, 3, r::nextDouble);
        Check.isTrue(n.fires() && n.atDays() >= 5 && n.atDays() <= 6, "month 1: 2-3 days");
        Schedule.Next m = Schedule.next(Schedule.Rule.CAR_ALARM, 45, r::nextDouble);
        Check.isTrue(m.atDays() >= 60 && m.atDays() < 80, "the next 20-day period (" + m.atDays() + ")");
        Schedule.Next never = Schedule.next(Schedule.Rule.CAR_ALARM, 361, r::nextDouble);
        Check.isTrue(!never.fires() && Double.isInfinite(never.atDays()), "never after month 12");
    }

    public static void testHalfOfFarEventsAreGunshots() {
        Random r = new Random(5);
        int guns = 0, dogs = 0;
        for (int i = 0; i < 20000; i++) {
            EventKind k = EventKind.pick(EventKind.FAR, r.nextDouble());
            if (k.gun()) guns++;
            if (k.name().equals("dog")) dogs++;
        }
        Check.isTrue(guns > 9500 && guns < 10500, "half gunshots (" + guns + ")");
        Check.isTrue(dogs > 4500 && dogs < 5500, "a quarter dogs (" + dogs + ")");
        EventKind rifle = EventKind.FAR.get(0);
        Check.equal(3, rifle.shots(0.0), "a rifle burst is at least 3 shots");
        Check.equal(8, rifle.shots(0.9999), "and at most 8");
    }

    /** 1:1 with Louder Gunshots: a rifle's 200 x its multiplier; without it vanilla's 600. */
    public static void testGunshotRadiusFollowsLouderGunshots() {
        Check.equal(600, Gunfire.radius(Double.NaN), "no Louder Gunshots: vanilla 600");
        Check.equal(1000, Gunfire.radius(5.0), "Louder Gunshots x5: 1000");
        Check.equal(200, Gunfire.radius(1.0), "x1: a rifle's 200");
    }

    /** A-Life fight: 6 shots + 4 per casualty, capped at 60, spread over about a minute, ascending. */
    public static void testFirefightShots() {
        Check.equal(6, Gunfire.fightShots(0), "no casualties: 6");
        Check.equal(18, Gunfire.fightShots(3), "3 casualties: 18");
        Check.equal(60, Gunfire.fightShots(40), "capped at 60");
        Random r = new Random(6);
        double[] t = Gunfire.times(60, Gunfire.FIGHT_SECONDS, r::nextDouble);
        Check.equal(0.0, t[0], "starts at once");
        for (int i = 1; i < t.length; i++) Check.isTrue(t[i] >= t[i - 1], "in order");
        Check.isTrue(t[59] <= 60.001 && t[59] > 15, "over about a minute (" + t[59] + " s)");
        double[] b = Gunfire.times(5, 0, r::nextDouble);
        for (int i = 1; i < b.length; i++) {
            double gap = b[i] - b[i - 1];
            Check.isTrue(gap >= 0.15 && gap <= 0.45, "a burst: a few tenths of a second apart (" + gap + ")");
        }
    }

    public static void testPlayerVolumeFallsWithDistance() {
        float near = Gunfire.volume(600, 75), mid = Gunfire.volume(600, 500), far = Gunfire.volume(600, 1000);
        Check.isTrue(near > mid && mid > far, "quieter further away (" + near + " " + mid + " " + far + ")");
        Check.isTrue(far >= 0.15f && near <= 1f, "always faintly audible, never past full volume");
    }

    /** A town block (buildings at chunks 100-110) with a road on row 105, open ground elsewhere, water past x 300. */
    static MapView map() {
        return new MapView() {
            @Override public int rank(int cx, int cy) {
                if (!inMap(cx, cy) || building(cx, cy)) return 0;
                return cy == 105 ? 1 : 4;
            }
            @Override public boolean inMap(int cx, int cy) { return cx >= 0 && cy >= 0 && cx < 300 && cy < 300; }
            @Override public boolean tight(int cx, int cy) { return false; }
            @Override public boolean building(int cx, int cy) {
                return cx >= 100 && cx < 110 && cy >= 100 && cy < 110 && cy != 105 && (cx + cy) % 3 == 0;
            }
        };
    }

    /** Spots fall in the band, on open ground, matching the kind, never near any player. */
    public static void testPlacementInTheBand() {
        Random r = new Random(7);
        float px = 1200, py = 1200;
        float[] players = {px, py, 1500, 1200};
        for (int i = 0; i < 300; i++) {
            float[] p = Placement.pick(map(), Placement.Where.ANYWHERE, px, py, players, 300, 1000, r::nextDouble);
            Check.isTrue(p != null, "a spot is found");
            double d = Math.hypot(p[0] - px, p[1] - py);
            Check.isTrue(d >= 299 && d <= 1001, "in the band (" + d + ")");
            Check.isTrue(Math.hypot(p[0] - 1500, p[1] - 1200) >= 299, "not near the other player");
            Check.isTrue(map().rank((int) Math.floor(p[0] / 8), (int) Math.floor(p[1] / 8)) > 0, "on open ground");
        }
        int town = 0;
        for (int i = 0; i < 200; i++) {
            float[] p = Placement.pick(map(), Placement.Where.TOWN, 840, 840, new float[] {840, 840}, 50, 100, r::nextDouble);
            int cx = (int) Math.floor(p[0] / 8), cy = (int) Math.floor(p[1] / 8);
            if (cy == 105 || (cx >= 98 && cx < 112 && cy >= 98 && cy < 112)) town++;
        }
        Check.isTrue(town > 190, "town events land in town or on the road (" + town + "/200)");
    }

    /** No ground in the band (all water): no spot, the event is skipped. */
    public static void testPlacementGivesUp() {
        MapView water = new MapView() {
            @Override public int rank(int cx, int cy) { return 0; }
            @Override public boolean inMap(int cx, int cy) { return true; }
            @Override public boolean tight(int cx, int cy) { return false; }
        };
        Random r = new Random(8);
        Check.isTrue(Placement.pick(water, Placement.Where.ANYWHERE, 0, 0, new float[0], 300, 1000, r::nextDouble) == null,
            "nowhere to stand: null");
    }
}
