package truehordes.util;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** "[TrueHordes] " lines on System.out (console.txt), switchable debug categories, each error kind once. */
public final class Log {
    public static final String TAG = "[TrueHordes] ";
    private static final Consumer<String> DEFAULT_SINK = System.out::println;
    private static final Set<String> debugOn = ConcurrentHashMap.newKeySet();
    private static final Set<String> errorsSeen = ConcurrentHashMap.newKeySet();
    private static volatile Consumer<String> sink = DEFAULT_SINK;

    private Log() {}

    /** Redirects output (tests); null restores System.out. */
    public static void setSink(Consumer<String> newSink) {
        sink = newSink == null ? DEFAULT_SINK : newSink;
    }

    public static void info(String msg) { sink.accept(TAG + msg); }

    public static void warn(String msg) { sink.accept(TAG + "WARN " + msg); }

    public static void error(String msg, Throwable t) {
        StringBuilder b = new StringBuilder(TAG + "ERROR " + msg);
        if (t != null) {
            b.append(": ").append(t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) b.append(" <- ").append(st[i]);
        }
        sink.accept(b.toString());
    }

    /** Logs only the first occurrence of each key. Returns true if this call logged. */
    public static boolean errorOnce(String key, String msg, Throwable t) {
        if (!errorsSeen.add(key)) return false;
        error(msg, t);
        return true;
    }

    public static void setDebug(String category, boolean on) {
        if (on) debugOn.add(category); else debugOn.remove(category);
    }

    public static boolean isDebug(String category) {
        return debugOn.contains(category) || debugOn.contains("all");
    }

    public static void debug(String category, String msg) {
        if (isDebug(category)) sink.accept(TAG + "[" + category + "] " + msg);
    }

    public static String debugCategories() {
        return String.join(",", new TreeSet<>(debugOn));
    }

    /** Forgets debug categories and logged error keys (tests and world reloads). */
    public static void reset() {
        debugOn.clear();
        errorsSeen.clear();
    }
}
