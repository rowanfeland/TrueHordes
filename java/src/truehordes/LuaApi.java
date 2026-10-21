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
            return c.stats().format() + (sim == null ? "" : "\n" + sim.stats()) + "\n" + Hooks.PILOTS.stats();
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

    /** The map the debug map draws: the core's, or on an MP client the steering's own copy (sub-project 7). */
    private static truehordes.world.World mapWorld(Core c) {
        truehordes.world.World w = Hooks.PILOTS.clientWorld();
        return zombie.network.GameClient.client && w != null ? w : c.world();
    }

    // ---- multiplayer (sub-project 7): the Lua net layer calls these ----

    /** MP client, every tick: steer the zombies this client owns. */
    @LuaMethod(name = "Hordes_j_clientTick", global = true)
    public static void clientTick() {
        try {
            Hooks.PILOTS.clientTick(System.currentTimeMillis());
        } catch (Throwable t) {
            Log.errorOnce("lua:clientTick:" + t.getClass().getName(), "MP client tick failed", t);
        }
    }

    /** MP client: the messages for the server since the last call, as a list of {k, o, x, y, v}. */
    @LuaMethod(name = "Hordes_j_mpOut", global = true)
    public static KahluaTable mpOut() {
        KahluaTable t = LuaManager.platform.newTable();
        int n = 0;
        double[] m;
        while (n < 64 && (m = Hooks.PILOTS.takeOut()) != null) {
            KahluaTable e = LuaManager.platform.newTable();
            e.rawset("k", m[0]);
            e.rawset("o", m[1]);
            e.rawset("x", m[2]);
            e.rawset("y", m[3]);
            e.rawset("v", m[4]);
            t.rawset((double) (++n), e);
        }
        t.rawset("n", (double) n);
        return t;
    }

    /** MP client: the server's tags for the zombies near the player (see Pilots.applyTags). */
    @LuaMethod(name = "Hordes_j_mpApplyTags", global = true)
    public static void mpApplyTags(Object tags, Object px, Object py, Object radius) {
        try {
            if (!(tags instanceof KahluaTable t)) return;
            int n = LuaArgs.toInt(t.rawget("n"), 0);
            double[] rows = new double[n];
            for (int i = 0; i < n; i++) {
                Object v = t.rawget((double) (i + 1));
                rows[i] = v instanceof Double d ? d : Double.NaN;
            }
            Hooks.PILOTS.applyTags(rows, LuaArgs.toFloat(px, 0), LuaArgs.toFloat(py, 0), LuaArgs.toFloat(radius, 90));
        } catch (Throwable t) {
            Log.errorOnce("lua:mpApplyTags:" + t.getClass().getName(), "applying the server's horde tags failed", t);
        }
    }

    /** Server: tags of the zombies near (x, y) for that player's client; NaN lures are sent as the string "n". */
    @LuaMethod(name = "Hordes_j_mpTags", global = true)
    public static KahluaTable mpTags(Object x, Object y, Object radius) {
        KahluaTable t = LuaManager.platform.newTable();
        try {
            double[] rows = Hooks.PILOTS.tagsFor(LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0), LuaArgs.toFloat(radius, 90));
            for (int i = 0; i < rows.length; i++) t.rawset((double) (i + 1), Double.isNaN(rows[i]) ? (Object) "n" : (Object) rows[i]);
            t.rawset("n", (double) rows.length);
        } catch (Throwable e) {
            Log.errorOnce("lua:mpTags:" + e.getClass().getName(), "building horde tags failed", e);
            t.rawset("n", 0.0);
        }
        return t;
    }

    /** Server: a client's zombie heard a sound and is heading there. */
    @LuaMethod(name = "Hordes_j_mpLure", global = true)
    public static void mpLure(Object o, Object x, Object y) {
        try {
            Hooks.PILOTS.clientLure(LuaArgs.toInt(o, -1), LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0));
        } catch (Throwable t) {
            Log.errorOnce("lua:mpLure:" + t.getClass().getName(), "client lure failed", t);
        }
    }

    /** Server: a client's zombie lost a player and chases to (x, y) until v; far = the second leg's point. */
    @LuaMethod(name = "Hordes_j_mpChase", global = true)
    public static void mpChase(Object o, Object x, Object y, Object v, Object far) {
        try {
            Hooks.PILOTS.clientChase(LuaArgs.toInt(o, -1), LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0),
                LuaArgs.toFloat(v, 0), Boolean.TRUE.equals(far));
        } catch (Throwable t) {
            Log.errorOnce("lua:mpChase:" + t.getClass().getName(), "client chase failed", t);
        }
    }

    /** Server: a client's zombie walked to the loaded edge heading out; (x, y) is just past it. */
    @LuaMethod(name = "Hordes_j_mpOff", global = true)
    public static void mpOff(Object o, Object x, Object y, Object heading) {
        Core c = Hooks.core();
        if (c == null) return;
        try {
            Hooks.PILOTS.clientOff(c, LuaArgs.toInt(o, -1), LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0),
                LuaArgs.toFloat(heading, 0));
        } catch (Throwable t) {
            Log.errorOnce("lua:mpOff:" + t.getClass().getName(), "client walk-off failed", t);
        }
    }

    /** Server: event audio for the clients since the last call, as a list of {s, x, y, r}. */
    @LuaMethod(name = "Hordes_j_mpAudio", global = true)
    public static KahluaTable mpAudio() {
        KahluaTable t = LuaManager.platform.newTable();
        int n = 0;
        Object[] a;
        while (n < 32 && (a = Hooks.EVENTS.audio.poll()) != null) {
            KahluaTable e = LuaManager.platform.newTable();
            e.rawset("s", a[0]);
            e.rawset("x", (double) (Float) a[1]);
            e.rawset("y", (double) (Float) a[2]);
            e.rawset("r", (double) (Float) a[3]);
            t.rawset((double) (++n), e);
        }
        t.rawset("n", (double) n);
        return t;
    }

    /** MP client: play an event the server ran, at this player's distance from it. */
    @LuaMethod(name = "Hordes_j_playEvent", global = true)
    public static void playEvent(Object sound, Object x, Object y, Object radius) {
        try {
            if (!(sound instanceof String s)) return;
            Hooks.EVENTS.play(s, LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0), LuaArgs.toFloat(radius, 600));
        } catch (Throwable t) {
            Log.errorOnce("lua:playEvent:" + t.getClass().getName(), "event audio failed", t);
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
            TerrainSample s = mapWorld(c).terrainSample((int) LuaArgs.toFloat(minX, 0), (int) LuaArgs.toFloat(minY, 0),
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
        if (c == null || !mapWorld(c).ready()) return null;
        try {
            int[] s = mapWorld(c).tileSample((int) LuaArgs.toFloat(minX, 0), (int) LuaArgs.toFloat(minY, 0),
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

    /** Sub-project 8: crop squares a big horde trampled off screen, "x,y,z;..." (server or single player). */
    @LuaMethod(name = "Hordes_j_takeCrops", global = true)
    public static String takeCrops() {
        try {
            return AftermathBridge.takeCrops();
        } catch (Throwable t) {
            Log.errorOnce("lua:takeCrops", "trampled crops failed", t);
            return "";
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

    /** Project A-Life compatibility: one of A-Life's helicopter noises, heard like the game's own helicopter. */
    @LuaMethod(name = "Hordes_j_heliNoise", global = true)
    public static void heliNoise(Object x, Object y) {
        try {
            Core c = Hooks.core();
            if (c == null || c.soundSink() == null) return;
            Hooks.soundAs(truehordes.sim.SoundEvent.HELICOPTER, (int) LuaArgs.toFloat(x, 0), (int) LuaArgs.toFloat(y, 0),
                Hooks.HELICOPTER_RADIUS);
        } catch (Throwable t) {
            Log.errorOnce("lua:heliNoise", "A-Life helicopter noise failed", t);
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
    /** Far sight in Viewpoint's 3D view (see compat.ViewpointBridge): true when Viewpoint draws player pn's view. */
    @LuaMethod(name = "Hordes_j_vpBegin", global = true)
    public static boolean vpBegin(Object pn) {
        return truehordes.compat.ViewpointBridge.begin(LuaArgs.toInt(pn, 0));
    }

    /** A standing figure at (x, y, z) in the 3D view: then vpX, vpY (feet, pixels) and vpH (height, pixels). */
    @LuaMethod(name = "Hordes_j_vpFigure", global = true)
    public static boolean vpFigure(Object x, Object y, Object z) {
        return truehordes.compat.ViewpointBridge.figure(LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0),
            LuaArgs.toFloat(z, 0));
    }

    @LuaMethod(name = "Hordes_j_vpX", global = true)
    public static double vpX() { return truehordes.compat.ViewpointBridge.x(); }

    @LuaMethod(name = "Hordes_j_vpY", global = true)
    public static double vpY() { return truehordes.compat.ViewpointBridge.y(); }

    @LuaMethod(name = "Hordes_j_vpH", global = true)
    public static double vpH() { return truehordes.compat.ViewpointBridge.h(); }

    /** How far Viewpoint's far world draws (tiles), or 0 without Viewpoint's 3D view. */
    @LuaMethod(name = "Hordes_j_vpReach", global = true)
    public static double vpReach() { return truehordes.compat.ViewpointBridge.farReach(); }

    /**
     * The far view (single player, with Viewpoint): beyond {@code near} tiles from the loaded edge, out to {@code far}
     * tiles from (x, y): {@code n}, {@code rspm}, then x, y, heading, speed, radius per mark (radius 0: one zombie;
     * else a horde as a crowd that wide). Nil when the simulation is not running here.
     */
    @LuaMethod(name = "Hordes_j_farMarks", global = true)
    public static KahluaTable farMarks(Object x, Object y, Object near, Object far, Object maxHordes, Object maxSingles) {
        Core c = Hooks.core();
        if (c == null || c.state() != Core.State.RUNNING) return null;
        try {
            float[] f;
            double rspm;
            synchronized (c) {
                truehordes.sim.Simulation sim = c.simulation();
                if (sim == null) return null;
                float edge = zombie.iso.IsoChunkMap.chunkWidthInTiles / 2f;
                f = truehordes.sim.FarSight.farMarks(sim, LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0),
                    edge + LuaArgs.toFloat(near, 200), LuaArgs.toFloat(far, 4096), LuaArgs.toInt(maxHordes, 80),
                    LuaArgs.toInt(maxSingles, 300));
                rspm = sim.settings().realSecondsPerGameMinute();
            }
            f = occlude(c, f, truehordes.sim.FarSight.FAR_STRIDE, x, y);
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("n", (double) (f.length / truehordes.sim.FarSight.FAR_STRIDE));
            t.rawset("rspm", rspm);
            for (int i = 0; i < f.length; i++) t.rawset((double) (i + 1), (double) f[i]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:farMarks:" + t.getClass().getName(), "far view failed", t);
            return null;
        }
    }

    /**
     * Seeing hordes coming (sub-project 6): held zombies beyond the loaded area within {@code reach} tiles of (x, y),
     * nearest first, at most {@code max}: {@code n}, {@code rspm} (real seconds per in-game minute), then x, y, heading,
     * speed (tiles per in-game minute) per figure. Nil when the simulation is not running here.
     */
    /** Far marks the player cannot see past walls, woods or buildings are dropped (main thread). */
    private static float[] occlude(Core c, float[] f, int stride, Object x, Object y) {
        zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
        int z = p == null ? 0 : (int) Math.floor(p.getZ());
        truehordes.sim.MapView map;
        synchronized (c) {
            map = c.simulation() == null ? null : c.simulation().movement().map();
        }
        return Occlusion.visible(f, stride, map, LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0), z,
            zombie.iso.IsoChunkMap.chunkWidthInTiles / 2f);
    }

    @LuaMethod(name = "Hordes_j_farSight", global = true)
    public static KahluaTable farSight(Object x, Object y, Object reach, Object max) {
        Core c = Hooks.core();
        if (c == null || c.state() != Core.State.RUNNING) return null;
        try {
            float[] f;
            java.util.List<float[]> cand;
            double rspm;
            synchronized (c) {
                truehordes.sim.Simulation sim = c.simulation();
                if (sim == null) return null;
                // The reach counts from the loaded edge (only zombies beyond it are drawn), not from the player.
                float edge = zombie.iso.IsoChunkMap.chunkWidthInTiles / 2f;
                cand = truehordes.sim.FarSight.collect(sim, LuaArgs.toFloat(x, 0), LuaArgs.toFloat(y, 0),
                    edge + LuaArgs.toFloat(reach, 200), LuaArgs.toInt(max, 250));
                rspm = sim.settings().realSecondsPerGameMinute();
            }
            f = truehordes.sim.FarSight.nearest(cand, LuaArgs.toInt(max, 250));
            f = occlude(c, f, truehordes.sim.FarSight.STRIDE, x, y);
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("n", (double) (f.length / truehordes.sim.FarSight.STRIDE));
            t.rawset("rspm", rspm);
            for (int i = 0; i < f.length; i++) t.rawset((double) (i + 1), (double) f[i]);
            return t;
        } catch (Throwable t) {
            Log.errorOnce("lua:farSight:" + t.getClass().getName(), "far sight failed", t);
            return null;
        }
    }

    /** Test command: a horde of {@code n} on the nearest highway about 250 tiles from (px, py), or at (x, y). */
    @LuaMethod(name = "Hordes_j_roadHorde", global = true)
    public static String roadHorde(Object n, Object px, Object py, Object x, Object y) {
        Core c = Hooks.core();
        if (c == null || c.state() != Core.State.RUNNING) return NOT_INSTALLED;
        try {
            synchronized (c) {
                truehordes.sim.Simulation sim = c.simulation();
                if (sim == null) return "the simulation is not running";
                int size = Math.max(sim.settings().hordeFormSize(), LuaArgs.toInt(n, 300));
                float ppx = LuaArgs.toFloat(px, 0), ppy = LuaArgs.toFloat(py, 0);
                float[] spot;
                if (x != null && y != null) {
                    float sx = LuaArgs.toFloat(x, 0), sy = LuaArgs.toFloat(y, 0);
                    spot = new float[] {sx, sy, (float) Math.atan2(ppy - sy, ppx - sx)};
                } else {
                    spot = truehordes.sim.TestHordes.roadSpot(sim.movement().map(), ppx, ppy, 250);
                }
                if (spot == null) return "no highway found 150-350 tiles from you; give x, y";
                truehordes.sim.Horde h = truehordes.sim.TestHordes.toward(sim, c.store(), size, spot[0], spot[1], spot[2],
                    ppx, ppy, new java.util.Random());
                String msg = String.format(java.util.Locale.ROOT, "horde %d of %d at %.0f,%.0f heading for you", h.id,
                    size, spot[0], spot[1]);
                Log.info("Hordes_roadHorde: " + msg);
                return msg;
            }
        } catch (Throwable t) {
            Log.errorOnce("lua:roadHorde:" + t.getClass().getName(), "road horde failed", t);
            return "failed: " + t;
        }
    }

    /** Test command: {@code count} dormant hordes of {@code size} on a circle of {@code radius} round (px, py). */
    @LuaMethod(name = "Hordes_j_ringHordes", global = true)
    public static String ringHordes(Object count, Object size, Object radius, Object px, Object py) {
        Core c = Hooks.core();
        if (c == null || c.state() != Core.State.RUNNING) return NOT_INSTALLED;
        try {
            synchronized (c) {
                truehordes.sim.Simulation sim = c.simulation();
                if (sim == null) return "the simulation is not running";
                int k = Math.max(1, LuaArgs.toInt(count, 6)), s = Math.max(2, LuaArgs.toInt(size, 100));
                float r = LuaArgs.toFloat(radius, 120);
                int placed = truehordes.sim.TestHordes.ring(sim, c.store(), k, s, r, LuaArgs.toFloat(px, 0),
                    LuaArgs.toFloat(py, 0), new java.util.Random());
                String msg = placed + " dormant hordes of " + s + " placed " + (int) r
                    + " tiles round you (any gunshot within 400 tiles sets them loose)";
                Log.info("Hordes_ringHordes: " + msg);
                return msg;
            }
        } catch (Throwable t) {
            Log.errorOnce("lua:ringHordes:" + t.getClass().getName(), "ring hordes failed", t);
            return "failed: " + t;
        }
    }

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
                // Real zombies in view (sub-project 6): x, y, in a horde (1/0), and real hordes: x, y, size.
                KahluaTable rz = LuaManager.platform.newTable();
                int m = 0;
                for (zombie.characters.IsoZombie z : zombie.iso.IsoWorld.instance.currentCell.getZombieList()) {
                    if (z == null || z.isDead() || m >= 4000) continue;
                    float zx = z.getX(), zy = z.getY();
                    if (zx < x0 || zx >= x1 || zy < y0 || zy >= y1) continue;
                    rz.rawset((double) (m * 3 + 1), (double) zx);
                    rz.rawset((double) (m * 3 + 2), (double) zy);
                    rz.rawset((double) (m * 3 + 3), Hooks.PILOTS.hordeIdOf(z) != 0 ? 1.0 : 0.0);
                    m++;
                }
                rz.rawset("n", (double) m);
                t.rawset("real", rz);
                KahluaTable rh = LuaManager.platform.newTable();
                int g = 0;
                for (truehordes.real.Crowd.Group gr : Hooks.PILOTS.realGroups()) {
                    rh.rawset((double) (g * 3 + 1), (double) gr.cx);
                    rh.rawset((double) (g * 3 + 2), (double) gr.cy);
                    rh.rawset((double) (g * 3 + 3), (double) gr.members.size());
                    g++;
                }
                rh.rawset("n", (double) g);
                t.rawset("realHordes", rh);
                return t;
            }
        } catch (Throwable t) {
            Log.errorOnce("lua:snapshot:" + t.getClass().getName(), "snapshot failed", t);
            return null;
        }
    }
}
