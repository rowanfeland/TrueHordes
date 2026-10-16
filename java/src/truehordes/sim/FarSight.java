package truehordes.sim;

import java.util.ArrayList;
import java.util.List;
import truehordes.population.PlayerNearby;
import truehordes.store.ZombieRecord;

/**
 * Seeing hordes coming (sub-project 6 spec goal 13, user): held zombies beyond the loaded area, out to a distance, as
 * figures to draw where the simulation has them. Wanderers at their spots; horde members scattered over the horde's
 * circle (a fixed spot per member, so they do not jump about). Each with its heading and walking speed so the client
 * can move it smoothly between refreshes. Used under the core's lock.
 */
public final class FarSight {
    private FarSight() {}

    /** Floats per figure: x, y, heading, speed (tiles per in-game minute). */
    public static final int STRIDE = 4;

    /** Up to {@code max} figures within {@code reach} of (px, py), nearest first, none in a loaded chunk. */
    public static float[] figures(Simulation sim, float px, float py, float reach, int max) {
        PlayerNearby loaded = sim.players();
        SimSettings s = sim.settings();
        Movement mv = sim.movement();
        double now = sim.currentHours();
        List<float[]> out = new ArrayList<>();
        sim.grid().wanderersIn(px - reach, py - reach, px + reach, py + reach, r -> {
            if (inLoaded(loaded, r.x(), r.y())) return;
            float d = Hearing.dist(px, py, r.x(), r.y());
            if (d > reach) return;
            boolean walking = !r.idle && r.lingerUntilHours <= now && r.pauseMinutes <= 0;
            float v = walking ? mv.speedTilesPerGameMinute(1, s.speedSetting(), r.id, (r.stateFlags & 2) != 0) : 0;
            out.add(new float[] {r.x(), r.y(), r.heading, v, d});
        });
        for (Horde h : sim.hordes().all()) {
            if (h.dormant) continue; // inside buildings, unseen
            float hd = Hearing.dist(px, py, h.x, h.y);
            if (hd - h.spread() > reach) continue;
            float v = h.pauseMinutes > 0 ? 0 : mv.speedTilesPerGameMinute(h.size(), s.speedSetting(), h.id, h.allCrawlers());
            float spread = h.spread();
            for (ZombieRecord m : h.members) {
                long k = Movement.mix(m.id * 0x9E3779B97F4A7C15L);
                double a = (k & 0xFFFF) / 65536.0 * 2 * Math.PI, rr = spread * Math.sqrt(((k >>> 16) & 0xFFFF) / 65536.0);
                float x = (float) (h.x + Math.cos(a) * rr), y = (float) (h.y + Math.sin(a) * rr);
                if (inLoaded(loaded, x, y)) continue;
                float d = Hearing.dist(px, py, x, y);
                if (d <= reach) out.add(new float[] {x, y, h.heading, v, d});
            }
        }
        out.sort((a, b) -> Float.compare(a[4], b[4]));
        int n = Math.min(max, out.size());
        float[] flat = new float[n * STRIDE];
        for (int i = 0; i < n; i++) System.arraycopy(out.get(i), 0, flat, i * STRIDE, STRIDE);
        return flat;
    }

    static boolean inLoaded(PlayerNearby p, float x, float y) {
        return p.nearChunk(Math.floorDiv((int) Math.floor(x), 8), Math.floorDiv((int) Math.floor(y), 8));
    }
}
