package truehordes;

import truehordes.sim.Simulation;
import truehordes.sim.Weather;
import zombie.iso.weather.ClimateManager;

/**
 * Hands the simulation the weather (sub-project 5b spec goal 4), main thread. With True Weathers' regional weather its
 * compatibility layer (Lua, media/lua/server/TrueHordes/Hordes_Compat_TrueWeathers.lua) samples precipitation into a
 * coarse grid here; without it (or when its samples stop) the whole map shares vanilla's precipitation. Real zombies
 * are muffled here too, unless True Weathers' own weather hearing is on (it already muffles them: no double muffling).
 */
final class WeatherBridge {
    /** Grid cell size in tiles. */
    static final int STEP = 300;
    static final long PUBLISH_MS = 5000, STALE_MS = 60000;

    private int originX, originY, width, height;
    private float[] grid;
    private long lastSampleMs = Long.MIN_VALUE / 2, lastPublishMs;
    private volatile Weather current = Weather.CLEAR;
    boolean on = true;
    float maxMuffleDb = Weather.MAX_MUFFLE_DB;
    private boolean twHearing;

    void readSettings() {
        on = GameEnvImpl.sandboxBool("TrueHordes.WeatherHearing", true);
        maxMuffleDb = (float) Math.max(0, GameEnvImpl.sandboxDouble("TrueHordes.WeatherMuffleDb", Weather.MAX_MUFFLE_DB));
        // True Weathers Gameplay is active when its sandbox options exist; its rain masking muffles real zombies.
        twHearing = GameEnvImpl.sandboxBool("TWGameplay.RainMasksNoise", false);
    }

    /** {originX, originY, step, width, height} of the grid for the map (tiles), or null before the map is known. */
    synchronized int[] spec(truehordes.world.World world) {
        if (grid == null) {
            int[] b = world == null ? null : world.tileBounds();
            if (b == null) return null;
            originX = b[0];
            originY = b[1];
            width = Math.max(1, (b[2] - b[0] + STEP - 1) / STEP);
            height = Math.max(1, (b[3] - b[1] + STEP - 1) / STEP);
            grid = new float[width * height];
        }
        return new int[] {originX, originY, STEP, width, height};
    }

    /** One sample from the compatibility layer. */
    synchronized void set(int i, float precip) {
        if (grid == null || i < 0 || i >= grid.length) return;
        grid[i] = precip;
        lastSampleMs = System.currentTimeMillis();
    }

    void tick(truehordes.core.Core core, long nowMs) {
        if (nowMs - lastPublishMs < PUBLISH_MS) return;
        lastPublishMs = nowMs;
        Weather w;
        if (!on) {
            w = Weather.CLEAR;
        } else {
            synchronized (this) {
                if (grid != null && nowMs - lastSampleMs < STALE_MS) {
                    w = Weather.regional(originX, originY, STEP, width, height, grid, maxMuffleDb);
                } else {
                    float p = ClimateManager.getInstance() == null ? 0f : ClimateManager.getInstance().getPrecipitationIntensity();
                    w = Weather.uniform(p, maxMuffleDb);
                }
            }
        }
        current = w;
        Simulation sim = core.soundSink();
        if (sim != null) sim.setWeather(w);
    }

    /** dB a real zombie at (lx, ly) loses for a sound at (sx, sy), or 0 when True Weathers already muffles it. */
    float realLossDb(float sx, float sy, float lx, float ly, boolean thunder) {
        if (!on) return 0f;
        Weather w = current;
        // Ignoring thunder inside a storm is not muffling: it applies alongside True Weathers' own hearing.
        if (thunder && w.inStorm(lx, ly)) return Float.POSITIVE_INFINITY;
        if (twHearing) return 0f;
        return thunder ? w.muffleDb(lx, ly, lx, ly) : w.muffleDb(sx, sy, lx, ly);
    }

    void clear() {
        synchronized (this) {
            grid = null;
            lastSampleMs = Long.MIN_VALUE / 2;
        }
        current = Weather.CLEAR;
    }
}
