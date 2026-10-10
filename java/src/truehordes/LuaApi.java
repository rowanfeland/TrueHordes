package truehordes;

import java.util.Random;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import truehordes.core.Core;
import truehordes.store.Snapshot;
import truehordes.util.Log;
import truehordes.util.LuaArgs;
import zombie.Lua.LuaManager;

/**
 * Exposed to Lua by ZombieBuddy as the table TrueHordesJ (works whatever the load order). The TH_* console commands
 * in media/lua/shared/TrueHordes/TH_Core.lua forward to it. Never throws into Lua.
 */
@Exposer.LuaClass(name = "TrueHordesJ")
public final class LuaApi {
    private static final String NOT_INSTALLED = "True Hordes Java core is not installed";

    private LuaApi() {}

    @LuaMethod(name = "TH_j_status", global = true)
    public static String status() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : c.status();
    }

    @LuaMethod(name = "TH_j_stats", global = true)
    public static String stats() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : c.stats().format();
    }

    @LuaMethod(name = "TH_j_debug", global = true)
    public static String debug(Object category, Object on) {
        String cat = category == null ? "all" : String.valueOf(category);
        boolean value = LuaArgs.toBool(on, true);
        Log.setDebug(cat, value);
        return "debug " + cat + " = " + value + " (on: " + Log.debugCategories() + ")";
    }

    @LuaMethod(name = "TH_j_save", global = true)
    public static String save() {
        Core c = Hooks.core();
        if (c == null) return NOT_INSTALLED;
        try {
            return c.save() ? "saved " + c.store().size() + " entities (debug save; the world was not saved)" : "not saved (state " + c.state() + ")";
        } catch (Throwable t) {
            Log.error("TH_save failed", t);
            return "save failed: " + t;
        }
    }

    @LuaMethod(name = "TH_j_seedTest", global = true)
    public static String seedTest(Object n, Object x, Object y) {
        Core c = Hooks.core();
        if (c == null) return NOT_INSTALLED;
        int added = c.seedTest(LuaArgs.toInt(n, 100), LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0), 300f, new Random());
        return added == 0 ? "not running (state " + c.state() + ")" : "added " + added + " placeholders; total " + c.store().size();
    }

    @LuaMethod(name = "TH_j_clearTest", global = true)
    public static String clearTest() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : "removed " + c.clearTest() + " placeholders";
    }

    /** Debug-map data for a rectangle, or nil when the core is not running. */
    @LuaMethod(name = "TH_j_snapshot", global = true)
    public static KahluaTable snapshot(Object minX, Object minY, Object maxX, Object maxY, Object maxPoints) {
        Core c = Hooks.core();
        if (c == null || c.state() != Core.State.RUNNING) return null;
        try {
            Snapshot s = c.store().snapshot(LuaArgs.toFloat(minX, 0), LuaArgs.toFloat(minY, 0),
                LuaArgs.toFloat(maxX, 0), LuaArgs.toFloat(maxY, 0), LuaArgs.toInt(maxPoints, 2000));
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("density", s.density);
            t.rawset("cell", (double) s.cellSize);
            t.rawset("n", (double) s.items);
            t.rawset("total", (double) s.total);
            t.rawset("maxCount", (double) s.maxCount);
            for (int i = 0; i < s.data.length; i++) t.rawset((double) (i + 1), (double) s.data[i]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:snapshot:" + t.getClass().getName(), "snapshot failed", t);
            return null;
        }
    }
}
