package truehordes.sim;

import java.util.Locale;

/**
 * The balance check (spec 5): zombies only enter or leave the held population through the population code (seeding,
 * growth, respawn, hand-over with real zombies), never through the simulation's own moves between wanderers and
 * hordes. The simulation counts every outside add and removal; held must always equal that running total.
 */
public final class Conservation {
    private long expected;
    private long added, removed;
    private String lastProblem = "";

    /** Starts counting from the current held total. */
    public void reset(long held) {
        expected = held;
        added = 0;
        removed = 0;
        lastProblem = "";
    }

    public void added(int n) {
        expected += n;
        added += n;
    }

    public void removed(int n) {
        expected -= n;
        removed += n;
    }

    /** "" when held matches; otherwise what is off (also remembered for the status line). */
    public String check(long held, String where) {
        if (held == expected) return "";
        lastProblem = String.format(Locale.ROOT, "conservation off at %s: held %d, expected %d (%+d)", where, held,
            expected, held - expected);
        expected = held; // report each drift once
        return lastProblem;
    }

    public long expected() { return expected; }

    public String summary() {
        return String.format(Locale.ROOT, "in %d, out %d%s", added, removed,
            lastProblem.isEmpty() ? ", ok" : ", last problem: " + lastProblem);
    }
}
