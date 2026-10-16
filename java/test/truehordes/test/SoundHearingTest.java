package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.sim.Horde;
import truehordes.sim.SimSettings;
import truehordes.sim.Simulation;
import truehordes.sim.SoundEvent;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/** Sub-project 5a spec section 4: held zombies hear by energy falloff, once per sound, at the moment it happens. */
public final class SoundHearingTest {
    private SoundHearingTest() {}

    static final double LATE = SimulationTest.LATE;

    static Simulation sim(Store s, long seed) {
        return new Simulation(s, SimulationTest.openMap(400, -1), SimulationTest.quiet().toBuilder().stragglerOneIn(1_000_000)
            .build(), seed, LATE);
    }

    static boolean heading(ZombieRecord r, float x, float y) {
        return r.hasTarget() && Math.abs(r.targetX - x) <= 1 && Math.abs(r.targetY - y) <= 1;
    }

    /** At 800 tiles from an 850-tile sound about 43% of lone zombies hear it, at once (no waiting for updates). */
    public static void testLoneZombiesAtTheEdge() {
        Store s = new Store();
        List<ZombieRecord> rs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) rs.add(SimulationTest.add(s, 1000 + 800, 1000 + (i % 40) * 0.5f, 0));
        Simulation sim = sim(s, 1);
        sim.step(LATE, Long.MAX_VALUE);
        sim.offerSound(1000, 1000, 850, false, 1, SoundEvent.GUNFIRE);
        sim.step(LATE + 0.001, Long.MAX_VALUE);
        int heard = 0;
        for (ZombieRecord r : rs) if (heading(r, 1000, 1000)) heard++;
        Check.isTrue(heard > 370 && heard < 490, "about 43% heard it (" + heard + "/1000)");
    }

    public static void testInnerQuarterAllHear() {
        Store s = new Store();
        List<ZombieRecord> rs = new ArrayList<>();
        for (int i = 0; i < 200; i++) rs.add(SimulationTest.add(s, 1000 + 150, 1000 + i * 0.5f, 0));
        Simulation sim = sim(s, 2);
        sim.offerSound(1000, 1000, 850, false, 1, SoundEvent.GUNFIRE);
        sim.step(LATE + 0.001, Long.MAX_VALUE);
        for (ZombieRecord r : rs) Check.isTrue(heading(r, 1000, 1000), "everyone inside the inner quarter hears it");
    }

    /** A 1,000 horde at 800 tiles sends about 22% as a new horde heading for the sound; the rest carry on. */
    public static void testHordeSplitsByShare() {
        Store s = new Store();
        Simulation sim = sim(s, 3);
        Horde h = SimulationTest.horde(sim, 1800, 1000, 0, 1000);
        sim.offerSound(1000, 1000, 850, false, 1, SoundEvent.GUNFIRE);
        sim.step(LATE + 0.001, Long.MAX_VALUE);
        int left = 1000 - h.size();
        Check.isTrue(left > 180 && left < 260, "about 22% broke off (" + left + ")");
        Horde part = null;
        for (Horde o : sim.hordes().all()) if (o != h) part = o;
        Check.isTrue(part != null && part.size() == left, "they left as one new horde");
        Check.isTrue(Math.abs(part.targetX - 1000) < 1 && Math.abs(part.targetY - 1000) < 1, "heading for the sound");
        Check.isTrue(Float.isNaN(h.targetX), "the rest did not");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    /** Half or more hearing: the whole horde goes (a horde of 100 at 425 tiles: 58% hear). */
    public static void testHiveMind() {
        Store s = new Store();
        Simulation sim = sim(s, 4);
        Horde h = SimulationTest.horde(sim, 1425, 1000, 0, 100);
        sim.offerSound(1000, 1000, 850, false, 1, SoundEvent.GUNFIRE);
        sim.step(LATE + 0.001, Long.MAX_VALUE);
        Check.equal(100, h.size(), "nobody split off");
        Check.isTrue(Math.abs(h.targetX - 1000) < 1, "the whole horde turned for the sound");
    }

    /** User, 2026-10-16: a firefight 800 tiles away chips at a 10,000 horde shot by shot. */
    public static void testFirefightChipsAtABigHorde() {
        Store s = new Store();
        Simulation sim = sim(s, 5);
        Horde h = SimulationTest.horde(sim, 1800, 1000, 0, 10000);
        double t = LATE;
        int after1 = -1;
        for (int shot = 0; shot < 20; shot++) {
            sim.offerSound(1000, 1000, 850, false, 100 + shot, SoundEvent.GUNFIRE);
            t += 0.0005;
            sim.step(t, Long.MAX_VALUE);
            if (shot == 0) after1 = sim.hordes().get(h.id) == null ? 0 : h.size();
        }
        Check.isTrue(after1 > 8400 && after1 < 9200, "the first shot took about 12% (" + after1 + " left)");
        int main = sim.hordes().get(h.id) == null || !Float.isNaN(h.targetX) ? 0 : h.size();
        Check.isTrue(main < 2500, "after 20 shots little of it is left not heading for the fight (" + main + ")");
        Check.equal("", sim.checkConservation("test"), "conservation");
    }

    /** A sound the game re-sends every tick is one sound: one roll per zombie, not one per re-send. */
    public static void testRepeatingSoundIsOneRoll() {
        Store s = new Store();
        List<ZombieRecord> rs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) rs.add(SimulationTest.add(s, 1800, 1000 + (i % 40) * 0.5f, 0));
        Simulation sim = sim(s, 6);
        double t = LATE;
        for (int i = 0; i < 100; i++) {
            sim.offerSound(1000, 1000, 850, true, 7, SoundEvent.ALARM);
            t += 0.0002;
            sim.step(t, Long.MAX_VALUE);
        }
        int heard = 0;
        for (ZombieRecord r : rs) if (heading(r, 1000, 1000)) heard++;
        Check.isTrue(heard > 370 && heard < 490, "still about 43% (" + heard + "/1000)");
        Check.equal(1, sim.sounds().size(), "one ongoing sound");
    }

    /** Heading for a sound, a walker switches only for one at least 6 dB louder where it stands. */
    public static void testSwitchesOnlyForAClearlyLouderSound() {
        Store s = new Store();
        ZombieRecord r = SimulationTest.add(s, 1100, 1000, 0);
        Simulation sim = sim(s, 7);
        sim.offerSound(1000, 1000, 1000, false, 1, SoundEvent.GUNFIRE); // 100 tiles away: certain
        sim.step(LATE + 0.001, Long.MAX_VALUE);
        Check.isTrue(heading(r, 1000, 1000), "heading for the first sound");
        sim.offerSound(1200, 1000, 1500, false, 2, SoundEvent.GUNFIRE); // 100 tiles the other way: +3.5 dB
        sim.step(LATE + 0.002, Long.MAX_VALUE);
        Check.isTrue(heading(r, 1000, 1000), "a slightly louder sound does not turn it");
        sim.offerSound(1200, 1000, 4000, false, 3, SoundEvent.GUNFIRE); // well over 6 dB louder where it is
        sim.step(LATE + 0.003, Long.MAX_VALUE);
        Check.isTrue(heading(r, 1200, 1000), "a clearly louder one does");
    }

    /** Walkers already heading for a spot do not split again for more sounds from it; their time is refreshed. */
    public static void testSameSpotDoesNotSplitAgain() {
        Store s = new Store();
        Simulation sim = sim(s, 8);
        Horde h = SimulationTest.horde(sim, 1425, 1000, 0, 100);
        sim.offerSound(1000, 1000, 850, false, 1, SoundEvent.GUNFIRE);
        sim.step(LATE + 0.001, Long.MAX_VALUE);
        double until = h.investigateUntilHours;
        for (int i = 0; i < 10; i++) {
            sim.offerSound(1005, 1000, 850, false, 10 + i, SoundEvent.GUNFIRE);
            sim.step(LATE + 0.002 + i * 0.01, Long.MAX_VALUE);
        }
        Check.equal(100, h.size(), "still one horde");
        Check.equal(1, sim.hordes().size(), "no splinters");
        Check.isTrue(h.investigateUntilHours > until, "its time at the spot was refreshed");
    }

    /** Moaning is always the lowest priority: a zombie heading for a sound is not turned by a horde's moaning. */
    public static void testMoaningDoesNotHijackASoundTarget() {
        Store s = new Store();
        ZombieRecord r = SimulationTest.add(s, 1100, 1000, 0);
        Simulation sim = new Simulation(s, SimulationTest.openMap(400, -1), SimulationTest.quiet().toBuilder()
            .moanResponsePerHour(1).stragglerOneIn(1_000_000).build(), 9, LATE);
        SimulationTest.horde(sim, 1100, 1100, 0, 3000);
        sim.offerSound(1000, 1000, 1000, false, 1, SoundEvent.GUNFIRE);
        SimulationTest.run(sim, LATE + 0.001, 0.5);
        Check.isTrue(heading(r, 1000, 1000) || s.get(r.id) == null, "still heading for the sound");
        Check.isTrue(r.x() < 1095, "and walking towards it (" + r.x() + ")");
    }

    /** The game's Hearing setting scales how far zombies hear (Pinpoint x3, Poor x0.45), as vanilla scales a radius. */
    public static void testHearingSettingScalesReach() {
        for (int setting = 1; setting <= 3; setting += 2) {
            Store s = new Store();
            float at = setting == 1 ? 2000 : 600; // outside the plain circle / inside it
            ZombieRecord r = SimulationTest.add(s, 1000 + at, 1000, 0);
            Simulation sim = new Simulation(s, SimulationTest.openMap(400, -1), SimulationTest.quiet().toBuilder()
                .stragglerOneIn(1_000_000).hearingSetting(setting).build(), 11, LATE);
            int heard = 0;
            for (int i = 0; i < 40; i++) {
                r.targetX = ZombieRecord.NO_TARGET;
                r.targetY = ZombieRecord.NO_TARGET;
                r.investigateUntilHours = 0;
                sim.offerSound(1000, 1000, 850, false, 1, SoundEvent.GUNFIRE);
                sim.step(LATE + 0.0001 * (i + 1), Long.MAX_VALUE);
                if (heading(r, 1000, 1000)) heard++;
                SimulationTest.add(s, 0, 0, 0); // keep ids moving (unused)
            }
            if (setting == 1) Check.isTrue(heard > 10, "Pinpoint hears it 2,000 tiles out (" + heard + "/40)");
            else Check.equal(0, heard, "Poor never hears it 600 tiles out");
        }
    }

    /** The viewer sees active sounds and recently ended ones with their counts, and lines from responding hordes. */
    public static void testViewerData() {
        Store s = new Store();
        for (int i = 0; i < 20; i++) SimulationTest.add(s, 1100, 1000 + i, 0);
        Simulation sim = sim(s, 12);
        Horde h = SimulationTest.horde(sim, 1000, 1300, 0, 50);
        sim.offerSound(1000, 1000, 1000, false, 1, SoundEvent.GUNFIRE);
        sim.step(LATE + 0.001, Long.MAX_VALUE);
        List<SoundEvent> v = sim.soundsView();
        Check.equal(1, v.size(), "one sound");
        Check.equal(20, v.get(0).wanderers, "20 wanderers went");
        Check.equal(1, v.get(0).hordes, "one horde went");
        float[] lines = sim.responseLines();
        Check.equal(4, lines.length, "one line");
        Check.isTrue(lines[0] == h.x && lines[2] == 1000, "from the horde to the sound");
        sim.step(LATE + 0.1, Long.MAX_VALUE);
        Check.equal(0, sim.sounds().size(), "the gunshot is over");
        Check.equal(1, sim.soundsView().size(), "but still in the viewer's recent list");
    }
}
