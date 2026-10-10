package truehordes.test;

import java.util.Objects;

/** Minimal assertions for the offline tests (no external test library). */
public final class Check {
    private Check() {}

    public static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void near(double expected, double actual, double tolerance, String what) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(what + ": expected " + expected + " +/- " + tolerance + " but was " + actual);
        }
    }

    public static void isTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void throwsAny(Runnable action, String what) {
        try {
            action.run();
        } catch (Throwable expected) {
            return;
        }
        throw new AssertionError(what + ": expected an exception");
    }
}
