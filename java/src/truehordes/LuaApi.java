package truehordes;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import truehordes.core.Core;
import truehordes.store.Snapshot;
import truehordes.util.Log;
import truehordes.util.LuaArgs;
import truehordes.world.TerrainSample;
import zombie.Lua.LuaManager;

/**
 * Exposed to Lua by ZombieBuddy as the table TrueHordesJ (works whatever the load order). The Hordes_* console commands
 * in media/lua/shared/TrueHordes/Hordes_Core.lua forward to it. Never throws into Lua.
 */
@Exposer.LuaClass(name = "TrueHordesJ")
public final class LuaApi {
    private static final String NOT_INSTALLED = "True Hordes Java core is not installed";

    private LuaApi() {}

    @LuaMethod(name = "Hordes_j_status", global = true)
    public static String status() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : c.status();
    }

    @LuaMethod(name = "Hordes_j_stats", global = true)
    public static String stats() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : c.stats().format();
    }

    @LuaMethod(name = "Hordes_j_debug", global = true)
    public static String debug(Object category, Object on) {
        String cat = category == null ? "all" : String.valueOf(category);
        boolean value = LuaArgs.toBool(on, true);
        Log.setDebug(cat, value);
        return "debug " + cat + " = " + value + " (on: " + Log.debugCategories() + ")";
    }

    @LuaMethod(name = "Hordes_j_save", global = true)
    public static String save() {
        Core c = Hooks.core();
        if (c == null) return NOT_INSTALLED;
        try {
            return c.save() ? "saved " + c.store().size() + " held zombies (debug save; the world was not saved)" : "not saved (state " + c.state() + ")";
        } catch (Throwable t) {
            Log.error("Hordes_save failed", t);
            return "save failed: " + t;
        }
    }

    @LuaMethod(name = "Hordes_j_world", global = true)
    public static String world() {
        Core c = Hooks.core();
        return c == null ? NOT_INSTALLED : c.world().report();
    }

    /** Debug-map terrain codes for a rectangle, or nil when the world map is not ready. */
    @LuaMethod(name = "Hordes_j_terrain", global = true)
    public static KahluaTable terrain(Object minX, Object minY, Object maxX, Object maxY, Object step) {
        Core c = Hooks.core();
        if (c == null) return null;
        try {
            TerrainSample s = c.world().terrainSample((int) LuaArgs.toFloat(minX, 0), (int) LuaArgs.toFloat(minY, 0),
                (int) LuaArgs.toFloat(maxX, 0), (int) LuaArgs.toFloat(maxY, 0), LuaArgs.toInt(step, 8));
            if (s == null) return null;
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("x0", (double) s.x0);
            t.rawset("y0", (double) s.y0);
            t.rawset("step", (double) s.step);
            t.rawset("cols", (double) s.cols);
            t.rawset("rows", (double) s.rows);
            for (int i = 0; i < s.codes.length; i++) t.rawset((double) (i + 1), (double) s.codes[i]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:terrain:" + t.getClass().getName(), "terrain snapshot failed", t);
            return null;
        }
    }

    /** Debug-map tiles with any collision bit set: n, then x, y, bits triples; nil when not ready. */
    @LuaMethod(name = "Hordes_j_tiles", global = true)
    public static KahluaTable tiles(Object minX, Object minY, Object maxX, Object maxY, Object max) {
        Core c = Hooks.core();
        if (c == null || !c.world().ready()) return null;
        try {
            int[] s = c.world().tileSample((int) LuaArgs.toFloat(minX, 0), (int) LuaArgs.toFloat(minY, 0),
                (int) LuaArgs.toFloat(maxX, 0), (int) LuaArgs.toFloat(maxY, 0), LuaArgs.toInt(max, 20000));
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("n", (double) (s.length / 3));
            for (int i = 0; i < s.length; i++) t.rawset((double) (i + 1), (double) s[i]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:tiles:" + t.getClass().getName(), "tile snapshot failed", t);
            return null;
        }
    }

    /** Debug-map data for a rectangle, or nil when the core is not running. */
    @LuaMethod(name = "Hordes_j_snapshot", global = true)
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
