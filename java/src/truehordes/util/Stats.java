package truehordes.util;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Per-system timings (moving average, last, max, calls) and counters for Hordes_stats(). Main thread only. */
public final class Stats {
    private static final double ALPHA = 0.1;
    private final Map<String, double[]> timings = new TreeMap<>(); // avgMs, lastMs, maxMs, calls
    private final Map<String, Long> counters = new TreeMap<>();

    public void time(String name, long nanos) {
        double ms = nanos / 1_000_000.0;
        double[] t = timings.computeIfAbsent(name, k -> new double[4]);
        t[0] = t[3] == 0 ? ms : t[0] + ALPHA * (ms - t[0]);
        t[1] = ms;
        t[2] = Math.max(t[2], ms);
        t[3]++;
    }

    public void count(String name, long value) { counters.put(name, value); }

    public double averageMs(String name) {
        double[] t = timings.get(name);
        return t == null ? 0 : t[0];
    }

    public long counter(String name) { return counters.getOrDefault(name, 0L); }

    public void clear() {
        timings.clear();
        counters.clear();
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        counters.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        timings.forEach((k, t) -> sb.append(String.format(Locale.ROOT,
            "%s: avg %.3f ms, last %.3f ms, max %.3f ms, %d calls%n", k, t[0], t[1], t[2], (long) t[3])));
        return sb.length() == 0 ? "no stats yet" : sb.toString().trim();
    }
}
