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
        if (c == null) return NOT_INSTALLED;
        synchronized (c) {
            truehordes.sim.Simulation sim = c.simulation();
            return c.stats().format() + (sim == null ? "" : "\n" + sim.stats());
        }
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

    /**
     * Debug: a real game sound at (x, y) with a radius in tiles (default 1,000), made through the game's own
     * WorldSoundManager.addSound so it reaches real zombies and the off-screen model like any other sound.
     */
    /**
     * True Weathers compatibility (sub-project 5b): the precipitation grid to sample, {originX, originY, step, width,
     * height} in tiles, or nil before the map is known.
     */
    @LuaMethod(name = "Hordes_j_weatherGrid", global = true)
    public static KahluaTable weatherGrid() {
        Core c = Hooks.core();
        if (c == null) return null;
        try {
            int[] s = Hooks.WEATHER.spec(c.world());
            if (s == null) return null;
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("originX", (double) s[0]);
            t.rawset("originY", (double) s[1]);
            t.rawset("step", (double) s[2]);
            t.rawset("width", (double) s[3]);
            t.rawset("height", (double) s[4]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:weatherGrid", "weather grid failed", t);
            return null;
        }
    }

    /** True Weathers compatibility: precipitation (0..1) of grid cell {@code i}. */
    @LuaMethod(name = "Hordes_j_weatherSet", global = true)
    public static void weatherSet(Object i, Object precip) {
        try {
            Hooks.WEATHER.set((int) LuaArgs.toFloat(i, -1), LuaArgs.toFloat(precip, 0));
        } catch (Throwable t) {
            Log.errorOnce("lua:weatherSet", "weather sample failed", t);
        }
    }

    /** Project A-Life compatibility (sub-project 5b): an off-screen squad fight at (x, y) with its casualties. */
    @LuaMethod(name = "Hordes_j_alifeFight", global = true)
    public static void alifeFight(Object x, Object y, Object casualties) {
        try {
            if (!Hooks.ownsMetaEvents()) return;
            Hooks.EVENTS.firefight(LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0), (int) LuaArgs.toFloat(casualties, 0));
        } catch (Throwable t) {
            Log.errorOnce("lua:alifeFight", "A-Life fight gunfire failed", t);
        }
    }

    @LuaMethod(name = "Hordes_j_sound", global = true)
    public static String sound(Object x, Object y, Object radius) {
        Core c = Hooks.core();
        if (c == null) return NOT_INSTALLED;
        int r = Math.max(1, (int) LuaArgs.toFloat(radius, 1000f));
        int sx = (int) LuaArgs.toFloat(x, 0), sy = (int) LuaArgs.toFloat(y, 0);
        zombie.WorldSoundManager.instance.addSound(null, sx, sy, 0, r, r);
        return String.format(java.util.Locale.ROOT, "sound at %d,%d, radius %d tiles (as the game makes it)", sx, sy, r);
    }

    /**
     * The sound viewer's data: {@code n} sounds of 10 numbers each (id, x, y, radius, kind, age in in-game minutes,
     * ended 0/1, wanderers, hordes, zombies split off), {@code lines} (n, then horde x, y, target x, y each), and the
     * curve settings {@code edge} and {@code certain} for shading. Nil when not running.
     */
    @LuaMethod(name = "Hordes_j_sounds", global = true)
    public static KahluaTable sounds() {
        Core c = Hooks.core();
        if (c == null) return null;
        try {
            synchronized (c) {
                truehordes.sim.Simulation sim = c.simulation();
                if (sim == null) return null;
                java.util.List<truehordes.sim.SoundEvent> v = sim.soundsView();
                double now = sim.currentHours();
                KahluaTable t = LuaManager.platform.newTable();
                t.rawset("n", (double) v.size());
                int k = 1;
                for (truehordes.sim.SoundEvent e : v) {
                    double[] row = {e.id(), e.x(), e.y(), e.radius(), e.kind, (now - e.startHours()) * 60,
                        e.endHours() <= now ? 1 : 0, e.wanderers, e.hordes, e.splitOff};
                    for (double d : row) t.rawset((double) k++, d);
                }
                float[] l = sim.responseLines();
                KahluaTable lines = LuaManager.platform.newTable();
                lines.rawset("n", (double) (l.length / 4));
                for (int i = 0; i < l.length; i++) lines.rawset((double) (i + 1), (double) l[i]);
                t.rawset("lines", lines);
                // Sub-project 5b: rain (flat x, y, size, precipitation per wet cell; "rain" = vanilla's map-wide value)
                // and the lit lights the mod knows (flat x, y, kind: 0 building or placed, 1 streetlight).
                truehordes.sim.Weather w = sim.weather();
                KahluaTable storms = LuaManager.platform.newTable();
                int sk = 1;
                for (int i = 0; i < w.cellCount(); i++) {
                    float p = w.cellPrecip(i);
                    if (p < 0.05f) continue;
                    storms.rawset((double) sk++, (double) w.cellX(i));
                    storms.rawset((double) sk++, (double) w.cellY(i));
                    storms.rawset((double) sk++, (double) w.step());
                    storms.rawset((double) sk++, (double) p);
                }
                storms.rawset("n", (double) ((sk - 1) / 4));
                t.rawset("storms", storms);
                t.rawset("rain", (double) w.uniformPrecip());
                KahluaTable lights = LuaManager.platform.newTable();
                int lk = 1;
                for (LightLure.Light lt : Hooks.EVENTS.lights.known()) {
                    lights.rawset((double) lk++, (double) lt.x());
                    lights.rawset((double) lk++, (double) lt.y());
                    lights.rawset((double) lk++, (double) lt.kind());
                }
                lights.rawset("n", (double) ((lk - 1) / 3));
                t.rawset("lights", lights);
                t.rawset("edge", sim.settings().soundEdgeChance());
                t.rawset("certain", sim.settings().soundCertainShare());
                return t;
            }
        } catch (Throwable t) {
            Log.errorOnce("lua:sounds:" + t.getClass().getName(), "sound list failed", t);
            return null;
        }
    }

    /**
     * The loudest held horde of 50 or more the player at (x, y) can hear: a table {x, y, level, size}, or nil. Polled
     * once a second by Hordes_Moan.lua.
     */
    @LuaMethod(name = "Hordes_j_hordeMoan", global = true)
    public static KahluaTable hordeMoan(Object x, Object y) {
        Core c = Hooks.core();
        if (c == null) return null;
        try {
            synchronized (c) {
                truehordes.sim.Simulation sim = c.simulation();
                if (sim == null) return null;
                float[] m = sim.loudestMoanAt(LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0), 50);
                if (m == null) return null;
                KahluaTable t = LuaManager.platform.newTable();
                t.rawset("x", (double) m[0]);
                t.rawset("y", (double) m[1]);
                t.rawset("level", (double) m[2]);
                t.rawset("size", (double) m[3]);
                return t;
            }
        } catch (Throwable t) {
            Log.errorOnce("lua:moan:" + t.getClass().getName(), "horde moan lookup failed", t);
            return null;
        }
    }

    /** Debug: the 20 biggest hordes. */
    @LuaMethod(name = "Hordes_j_hordes", global = true)
    public static String hordes() {
        Core c = Hooks.core();
        if (c == null) return NOT_INSTALLED;
        synchronized (c) {
            truehordes.sim.Simulation sim = c.simulation();
            if (sim == null) return "not running (state " + c.state() + ")";
            java.util.List<truehordes.sim.Horde> list = new java.util.ArrayList<>();
            int dormant = 0, inside = 0;
            for (truehordes.sim.Horde h : sim.hordes().all()) {
                if (h.dormant) { dormant++; inside += h.size(); } else list.add(h);
            }
            list.sort((a, b) -> Integer.compare(b.size(), a.size()));
            int roaming = sim.hordes().memberCount() - inside;
            StringBuilder sb = new StringBuilder();
            sb.append(list.size()).append(" hordes (").append(roaming).append(" zombies), ").append(dormant)
                .append(" building hordes waiting (").append(inside).append(" zombies)");
            for (int i = 0; i < Math.min(20, list.size()); i++) {
                truehordes.sim.Horde h = list.get(i);
                sb.append(String.format(java.util.Locale.ROOT, "\n#%d: %d zombies at %.0f,%.0f heading %.0f deg, %s%s",
                    h.id, h.size(), h.x, h.y, Math.toDegrees(h.heading), h.pauseMinutes > 0 ? "paused" : "moving",
                    h.followHordeId != 0 ? ", following #" + h.followHordeId : ""));
            }
            return sb.toString();
        }
    }

    /** Debug-map data for a rectangle, or nil when the core is not running. */
    @LuaMethod(name = "Hordes_j_snapshot", global = true)
    public static KahluaTable snapshot(Object minX, Object minY, Object maxX, Object maxY, Object maxPoints) {
        Core c = Hooks.core();
        if (c == null || c.state() != Core.State.RUNNING) return null;
        float x0 = LuaArgs.toFloat(minX, 0), y0 = LuaArgs.toFloat(minY, 0);
        float x1 = LuaArgs.toFloat(maxX, 0), y1 = LuaArgs.toFloat(maxY, 0);
        try {
            // Under the core's lock: the simulation thread moves zombies in the background.
            synchronized (c) {
                truehordes.sim.Simulation running = c.simulation();
                Snapshot s;
                if (running != null) {
                    // Through the spatial grid: only the chunks in view, not every held zombie (main thread, locked).
                    java.util.List<truehordes.store.ZombieRecord> in = new java.util.ArrayList<>();
                    running.grid().wanderersIn(x0, y0, x1, y1, in::add);
                    s = truehordes.store.Store.snapshotOf(in, x0, y0, x1, y1, LuaArgs.toInt(maxPoints, 2000));
                } else {
                    s = c.store().snapshot(x0, y0, x1, y1, LuaArgs.toInt(maxPoints, 2000));
                }
                KahluaTable t = LuaManager.platform.newTable();
                t.rawset("density", s.density);
                t.rawset("cell", (double) s.cellSize);
                t.rawset("n", (double) s.items);
                t.rawset("total", (double) s.total);
                t.rawset("maxCount", (double) s.maxCount);
                for (int i = 0; i < s.data.length; i++) t.rawset((double) (i + 1), (double) s.data[i]);
                // Hordes in view: x, y, spread, size, heading, dormant (1 = a building horde) per horde.
                KahluaTable hs = LuaManager.platform.newTable();
                int n = 0;
                truehordes.sim.Simulation sim = c.simulation();
                if (sim != null) {
                    for (truehordes.sim.Horde h : sim.hordes().all()) {
                        float r = h.spread();
                        if (h.x + r < x0 || h.x - r >= x1 || h.y + r < y0 || h.y - r >= y1) continue;
                        hs.rawset((double) (n * 6 + 1), (double) h.x);
                        hs.rawset((double) (n * 6 + 2), (double) h.y);
                        hs.rawset((double) (n * 6 + 3), (double) r);
                        hs.rawset((double) (n * 6 + 4), (double) h.size());
                        hs.rawset((double) (n * 6 + 5), (double) h.heading);
                        hs.rawset((double) (n * 6 + 6), h.dormant ? 1.0 : 0.0);
                        n++;
                    }
                }
                hs.rawset("n", (double) n);
                t.rawset("hordes", hs);
                return t;
            }
        } catch (Throwable t) {
            Log.errorOnce("lua:snapshot:" + t.getClass().getName(), "snapshot failed", t);
            return null;
        }
    }
}
