package truehordes.util;

/** Defensive conversion of values arriving from Lua (Kahlua passes Double, Boolean, String or null). */
public final class LuaArgs {
    private LuaArgs() {}

    public static int toInt(Object v, int fallback) {
        double d = toDouble(v, Double.NaN);
        return Double.isNaN(d) ? fallback : (int) d;
    }

    public static float toFloat(Object v, float fallback) {
        double d = toDouble(v, Double.NaN);
        return Double.isNaN(d) ? fallback : (float) d;
    }

    public static boolean toBool(Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return fallback;
    }

    private static double toDouble(Object v, double fallback) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
