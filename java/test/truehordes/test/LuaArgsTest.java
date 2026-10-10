package truehordes.test;

import truehordes.util.LuaArgs;

/** Lua passes numbers as Double, booleans as Boolean, anything else as strings or null. */
public final class LuaArgsTest {
    public static void testNumbers() {
        Check.equal(12, LuaArgs.toInt(12.7, 0), "double to int truncates");
        Check.equal(5, LuaArgs.toInt("5", 0), "numeric string");
        Check.equal(3, LuaArgs.toInt(null, 3), "fallback for nil");
        Check.equal(3, LuaArgs.toInt("abc", 3), "fallback for junk");
        Check.near(1.5f, LuaArgs.toFloat(1.5, 0f), 0, "float");
        Check.near(9f, LuaArgs.toFloat(Double.NaN, 9f), 0, "NaN falls back");
    }

    public static void testBooleans() {
        Check.equal(true, LuaArgs.toBool(Boolean.TRUE, false), "Boolean");
        Check.equal(false, LuaArgs.toBool("false", true), "string false");
        Check.equal(true, LuaArgs.toBool(null, true), "nil falls back");
        Check.equal(true, LuaArgs.toBool(1.0, false), "non-zero number is true");
    }
}
