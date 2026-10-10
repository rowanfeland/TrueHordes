package truehordes.test;

import java.util.ArrayList;
import java.util.List;
import truehordes.util.Log;
import truehordes.util.Stats;

public final class LogStatsTest {
    private static List<String> capture() {
        List<String> lines = new ArrayList<>();
        Log.reset();
        Log.setSink(lines::add);
        return lines;
    }

    public static void testLinesCarryTag() {
        List<String> lines = capture();
        Log.info("hello");
        Log.warn("careful");
        Check.equal("[TrueHordes] hello", lines.get(0), "info line");
        Check.equal("[TrueHordes] WARN careful", lines.get(1), "warn line");
        Log.setSink(null);
    }

    public static void testErrorOnceLogsEachKeyOnce() {
        List<String> lines = capture();
        Check.isTrue(Log.errorOnce("k", "boom", new RuntimeException("x")), "first logged");
        Check.isTrue(!Log.errorOnce("k", "boom", new RuntimeException("x")), "second suppressed");
        Check.equal(1, lines.size(), "one line");
        Check.isTrue(lines.get(0).contains("ERROR boom: java.lang.RuntimeException: x"), "error text");
        Log.setSink(null);
    }

    public static void testDebugCategories() {
        List<String> lines = capture();
        Log.debug("save", "hidden");
        Log.setDebug("save", true);
        Log.debug("save", "shown");
        Log.debug("other", "hidden");
        Log.setDebug("all", true);
        Log.debug("other", "shown too");
        Check.equal(2, lines.size(), "two debug lines");
        Check.equal("[TrueHordes] [save] shown", lines.get(0), "category prefix");
        Check.equal("all,save", Log.debugCategories(), "categories listed sorted");
        Log.setSink(null);
        Log.reset();
    }

    public static void testStatsAveragesAndFormats() {
        Stats s = new Stats();
        s.time("tick", 2_000_000);
        s.time("tick", 4_000_000);
        Check.near(2.2, s.averageMs("tick"), 1e-9, "moving average (alpha 0.1)");
        s.count("entities", 7);
        Check.equal(7L, s.counter("entities"), "counter");
        String text = s.format();
        Check.isTrue(text.contains("entities=7"), "counter in text");
        Check.isTrue(text.contains("tick: avg 2.200 ms, last 4.000 ms, max 4.000 ms, 2 calls"), "timing line: " + text);
        s.clear();
        Check.equal("no stats yet", s.format(), "cleared");
    }
}
