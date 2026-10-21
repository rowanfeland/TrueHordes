package truehordes.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consequences while away (sub-project 8): what happened off screen that the game must show when the area loads.
 * Sieges: a horde standing at a spot (a sound, a light, a chase brought it) for some hours, kept per chunk with its
 * biggest crowd and its hours there; the main thread breaks doors and windows near it by the crowd break-in times (D15).
 * Trails: where a big horde walked, its footprint every few tiles; crops there are trampled and, for bigger crowds,
 * fences flattened. Corpses: zombies killed off screen (gunfire meta events, A-Life fights), made into bodies where they fell. Held
 * under the core lock; taken by chunk when the chunk loads.
 */
public final class Aftermath {
    /** A crowd that stood at (x, y): its biggest size and its hours there. */
    public static final class Siege {
        public float x, y;
        public int crowd;
        public double hours;
    }

    /** A big horde's footprint at (x, y): its radius and its biggest crowd. */
    public static final class Trample {
        public float x, y, r;
        public int crowd;
    }

    /** Hordes smaller than this leave no trail (Claude's call, tuning). */
    public static final int TRAMPLE_MIN = 20;
    static final int TRAMPLES_PER_CHUNK = 24;
    /** Rain washes floor blood at this rate per hour of full rain (about three quarters in a day; Claude's call). */
    static final double WASH_PER_HOUR = 1 / 16.0;

    /** The share of blood a rain of {@code rainHours} (hours times intensity) washes away. */
    public static double washed(double rainHours) {
        return rainHours <= 0 ? 0 : 1 - Math.exp(-WASH_PER_HOUR * rainHours);
    }

    /** Sieges this close together are one. */
    static final float SAME = 6f;
    /** At most this many corpses per chunk are kept (the rest are lost, as bodies would be eaten and trampled). */
    static final int CORPSES_PER_CHUNK = 40;

    private final Map<Long, List<Siege>> sieges = new HashMap<>();
    private final Map<Long, List<float[]>> corpses = new HashMap<>();
    private final Map<Long, List<Trample>> tramples = new HashMap<>();

    static long chunk(float x, float y) {
        return SpatialGrid.key((int) Math.floor(x / 8), (int) Math.floor(y / 8));
    }

    /** A crowd of {@code n} stood at (x, y) for {@code hours}. */
    public void linger(float x, float y, int n, double hours) {
        if (n <= 0 || hours <= 0) return;
        List<Siege> list = sieges.computeIfAbsent(chunk(x, y), k -> new ArrayList<>());
        for (Siege s : list) {
            if (Hearing.dist(s.x, s.y, x, y) <= SAME) {
                s.crowd = Math.max(s.crowd, n);
                s.hours += hours;
                return;
            }
        }
        Siege s = new Siege();
        s.x = x;
        s.y = y;
        s.crowd = n;
        s.hours = hours;
        list.add(s);
    }

    /** A horde of {@code n} with footprint radius {@code r} walked from (x0, y0) to (x1, y1). */
    public void trample(float x0, float y0, float x1, float y1, float r, int n) {
        if (n < TRAMPLE_MIN) return;
        r = Math.max(2, Math.min(8, r));
        float len = Hearing.dist(x0, y0, x1, y1);
        int steps = Math.max(1, (int) Math.ceil(len / r));
        for (int i = 1; i <= steps; i++) {
            float f = (float) i / steps;
            footprint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, r, n);
        }
    }

    private void footprint(float x, float y, float r, int n) {
        List<Trample> list = tramples.computeIfAbsent(chunk(x, y), k -> new ArrayList<>());
        for (Trample t : list) {
            if (Hearing.dist(t.x, t.y, x, y) <= Math.max(t.r, r) * 0.75f) {
                t.crowd = Math.max(t.crowd, n);
                t.r = Math.max(t.r, r);
                return;
            }
        }
        if (list.size() >= TRAMPLES_PER_CHUNK) return;
        Trample t = new Trample();
        t.x = x;
        t.y = y;
        t.r = r;
        t.crowd = n;
        list.add(t);
    }

    public List<Trample> takeTramples(int chunkX, int chunkY) {
        List<Trample> l = tramples.remove(SpatialGrid.key(chunkX, chunkY));
        return l == null ? List.of() : l;
    }

    public int trampleCount() {
        int n = 0;
        for (List<Trample> l : tramples.values()) n += l.size();
        return n;
    }

    /** A zombie killed off screen at (x, y, z) wearing {@code outfit}. */
    public void corpse(float x, float y, int z, int outfit) {
        List<float[]> list = corpses.computeIfAbsent(chunk(x, y), k -> new ArrayList<>());
        if (list.size() < CORPSES_PER_CHUNK) list.add(new float[] {x, y, z, outfit});
    }

    public List<Siege> takeSieges(int chunkX, int chunkY) {
        List<Siege> l = sieges.remove(SpatialGrid.key(chunkX, chunkY));
        return l == null ? List.of() : l;
    }

    /** Corpses as {x, y, z, outfit}. */
    public List<float[]> takeCorpses(int chunkX, int chunkY) {
        List<float[]> l = corpses.remove(SpatialGrid.key(chunkX, chunkY));
        return l == null ? List.of() : l;
    }

    public int siegeCount() {
        int n = 0;
        for (List<Siege> l : sieges.values()) n += l.size();
        return n;
    }

    public int corpseCount() {
        int n = 0;
        for (List<float[]> l : corpses.values()) n += l.size();
        return n;
    }

    // ---- save (a line of text each, in events.properties) ----

    public String siegesString() {
        StringBuilder sb = new StringBuilder();
        for (List<Siege> l : sieges.values()) {
            for (Siege s : l) sb.append(s.x).append(',').append(s.y).append(',').append(s.crowd).append(',').append(s.hours).append(';');
        }
        return sb.toString();
    }

    public String corpsesString() {
        StringBuilder sb = new StringBuilder();
        for (List<float[]> l : corpses.values()) {
            for (float[] c : l) sb.append(c[0]).append(',').append(c[1]).append(',').append((int) c[2]).append(',').append((int) c[3]).append(';');
        }
        return sb.toString();
    }

    public String tramplesString() {
        StringBuilder sb = new StringBuilder();
        for (List<Trample> l : tramples.values()) {
            for (Trample t : l) sb.append(t.x).append(',').append(t.y).append(',').append(t.r).append(',').append(t.crowd).append(';');
        }
        return sb.toString();
    }

    public void load(String siegeText, String corpseText) { load(siegeText, corpseText, ""); }

    public void load(String siegeText, String corpseText, String trampleText) {
        sieges.clear();
        corpses.clear();
        tramples.clear();
        for (String e : trampleText == null ? new String[0] : trampleText.split(";")) {
            String[] f = e.split(",");
            if (f.length < 4) continue;
            footprint(Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]), Integer.parseInt(f[3]));
        }
        for (String e : siegeText == null ? new String[0] : siegeText.split(";")) {
            String[] f = e.split(",");
            if (f.length < 4) continue;
            linger(Float.parseFloat(f[0]), Float.parseFloat(f[1]), Integer.parseInt(f[2]), Double.parseDouble(f[3]));
        }
        for (String e : corpseText == null ? new String[0] : corpseText.split(";")) {
            String[] f = e.split(",");
            if (f.length < 4) continue;
            corpse(Float.parseFloat(f[0]), Float.parseFloat(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
        }
    }
}
