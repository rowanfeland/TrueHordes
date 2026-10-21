package truehordes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import truehordes.sim.Aftermath;
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
 * a little at a time, most of it after a day of steady rain ({@link Aftermath#washed}). An area left with blood on it
 * remembers how much rain had fallen; when it loads again it is washed by the rain since. Main thread, everywhere the
 * game holds chunks (a multiplayer client washes what it sees; the server what it saves).
 */
final class RainWash {
    static final long CHECK_MS = 10000;
    static final int MAX_LEFT = 20000;

    boolean on = true;
    private long lastMs;
    private double lastHours = Double.NaN;
    /** Rain fallen since the world began, in hours of full rain. */
    private double rainHours;
    /** Chunks unloaded with blood: the rain fallen when they were left. */
    private final Map<Long, Double> leftAt = new HashMap<>();

    void readSettings() {
        on = GameEnvImpl.sandboxBool("TrueHordes.RainWashesBlood", true);
    }

    static long key(int wx, int wy) { return ((long) wx << 32) ^ (wy & 0xffffffffL); }

    void tick(long now) {
        if (!on || now - lastMs < CHECK_MS) return;
        lastMs = now;
        double h = GameTime.getInstance().getWorldAgeHours();
        double dt = Double.isNaN(lastHours) ? 0 : Math.max(0, Math.min(1, h - lastHours));
        lastHours = h;
        ClimateManager cm = ClimateManager.getInstance();
        float rain = cm.getPrecipitationIsSnow() ? 0 : cm.getRainIntensity();
        if (rain <= 0 || dt <= 0) return;
        double r = rain * dt;
        rainHours += r;
        double share = Aftermath.washed(r);
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        int reach = Math.max(1, IsoChunkMap.chunkWidthInTiles / 2 / 8);
        Set<Long> done = new HashSet<>();
        for (IsoPlayer p : EventDirector.livePlayers()) {
            int cx = (int) Math.floor(p.getX() / 8), cy = (int) Math.floor(p.getY() / 8);
            for (int wx = cx - reach; wx <= cx + reach; wx++) {
                for (int wy = cy - reach; wy <= cy + reach; wy++) {
                    if (!done.add(key(wx, wy))) continue;
                    IsoChunk c = cell.getChunk(wx, wy);
                    if (c != null) wash(c, share);
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
        leftAt.put(key(chunk.wx, chunk.wy), rainHours);
    }

    /** Where the population is owned: an area loads; the rain since it was left washes it. */
    void onLoaded(IsoChunk chunk) {
        if (chunk == null) return;
        Double at = leftAt.remove(key(chunk.wx, chunk.wy));
        if (!on || at == null) return;
        try {
            wash(chunk, Aftermath.washed(rainHours - at));
        } catch (Throwable t) {
            Log.errorOnce("rainwash:" + t.getClass().getName(), "washing blood in a loaded area failed", t);
        }
    }

    // ---- save: "rainHours|wx,wy,at;..." ----

    String saveString() {
        StringBuilder sb = new StringBuilder().append(rainHours).append('|');
        for (Map.Entry<Long, Double> e : leftAt.entrySet()) {
            sb.append((int) (e.getKey() >> 32)).append(',').append((int) (long) e.getKey()).append(',').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    void loadString(String s) {
        leftAt.clear();
        if (s == null || s.isEmpty()) return;
        String[] parts = s.split("\\|", 2);
        rainHours = Double.parseDouble(parts[0]);
        if (parts.length < 2) return;
        for (String e : parts[1].split(";")) {
            String[] f = e.split(",");
            if (f.length < 3) continue;
            leftAt.put(key(Integer.parseInt(f[0]), Integer.parseInt(f[1])), Double.parseDouble(f[2]));
        }
    }
}
