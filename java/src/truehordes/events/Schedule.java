package truehordes.events;

import java.util.function.DoubleSupplier;

/**
 * When meta events happen (sub-project 5b spec goals 1-2). Days count from the world's first day. Each rule hands back
 * the next time to look and whether something happens then; a "chance per period" rule looks once per period, on a
 * random day of it, and fires with that chance.
 */
public final class Schedule {
    private Schedule() {}

    public enum Rule { FAR, NEAR_GUN, CAR_ALARM }

    /** The next time to look (days since the world began) and whether an event fires then. */
    public record Next(double atDays, boolean fires) {}

    public static final double MONTH = 30, YEAR = 360;
    /** After the first month: a near gunshot is a 10% chance per 31 days; a car alarm 10% per 20 days. */
    public static final double NEAR_GUN_PERIOD = 31, CAR_PERIOD = 20, LATE_CHANCE = 0.10;

    /** The next look after {@code day} for {@code rule}; {@code u} gives uniform rolls in [0, 1). */
    public static Next next(Rule rule, double day, DoubleSupplier u) {
        switch (rule) {
            case FAR: return new Next(day + farGapDays(day, u), true);
            case NEAR_GUN:
                if (day < MONTH) return new Next(day + monthOneGap(day, u), true);
                return period(day, NEAR_GUN_PERIOD, u);
            default:
                if (day < MONTH) return new Next(day + 2 + u.getAsDouble(), true);
                if (day >= YEAR) return new Next(Double.POSITIVE_INFINITY, false);
                return period(day, CAR_PERIOD, u);
        }
    }

    /**
     * Far events: month 1 one every 1-3 days, leaning towards 3 as the month ends; months 1-12 the gap stretches to
     * about 31 days; after that about monthly. The day is random (a gap of half to one and a half times the mean).
     */
    static double farGapDays(double day, DoubleSupplier u) {
        if (day < MONTH) return monthOneGap(day, u);
        double mean = day >= YEAR ? 31 : 3 + 28 * (day - MONTH) / (YEAR - MONTH);
        return mean * (0.5 + u.getAsDouble());
    }

    /** 1-3 days: even at the start, skewed towards 3 by the end of the month. */
    static double monthOneGap(double day, DoubleSupplier u) {
        double lean = Math.max(0, Math.min(1, day / MONTH));
        return 1 + 2 * Math.pow(u.getAsDouble(), 1 / (1 + 2 * lean));
    }

    /** One look per period, on a random day of the next one, firing with the late chance. */
    static Next period(double day, double length, DoubleSupplier u) {
        double start = Math.floor(day / length) * length + length;
        return new Next(start + u.getAsDouble() * length, u.getAsDouble() < LATE_CHANCE);
    }
}
