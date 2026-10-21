package truehordes.real;

/**
 * Crowd-scaled break-in (D15, sub-project 6 spec goal 12): how long a crowd of {@code n} zombies takes to break a
 * window or a door (and barricades and player-built walls, timed like doors), and the damage each thump deals so that
 * the whole crowd takes that long. Times: a lone zombie about 5 in-game hours for a window and 24 for a door, 10 about
 * 1 and 5 hours, 1,000 about 10 minutes for either; between those points log-log interpolation, past 1,000 no faster.
 */
public final class BreakIn {
    private BreakIn() {}

    static final double[] CROWD = {1, 10, 1000};
    /** Windows take 28% less time than doors (user, 2026-10-21: one zombie broke windows far too fast at 5 h). */
    static final double WINDOW_SHARE = 0.72;
    static final double[] WINDOW_HOURS = {24 * WINDOW_SHARE, 5 * WINDOW_SHARE, 10 / 60.0 * WINDOW_SHARE};
    static final double[] DOOR_HOURS = {24, 5, 10 / 60.0};
    /** Thumps one zombie lands per real second while thumping (the thump animation; Claude's estimate, tuning). */
    public static final double THUMPS_PER_SECOND = 0.75;

    /** Fewer zombies than this at a door only claw at it: no damage (user, 2026-10-21: not just one). Default. */
    public static final int DOOR_MIN_CROWD = 3;

    /** True when {@code n} zombies can break a door at all, needing {@code min} of them. */
    public static boolean canBreakDoor(int n, int min) { return n >= Math.max(1, min); }

    /** In-game hours for {@code n} zombies to break it. */
    public static double hours(boolean window, int n) {
        double[] t = window ? WINDOW_HOURS : DOOR_HOURS;
        double c = Math.max(1, Math.min(CROWD[CROWD.length - 1], n));
        int i = c <= CROWD[1] ? 0 : 1;
        double f = (Math.log(c) - Math.log(CROWD[i])) / (Math.log(CROWD[i + 1]) - Math.log(CROWD[i]));
        return Math.exp(Math.log(t[i]) + f * (Math.log(t[i + 1]) - Math.log(t[i])));
    }

    /**
     * Health one thump takes off an object of {@code maxHealth}, with {@code n} zombies at it and
     * {@code realSecondsPerGameMinute} for the day length.
     */
    public static double damagePerThump(double maxHealth, int n, boolean window, double realSecondsPerGameMinute) {
        double seconds = hours(window, n) * 60 * realSecondsPerGameMinute;
        return maxHealth / (seconds * Math.max(1, n) * THUMPS_PER_SECOND);
    }
}
