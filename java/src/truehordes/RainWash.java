package truehordes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import truehordes.sim.Aftermath;
import truehordes.sim.Weather;
import truehordes.util.Log;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoFloorBloodSplat;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.weather.ClimateManager;

/**
 * Rain washing blood away (sub-project 8). While it rains (not snow), floor blood on open ground near the players fades
 * a little at a time, most of it after a day of steady rain ({@link Aftermath#washed}). Rain is kept map wide on the
 * weather grid ({@link WeatherBridge}, 300-tile cells): with True Weathers it rains where that mod says, else
 * everywhere alike. An area left with blood remembers how much rain its cell had had; when it loads again it is washed
 * by the rain that fell there since. Main thread, everywhere the game holds chunks (a multiplayer client washes what it
 * sees; the server what it saves).
 */
final class RainWash {
    static final long CHECK_MS = 10000;
    static final int MAX_LEFT = 20000;

    boolean on = true;
    private long lastMs;
    private double lastHours = Double.NaN;
    /** The weather grid {originX, originY, step, width, height}, and each cell's rain so far (hours of full rain). */
    private int[] spec;
    private double[] cellRain;
    /** Chunks unloaded with blood: their cell's rain when they were left. */
    private final Map<Long, Double> leftAt = new HashMap<>();

    void readSettings() {
        on = GameEnvImpl.sandboxBool("TrueHordes.RainWashesBlood", true);
    }

    static long key(int wx, int wy) { return ((long) wx << 32) ^ (wy & 0xffffffffL); }

    private int cell(float x, float y) {
        if (spec == null) return -1;
        int cx = (int) Math.floor((x - spec[0]) / spec[2]), cy = (int) Math.floor((y - spec[1]) / spec[2]);
        return cx < 0 || cy < 0 || cx >= spec[3] || cy >= spec[4] ? -1 : cy * spec[3] + cx;
    }

    private boolean ready(truehordes.core.Core c) {
        if (spec != null) return true;
        int[] s = c == null ? null : Hooks.WEATHER.spec(c.world());
        if (s == null) return false;
        spec = s;
        cellRain = new double[s[3] * s[4]];
        return true;
    }

    void tick(truehordes.core.Core c, long now) {
        if (!on || now - lastMs < CHECK_MS) return;
        lastMs = now;
        double h = GameTime.getInstance().getWorldAgeHours();
        double dt = Double.isNaN(lastHours) ? 0 : Math.max(0, Math.min(1, h - lastHours));
        lastHours = h;
        if (dt <= 0 || ClimateManager.getInstance().getPrecipitationIsSnow()) return;
        Weather w = Hooks.WEATHER.rain();
        if (ready(c)) {
            for (int i = 0; i < cellRain.length; i++) {
                float x = spec[0] + (i % spec[3] + 0.5f) * spec[2], y = spec[1] + (i / spec[3] + 0.5f) * spec[2];
                cellRain[i] += w.precipAt(x, y) * dt;
            }
        }
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        int reach = Math.max(1, IsoChunkMap.chunkWidthInTiles / 2 / 8);
        Set<Long> done = new HashSet<>();
        for (IsoPlayer p : EventDirector.livePlayers()) {
            int cx = (int) Math.floor(p.getX() / 8), cy = (int) Math.floor(p.getY() / 8);
            for (int wx = cx - reach; wx <= cx + reach; wx++) {
                for (int wy = cy - reach; wy <= cy + reach; wy++) {
                    if (!done.add(key(wx, wy))) continue;
                    float rain = w.precipAt(wx * 8 + 4, wy * 8 + 4);
                    if (rain <= 0) continue;
                    IsoChunk ch = cell.getChunk(wx, wy);
                    if (ch != null) wash(ch, Aftermath.washed(rain * dt));
                }
            }
        }
    }

    /** Removes about {@code share} of the chunk's floor blood on open ground. */
    static void wash(IsoChunk chunk, double share) {
        if (share <= 0 || chunk.floorBloodSplats.isEmpty()) return;
        for (int i = chunk.floorBloodSplats.size() - 1; i >= 0; i--) {
            IsoFloorBloodSplat s = chunk.floorBloodSplats.get(i);
            IsoGridSquare sq = chunk.getGridSquare((int) s.x, (int) s.y, (int) s.z);
            if (sq != null && sq.isOutside() && ThreadLocalRandom.current().nextDouble() < share) chunk.floorBloodSplats.remove(i);
        }
    }

    /** Where the population is owned: an area with blood unloads. */
    void onUnloading(IsoChunk chunk) {
        if (!on || chunk == null || chunk.floorBloodSplats.isEmpty() || leftAt.size() >= MAX_LEFT) return;
        int i = cell(chunk.wx * 8 + 4, chunk.wy * 8 + 4);
        if (i >= 0) leftAt.put(key(chunk.wx, chunk.wy), cellRain[i]);
    }

    /** Where the population is owned: an area loads; the rain that fell on it since it was left washes it. */
    void onLoaded(IsoChunk chunk) {
        if (chunk == null) return;
        Double at = leftAt.remove(key(chunk.wx, chunk.wy));
        int i = cell(chunk.wx * 8 + 4, chunk.wy * 8 + 4);
        if (!on || at == null || i < 0) return;
        try {
            wash(chunk, Aftermath.washed(cellRain[i] - at));
        } catch (Throwable t) {
            Log.errorOnce("rainwash:" + t.getClass().getName(), "washing blood in a loaded area failed", t);
        }
    }

    // ---- save: "originX,originY,step,width,height|rain,rain,...|wx,wy,at;..." ----

    String saveString() {
        if (spec == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(spec[i]).append(i < 4 ? ',' : '|');
        for (double r : cellRain) sb.append((float) r).append(',');
        sb.append('|');
        for (Map.Entry<Long, Double> e : leftAt.entrySet()) {
            sb.append((int) (e.getKey() >> 32)).append(',').append((int) (long) e.getKey()).append(',')
                .append(e.getValue().floatValue()).append(';');
        }
        return sb.toString();
    }

    void loadString(String s) {
        leftAt.clear();
        spec = null;
        cellRain = null;
        if (s == null || s.isEmpty()) return;
        String[] parts = s.split("\\|", -1);
        if (parts.length < 3) return; // an older save: start the rain count afresh
        String[] f = parts[0].split(",");
        int[] sp = new int[5];
        for (int i = 0; i < 5; i++) sp[i] = Integer.parseInt(f[i]);
        String[] r = parts[1].split(",");
        if (r.length != sp[3] * sp[4]) return;
        spec = sp;
        cellRain = new double[r.length];
        for (int i = 0; i < r.length; i++) cellRain[i] = Double.parseDouble(r[i]);
        for (String e : parts[2].split(";")) {
            String[] g = e.split(",");
            if (g.length < 3) continue;
            leftAt.put(key(Integer.parseInt(g[0]), Integer.parseInt(g[1])), Double.parseDouble(g[2]));
        }
    }
}
