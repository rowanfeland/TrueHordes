package truehordes.events;

import java.util.function.DoubleSupplier;
import truehordes.sim.MapView;

/**
 * Where a meta event happens (sub-project 5b spec goal 1): a random spot in a distance band from the player (far
 * events 300-1000 tiles, near ones 50-100), never closer than the band to any player, on ground a person could stand
 * on, matching the kind: towns and roads, the countryside, or anywhere.
 */
public final class Placement {
    private Placement() {}

    public enum Where { TOWN, RURAL, ANYWHERE }

    public static final float FAR_MIN = 300, FAR_MAX = 1000, NEAR_MIN = 50, NEAR_MAX = 100;
    static final int TRIES = 48;

    /**
     * A spot {@code [x, y]} between {@code min} and {@code max} tiles from player {@code (px, py)} and at least
     * {@code min} from every player in {@code players} ({x0, y0, x1, y1, ...}), or null if none is found.
     */
    public static float[] pick(MapView map, Where where, float px, float py, float[] players, float min, float max,
                               DoubleSupplier u) {
        float[] fallback = null;
        for (int i = 0; i < TRIES; i++) {
            double a = u.getAsDouble() * 2 * Math.PI, d = min + u.getAsDouble() * (max - min);
            float x = (float) (px + Math.cos(a) * d), y = (float) (py + Math.sin(a) * d);
            int cx = (int) Math.floor(x / 8), cy = (int) Math.floor(y / 8);
            if (map.rank(cx, cy) == 0 || tooClose(x, y, players, min)) continue;
            if (fits(map, where, cx, cy)) return new float[] {x, y};
            if (fallback == null) fallback = new float[] {x, y};
        }
        return fallback;
    }

    static boolean tooClose(float x, float y, float[] players, float min) {
        for (int i = 0; i + 1 < players.length; i += 2) {
            if (Math.hypot(players[i] - x, players[i + 1] - y) < min - 0.5f) return true;
        }
        return false;
    }

    /** Town: a road, or buildings within two chunks; rural: no building within two chunks; anywhere: any ground. */
    static boolean fits(MapView map, Where where, int cx, int cy) {
        if (where == Where.ANYWHERE) return true;
        boolean town = map.rank(cx, cy) == 1 || buildingsNear(map, cx, cy);
        return where == Where.TOWN ? town : !buildingsNear(map, cx, cy);
    }

    static boolean buildingsNear(MapView map, int cx, int cy) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) if (map.building(cx + dx, cy + dy)) return true;
        }
        return false;
    }
}
