package truehordes.sim;

import java.util.ArrayList;
import java.util.List;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;

/**
 * Test commands (sub-project 6 spec goal 14, user): a horde walking down the nearest highway towards the player, and a
 * ring of dormant hordes round the player that a sound sets loose. Both are added as held zombies (a population
 * change, counted by the conservation checks). Used under the core's lock.
 */
public final class TestHordes {
    private TestHordes() {}

    static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    /** A highway lane (a road chunk in a 2x2 block of road) about {@code dist} tiles from (px, py): {x, y, heading}, or null. */
    public static float[] roadSpot(MapView map, float px, float py, float dist) {
        float[] best = null;
        float bestErr = Float.MAX_VALUE;
        for (float r = dist * 0.6f; r <= dist * 1.4f; r += 8) {
            for (int a = 0; a < 360; a += 2) {
                double t = Math.toRadians(a);
                int cx = Math.floorDiv((int) Math.floor(px + Math.cos(t) * r), 8);
                int cy = Math.floorDiv((int) Math.floor(py + Math.sin(t) * r), 8);
                if (map.rank(cx, cy) != 1 || !wide(map, cx, cy)) continue;
                float err = Math.abs(r - dist);
                if (err >= bestErr) continue;
                // Along the road, the way that faces the player most.
                double toPlayer = Math.atan2(py - (cy * 8 + 4), px - (cx * 8 + 4));
                int bestD = -1;
                double bestCos = -2;
                for (int d = 0; d < 8; d++) {
                    if (map.rank(cx + DX[d], cy + DY[d]) != 1 || map.rank(cx - DX[d], cy - DY[d]) != 1) continue;
                    double c = Math.cos(d * Math.PI / 4 - toPlayer);
                    if (c > bestCos) {
                        bestCos = c;
                        bestD = d;
                    }
                }
                if (bestD < 0) continue;
                bestErr = err;
                best = new float[] {cx * 8 + 4, cy * 8 + 4, (float) Movement.normalise((float) (bestD * Math.PI / 4))};
            }
            if (best != null && bestErr <= 8) break;
        }
        return best;
    }

    static boolean wide(MapView map, int cx, int cy) {
        for (int ox = -1; ox <= 0; ox++) {
            for (int oy = -1; oy <= 0; oy++) {
                if (map.rank(cx + ox, cy + oy) == 1 && map.rank(cx + ox + 1, cy + oy) == 1
                        && map.rank(cx + ox, cy + oy + 1) == 1 && map.rank(cx + ox + 1, cy + oy + 1) == 1) return true;
            }
        }
        return false;
    }

    /** n held zombies scattered in a horde's circle at (x, y), on walkable ground. */
    static List<ZombieRecord> members(Simulation sim, Store store, int n, float x, float y, boolean idle, java.util.Random rnd) {
        float spread = Horde.spreadOf(n);
        MapView map = sim.movement().map();
        List<ZombieRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float zx = x, zy = y;
            for (int t = 0; t < 8; t++) {
                double a = rnd.nextDouble() * 2 * Math.PI, d = spread * Math.sqrt(rnd.nextDouble());
                float tx = (float) (x + Math.cos(a) * d), ty = (float) (y + Math.sin(a) * d);
                if (map.rank(Math.floorDiv((int) Math.floor(tx), 8), Math.floorDiv((int) Math.floor(ty), 8)) > 0) {
                    zx = tx;
                    zy = ty;
                    break;
                }
            }
            ZombieRecord r = store.add(zx, zy, 0f, (byte) rnd.nextInt(8), 0, 0, 0, ZombieRecord.NO_TARGET,
                ZombieRecord.NO_TARGET, null, false);
            r.idle = idle;
            r.strRolled = true;
            out.add(r);
        }
        return out;
    }

    /** A walking horde of n at (x, y) with the heading; returns it. */
    public static Horde walking(Simulation sim, Store store, int n, float x, float y, float heading, java.util.Random rnd) {
        List<ZombieRecord> ms = members(sim, store, n, x, y, false, rnd);
        Horde[] h = new Horde[1];
        sim.internal(() -> {
            for (ZombieRecord r : ms) store.remove(r.id);
            h[0] = sim.hordes().create(x, y, heading, ms);
            sim.grid().putHorde(h[0]);
        });
        return h[0];
    }

    /** Ground near (x, y) a horde can stand on (searching outwards up to 4 chunks), or null. */
    public static float[] ground(MapView map, float x, float y) {
        int cx = Math.floorDiv((int) Math.floor(x), 8), cy = Math.floorDiv((int) Math.floor(y), 8);
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int rank = map.rank(cx + dx, cy + dy);
                    if (rank > 0 && !map.building(cx + dx, cy + dy)) return new float[] {(cx + dx) * 8 + 4, (cy + dy) * 8 + 4};
                }
            }
        }
        return null;
    }

    /**
     * {@code count} dormant hordes of {@code size} spaced evenly on a circle of {@code radius} round (px, py): they wait
     * until a sound or a louder horde's moaning sets them loose. Returns how many were placed.
     */
    public static int ring(Simulation sim, Store store, int count, int size, float radius, float px, float py,
                           java.util.Random rnd) {
        int placed = 0;
        for (int k = 0; k < count; k++) {
            double a = 2 * Math.PI * k / count;
            float[] g = ground(sim.movement().map(), (float) (px + Math.cos(a) * radius), (float) (py + Math.sin(a) * radius));
            if (g == null) continue;
            List<ZombieRecord> ms = members(sim, store, size, g[0], g[1], true, rnd);
            sim.internal(() -> {
                for (ZombieRecord r : ms) store.remove(r.id);
                Horde h = sim.hordes().createDormant(g[0], g[1], ms);
                sim.grid().putHorde(h);
            });
            placed++;
        }
        return placed;
    }
}
