package truehordes.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consequences while away (sub-project 8): what happened off screen that the game must show when the area loads.
 * Sieges: a horde standing at a spot (a sound, a light, a chase brought it) for some hours, kept per chunk with its
 * biggest crowd and its hours there; the main thread breaks doors and windows near it by the crowd break-in times (D15).
 * Corpses: zombies killed off screen (gunfire meta events, A-Life fights), made into bodies where they fell. Held
 * under the core lock; taken by chunk when the chunk loads.
 */
public final class Aftermath {
    /** A crowd that stood at (x, y): its biggest size and its hours there. */
    public static final class Siege {
        public float x, y;
        public int crowd;
        public double hours;
    }

    /** Sieges this close together are one. */
    static final float SAME = 6f;
    /** At most this many corpses per chunk are kept (the rest are lost, as bodies would be eaten and trampled). */
    static final int CORPSES_PER_CHUNK = 40;

    private final Map<Long, List<Siege>> sieges = new HashMap<>();
    private final Map<Long, List<float[]>> corpses = new HashMap<>();

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

    public void load(String siegeText, String corpseText) {
        sieges.clear();
        corpses.clear();
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
