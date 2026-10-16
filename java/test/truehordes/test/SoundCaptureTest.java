package truehordes.test;

import truehordes.SoundCapture;
import truehordes.sim.SoundEvent;

/** Sub-project 5a spec section 3: what the capture patch records, without the game. */
public final class SoundCaptureTest {
    private SoundCaptureTest() {}

    /** Nested addSound overloads report the same sound object on the way out: captured once per call. */
    public static void testNestedOverloadsCaptureOnce() {
        SoundCapture c = new SoundCapture();
        Object sound = new Object();
        Check.isTrue(c.firstTime(sound, 1), "first exit captures");
        Check.isTrue(!c.firstTime(sound, 1), "outer overloads of the same call do not");
        Check.isTrue(c.firstTime(sound, 2), "the pooled object reused on a later tick is a new sound");
        Check.isTrue(c.firstTime(new Object(), 2), "another sound on the same tick is captured");
    }

    public static void testKinds() {
        Check.equal(SoundEvent.GUNFIRE, SoundCapture.kindOf(true, false, false, false), "a gun in hand");
        Check.equal(SoundEvent.VEHICLE, SoundCapture.kindOf(false, true, false, false), "a vehicle");
        Check.equal(SoundEvent.APPLIANCE, SoundCapture.kindOf(false, false, true, false), "an appliance");
        Check.equal(SoundEvent.ALARM, SoundCapture.kindOf(false, false, false, true), "a repeating sound with no source");
        Check.equal(SoundEvent.OTHER, SoundCapture.kindOf(false, false, false, false), "anything else");
        Check.equal(SoundEvent.META, SoundCapture.kindOf(SoundEvent.META, false, false, false, false, false),
            "made inside vanilla's meta event");
        Check.equal(SoundEvent.THUNDER, SoundCapture.kindOf(SoundEvent.THUNDER, false, false, false, false, false),
            "made inside vanilla's thunder");
        Check.equal(SoundEvent.HELICOPTER, SoundCapture.kindOf(0, true, false, false, false, false),
            "at the helicopter");
    }
}
