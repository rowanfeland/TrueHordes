package truehordes.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import truehordes.util.Log;

/**
 * Bridge to Viewpoint (the 3D camera mod): where a world point lands on screen as Viewpoint draws it. Far sight needs
 * it because Viewpoint replaces the isometric view, so the game's isoToScreen puts things in the wrong place.
 *
 * <p>Reflection only, against Viewpoint's own world-to-screen helper (the one it places name tags with). Nothing of
 * Viewpoint is compiled in: if an update renames or changes anything, the bridge logs once and turns itself off, and
 * far sight falls back to the 2D view. Main thread only.
 */
public final class ViewpointBridge {
    private ViewpointBridge() {}

    private static boolean tried, ok;
    private static Field enabled, frames, freeActive, freePlace;
    private static Method set, pixel;
    private static Object screen;
    private static final float[] px = new float[2];
    private static float sx, sy, sh;

    private static Class<?> find(String name) throws ClassNotFoundException {
        ClassLoader[] loaders = {ViewpointBridge.class.getClassLoader(), Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader(), zombie.GameTime.class.getClassLoader()};
        for (ClassLoader l : loaders) {
            if (l == null) continue;
            try {
                return Class.forName(name, false, l);
            } catch (ClassNotFoundException ignored) {
                // next loader
            }
        }
        throw new ClassNotFoundException(name);
    }

    private static boolean init() {
        if (tried) return ok;
        tried = true;
        try {
            Class<?> view = find("viewpoint.core.View");
            enabled = view.getField("enabled");
            Class<?> fp = find("viewpoint.FP");
            frames = fp.getDeclaredField("frames");
            frames.setAccessible(true);
            Class<?> free = find("viewpoint.input.FreeCam");
            freeActive = free.getDeclaredField("active");
            freeActive.setAccessible(true);
            freePlace = free.getDeclaredField("place");
            freePlace.setAccessible(true);
            Class<?> w2s = find("viewpoint.game.WorldToScreen");
            Class<?> frame = find("viewpoint.core.Frame");
            Class<?> place = find("viewpoint.input.FreeCam$Place");
            Constructor<?> c = w2s.getDeclaredConstructor();
            c.setAccessible(true);
            screen = c.newInstance();
            set = w2s.getDeclaredMethod("set", frame, place, float.class, float.class);
            set.setAccessible(true);
            pixel = w2s.getDeclaredMethod("pixel", float.class, float.class, float.class, float.class, float[].class);
            pixel.setAccessible(true);
            ok = true;
            Log.info("Viewpoint found: far sight draws in its 3D view");
        } catch (ClassNotFoundException e) {
            ok = false; // Viewpoint not installed
        } catch (Throwable t) {
            ok = false;
            Log.info("Viewpoint changed in a way the far-sight bridge does not know; far sight uses the 2D view ("
                + t + ")");
        }
        return ok;
    }

    /** Viewpoint is drawing player {@code pn}'s view in 3D: sets this frame's projection. False = use 2D. */
    public static boolean begin(int pn) {
        if (!init()) return false;
        try {
            if (!enabled.getBoolean(null)) return false;
            Object[] fs = (Object[]) frames.get(null);
            if (fs == null || pn < 0 || pn >= fs.length || fs[pn] == null) return false;
            Object place = freeActive.getBoolean(null) ? freePlace.get(null) : null;
            set.invoke(screen, fs[pn], place, (float) zombie.iso.IsoCamera.getScreenWidth(pn),
                (float) zombie.iso.IsoCamera.getScreenHeight(pn));
            return true;
        } catch (Throwable t) {
            ok = false;
            Log.errorOnce("viewpoint:begin", "Viewpoint bridge failed; far sight uses the 2D view", t);
            return false;
        }
    }

    private static Method farReach;
    private static boolean reachTried;

    /**
     * How far Viewpoint's far world draws, in tiles (its "Far world up to" setting in 64-tile blocks; a tile is a
     * metre, so 64 blocks is about 4.1 km), or 0 when Viewpoint is off, missing or changed.
     */
    public static float farReach() {
        if (!init()) return 0;
        try {
            if (!enabled.getBoolean(null)) return 0;
            if (!reachTried) {
                reachTried = true;
                farReach = find("viewpoint.far.FarWorld").getMethod("reach");
            }
            return farReach == null ? 0 : Math.max(0, ((Number) farReach.invoke(null)).floatValue());
        } catch (Throwable t) {
            farReach = null;
            Log.errorOnce("viewpoint:reach", "reading Viewpoint's far-world reach failed; far sight keeps its own reach", t);
            return 0;
        }
    }

    /** How tall a standing figure is, in Viewpoint's height units (metres). */
    static final float FIGURE = 1.75f;

    /**
     * A standing figure at world (x, y, z), after {@link #begin}: its feet on screen ({@link #x}, {@link #y}) and its
     * height in pixels ({@link #h}). False when it is behind the camera.
     */
    public static boolean figure(float x, float y, float z) {
        try {
            Object r = pixel.invoke(screen, x, y, z, FIGURE, px);
            if (!(r instanceof Boolean b) || !b) return false;
            float headY = px[1];
            r = pixel.invoke(screen, x, y, z, 0f, px);
            if (!(r instanceof Boolean b2) || !b2) return false;
            sx = px[0];
            sy = px[1];
            sh = Math.max(1f, sy - headY);
            return true;
        } catch (Throwable t) {
            ok = false;
            Log.errorOnce("viewpoint:pixel", "Viewpoint bridge failed; far sight uses the 2D view", t);
            return false;
        }
    }

    public static float x() { return sx; }

    public static float y() { return sy; }

    public static float h() { return sh; }
}
