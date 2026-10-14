package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.sim.Hearing;
import truehordes.sim.Horde;
import truehordes.sim.Hordes;
import truehordes.sim.SoundEvent;
import truehordes.store.Store;

public final class HearingTest {
    private HearingTest() {}

    static Horde horde(Hordes hs, Store s, float x, float y, int n) {
        return hs.create(x, y, 0, SpatialGridTest.members(s, n));
    }

    public static void testRangeFromLoudness() {
        Check.near(10, Hearing.range(50), 1e-3, "50 dB carries about 10 tiles");
        Check.near(100, Hearing.range(70), 1e-2, "70 dB carries about 100 tiles");
        Check.isTrue(Hearing.audible(70, 99) && !Hearing.audible(70, 101), "audible edge");
    }

    public static void testWandererDrawnToBigHorde() {
        Store s = new Store();
        Hordes hs = new Hordes();
        Horde big = horde(hs, s, 50, 0, 1000);
        Hearing.Pull p = Hearing.choose(0, 0, 0, 0, List.of(), List.of(big));
        Check.isTrue(p != null && p.event() == null, "pulled by moaning");
        Check.near(50, p.x(), 1e-3, "towards the horde");
    }

    public static void testMembersNeverHearOwnHorde() {
        Store s = new Store();
        Hordes hs = new Hordes();
        Horde own = horde(hs, s, 0, 0, 1000);
        Check.isTrue(Hearing.choose(0, 0, own.id, Hearing.maskingDb(own), List.of(), List.of(own)) == null,
            "own horde ignored");
    }

    public static void testMoaningIsLowestPriority() {
        Store s = new Store();
        Hordes hs = new Hordes();
        Horde huge = horde(hs, s, 80, 0, 10000);
        SoundEvent shot = new SoundEvent(1, -30, 0, 90, 0, 1, false, 0);
        Hearing.Pull p = Hearing.choose(0, 0, 0, 0, List.of(shot), List.of(huge));
        Check.isTrue(p != null && p.event() == shot, "the test sound wins over a 10,000-horde's moaning");
        Check.near(-30, p.x(), 1e-3, "heads for the sound");
    }

    public static void testSmallHordeFollowsBigOneButNotTheOtherWay() {
        Store s = new Store();
        Hordes hs = new Hordes();
        Horde small = horde(hs, s, 0, 0, 20);
        Horde big = horde(hs, s, 60, 0, 2000);
        Hearing.Pull p = Hearing.choose(small.x, small.y, small.id, Hearing.maskingDb(small), List.of(),
            List.of(small, big));
        Check.isTrue(p != null && p.x() == 60, "small horde hears the big one");
        Check.isTrue(Hearing.choose(big.x, big.y, big.id, Hearing.maskingDb(big), List.of(), List.of(small, big)) == null,
            "big horde does not notice the small one");
    }

    public static void testNothingToHear() {
        Check.isTrue(Hearing.choose(0, 0, 0, 0, new ArrayList<>(), new ArrayList<>()) == null, "silence");
        SoundEvent far = new SoundEvent(2, 5000, 0, 90, 0, 1, false, 0);
        Check.isTrue(Hearing.choose(0, 0, 0, 0, List.of(far), List.of()) == null, "too far to hear");
    }
}
