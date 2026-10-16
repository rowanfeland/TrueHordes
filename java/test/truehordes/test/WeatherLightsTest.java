package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.sim.Horde;
import truehordes.sim.SoundEvent;
import truehordes.sim.Simulation;
import truehordes.sim.Weather;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/** Sub-project 5b spec goals 4 and 6: rain muffles, thunder draws from outside storms, lights lure at night. */
public final class WeatherLightsTest {
    private WeatherLightsTest() {}

    static final double LATE = SimulationTest.LATE;

    static List<ZombieRecord> ring(Store s, float x, float y, float d, int n) {
        List<ZombieRecord> rs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            rs.add(SimulationTest.add(s, (float) (x + Math.cos(a) * d), (float) (y + Math.sin(a) * d), 0));
        }
        return rs;
    }

    static int heading(List<ZombieRecord> rs, float x, float y) {
        int n = 0;
        for (ZombieRecord r : rs) if (SoundHearingTest.heading(r, x, y)) n++;
        return n;
    }

    /** The same gunshot draws far fewer zombies in heavy rain than on a clear day. */
    public static void testRainMuffles() {
        int[] got = new int[2];
        for (int wet = 0; wet <= 1; wet++) {
            Store s = new Store();
            List<ZombieRecord> rs = ring(s, 1600, 1600, 700, 400);
            Simulation sim = SoundHearingTest.sim(s, 11);
            if (wet == 1) sim.setWeather(Weather.uniform(1f, Weather.MAX_MUFFLE_DB));
            sim.offerSound(1600, 1600, 1000, false, 0, SoundEvent.GUNFIRE);
            sim.step(LATE, Long.MAX_VALUE);
            got[wet] = heading(rs, 1600, 1600);
        }
        Check.isTrue(got[0] > 150, "clear: many go (" + got[0] + ")");
        Check.isTrue(got[1] < got[0] / 3, "heavy rain: far fewer (" + got[1] + " vs " + got[0] + ")");
    }

    /** A regional storm over the sound's spot: (x, y) cells 0..9 of 200 tiles, wet only where {@code wet} says. */
    static Weather storm(float[] precip) { return Weather.regional(0, 0, 200, 20, 20, precip, Weather.MAX_MUFFLE_DB); }

    /** Thunder: zombies inside the storm ignore it; outside, some hear it (the lone roll), not all. */
    public static void testThunderDrawsOnlyFromOutsideTheStorm() {
        Store s = new Store();
        List<ZombieRecord> inside = ring(s, 2000, 2000, 150, 100);
        List<ZombieRecord> outside = ring(s, 2000, 2000, 1500, 200);
        Simulation sim = SoundHearingTest.sim(s, 12);
        float[] p = new float[400];
        for (int y = 8; y <= 11; y++) for (int x = 8; x <= 11; x++) p[y * 20 + x] = 0.9f; // 1600-2400 is stormy
        sim.setWeather(storm(p));
        sim.offerSound(2000, 2000, Simulation.THUNDER_RADIUS, false, 0, SoundEvent.THUNDER);
        sim.step(LATE, Long.MAX_VALUE);
        Check.equal(0, heading(inside, 2000, 2000), "inside the storm nobody turns to the thunder");
        int out = heading(outside, 2000, 2000);
        Check.isTrue(out > 20, "outside, zombies are drawn to it (" + out + "/200)");
    }

    /** A horde hearing thunder rolls once: all of it turns, or none of it. */
    public static void testHordeRollsOnceForThunder() {
        int all = 0, none = 0;
        for (int seed = 0; seed < 40; seed++) {
            Store s = new Store();
            Simulation sim = SoundHearingTest.sim(s, 100 + seed);
            Horde h = SimulationTest.horde(sim, 1600 + 3500, 1600, 0, 200);
            sim.offerSound(1600, 1600, Simulation.THUNDER_RADIUS, false, 0, SoundEvent.THUNDER);
            sim.step(LATE, Long.MAX_VALUE);
            Horde now = sim.hordes().get(h.id);
            Check.isTrue(now != null && now.size() == 200, "no members split off for thunder");
            if (Math.abs(now.targetX - 1600) < 1) all++;
            else none++;
        }
        Check.isTrue(all > 0 && none > 0, "some hordes turn, some do not (" + all + " / " + none + ")");
    }

    /** Storms away from the players thunder on their own; with vanilla weather (no regions) they do not. */
    public static void testFarStormsThunder() {
        for (int regional = 0; regional <= 1; regional++) {
            Store s = new Store();
            Simulation sim = SoundHearingTest.sim(s, 13);
            float[] p = new float[400];
            for (int y = 2; y <= 4; y++) for (int x = 2; x <= 4; x++) p[y * 20 + x] = 1f;
            sim.setWeather(regional == 1 ? storm(p) : Weather.uniform(1f, Weather.MAX_MUFFLE_DB));
            int thunder = 0;
            for (double t = LATE; t < LATE + 4; t += 1.0 / 60) {
                sim.step(t, Long.MAX_VALUE);
                for (SoundEvent e : sim.sounds()) if (e.kind == SoundEvent.STORM_THUNDER && e.startHours() == t) thunder++;
            }
            if (regional == 0) Check.equal(0, thunder, "vanilla weather: the game makes the thunder");
            else Check.isTrue(thunder >= 5 && thunder <= 25, "a full storm thunders about 3 times an hour (" + thunder + " in 4 h)");
        }
    }

    /** A light at night: about its chance of the zombies in reach go to it, each rolling once; out of reach, none. */
    public static void testLightLuresOnceEach() {
        Store s = new Store();
        List<ZombieRecord> near = ring(s, 1600, 1600, 40, 400);
        List<ZombieRecord> far = ring(s, 1600, 1600, 90, 100);
        Simulation sim = SoundHearingTest.sim(s, 14);
        sim.setLures(List.of(new Simulation.Lure(1, 1600, 1600, 60, 0.25, LATE + 10)));
        sim.step(LATE, Long.MAX_VALUE);
        int went = heading(near, 1600, 1600);
        Check.isTrue(went > 70 && went < 130, "about a quarter go (" + went + "/400)");
        Check.equal(0, heading(far, 1600, 1600), "out of reach: none");
        for (double t = LATE; t < LATE + 0.5; t += 1.0 / 60) sim.step(t, Long.MAX_VALUE);
        int later = 0;
        for (ZombieRecord r : near) if (r.hasTarget() && r.investigateUntilHours == LATE + 10) later++;
        Check.isTrue(later <= went + 5, "no second roll for the same light (" + later + " vs " + went + ")");
    }

    /** A horde rolls once for a light, as a whole; a streetlight keeps it until morning. */
    public static void testHordeLuredWhole() {
        int whole = 0;
        for (int seed = 0; seed < 60; seed++) {
            Store s = new Store();
            Simulation sim = SoundHearingTest.sim(s, 200 + seed);
            Horde h = SimulationTest.horde(sim, 1600 + 30, 1600, 0, 50);
            sim.setLures(List.of(new Simulation.Lure(7, 1600, 1600, 60, 0.25, LATE + 8)));
            sim.step(LATE, Long.MAX_VALUE);
            Horde now = sim.hordes().get(h.id);
            Check.isTrue(now != null && now.size() == 50, "lured as a whole");
            if (Math.abs(now.targetX - 1600) < 1) {
                whole++;
                Check.equal(LATE + 8, now.investigateUntilHours, "stays until morning");
            }
        }
        Check.isTrue(whole > 5 && whole < 28, "about a quarter of hordes go (" + whole + "/60)");
    }

    /** A lured zombie still goes for a real sound: the light is the quietest pull. */
    public static void testSoundBeatsALight() {
        Store s = new Store();
        List<ZombieRecord> rs = ring(s, 1600, 1600, 30, 200);
        Simulation sim = SoundHearingTest.sim(s, 15);
        sim.setLures(List.of(new Simulation.Lure(2, 1600, 1600, 60, 1.0, LATE + 10)));
        sim.step(LATE, Long.MAX_VALUE);
        Check.equal(200, heading(rs, 1600, 1600), "a 100% light takes all");
        sim.offerSound(1700, 1600, 800, false, 0, SoundEvent.GUNFIRE);
        sim.step(LATE + 1.0 / 60, Long.MAX_VALUE);
        Check.isTrue(heading(rs, 1700, 1600) > 150, "a nearby gunshot takes them from the light");
    }
}
