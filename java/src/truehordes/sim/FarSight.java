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
        return nearest(collect(sim, px, py, reach, max), max);
    }

    /** The candidates {x, y, heading, speed, distance} (under the core's lock; at most {@code max} per horde). */
    public static List<float[]> collect(Simulation sim, float px, float py, float reach, int max) {
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
            int taken = 0;
            for (ZombieRecord m : h.members) {
                if (taken >= max) break;
                long k = Movement.mix(m.id * 0x9E3779B97F4A7C15L);
                double a = (k & 0xFFFF) / 65536.0 * 2 * Math.PI, rr = spread * Math.sqrt(((k >>> 16) & 0xFFFF) / 65536.0);
                float x = (float) (h.x + Math.cos(a) * rr), y = (float) (h.y + Math.sin(a) * rr);
                if (inLoaded(loaded, x, y)) continue;
                float d = Hearing.dist(px, py, x, y);
                if (d <= reach) {
                    out.add(new float[] {x, y, h.heading, v, d});
                    taken++;
                }
            }
        }
        return out;
    }

    /** Floats per far mark: x, y, heading, speed, radius (0: one zombie; else a horde drawn as a crowd that wide). */
    public static final int FAR_STRIDE = 5;

    /**
     * The far view, low detail (user, 2026-10-21: as far as Viewpoint's far world draws, up to 4,096 tiles): beyond
     * {@code near} and out to {@code far} from (px, py), each horde one crowd mark (the nearest {@code maxHordes}) and
     * lone zombies single marks (the nearest {@code maxSingles}), none in a loaded chunk. Under the core's lock.
     */
    public static float[] farMarks(Simulation sim, float px, float py, float near, float far, int maxHordes, int maxSingles) {
        PlayerNearby loaded = sim.players();
        SimSettings s = sim.settings();
        Movement mv = sim.movement();
        double now = sim.currentHours();
        java.util.PriorityQueue<float[]> singles = new java.util.PriorityQueue<>((a, b) -> Float.compare(b[5], a[5]));
        if (maxSingles > 0) {
            sim.grid().wanderersIn(px - far, py - far, px + far, py + far, r -> {
                float d = Hearing.dist(px, py, r.x(), r.y());
                if (d <= near || d > far || r.idle) return;
                if (singles.size() >= maxSingles && d >= singles.peek()[5]) return;
                if (inLoaded(loaded, r.x(), r.y())) return;
                boolean walking = r.lingerUntilHours <= now && r.pauseMinutes <= 0;
                float v = walking ? mv.speedTilesPerGameMinute(1, s.speedSetting(), r.id, (r.stateFlags & 2) != 0) : 0;
                singles.add(new float[] {r.x(), r.y(), r.heading, v, 0, d});
                if (singles.size() > maxSingles) singles.poll();
            });
        }
        List<float[]> crowds = new ArrayList<>();
        for (Horde h : sim.hordes().all()) {
            if (h.dormant) continue;
            float d = Hearing.dist(px, py, h.x, h.y);
            if (d <= near || d - h.spread() > far || inLoaded(loaded, h.x, h.y)) continue;
            float v = h.pauseMinutes > 0 ? 0 : mv.speedTilesPerGameMinute(h.size(), s.speedSetting(), h.id, h.allCrawlers());
            crowds.add(new float[] {h.x, h.y, h.heading, v, Math.max(1, h.spread()), d});
        }
        crowds.sort((a, b) -> Float.compare(a[5], b[5]));
        int nh = Math.min(maxHordes, crowds.size());
        float[] flat = new float[(nh + singles.size()) * FAR_STRIDE];
        int i = 0;
        for (int k = 0; k < nh; k++, i++) System.arraycopy(crowds.get(k), 0, flat, i * FAR_STRIDE, FAR_STRIDE);
        for (float[] m : singles) System.arraycopy(m, 0, flat, i++ * FAR_STRIDE, FAR_STRIDE);
        return flat;
    }

    /** The nearest {@code max} candidates, flattened (no lock needed). */
    public static float[] nearest(List<float[]> out, int max) {
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
