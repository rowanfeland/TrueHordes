package truehordes.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rain and storms across the map (sub-project 5b spec goal 4), an immutable snapshot the main thread hands the
 * simulation. With True Weathers' regional weather it is a coarse grid of precipitation (0..1) sampled from that mod;
 * without it the whole map shares vanilla's precipitation. Rain muffles every sound by up to {@link #MAX_MUFFLE_DB}
 * at full strength; zombies inside a storm ignore thunder; storms away from the players thunder too.
 */
public final class Weather {
    /** Loudest muffling, at full rain (Claude's call, tuning). */
    public static final float MAX_MUFFLE_DB = 12f;
    /** Rain at least this strong counts as inside the storm for thunder (Claude's call). */
    public static final float IN_STORM = 0.2f;
    /** Cells at least this wet make a thundering storm (Claude's call). */
    public static final float THUNDER_STORM = 0.5f;

    public static final Weather CLEAR = new Weather(0f, 0, 0, 1, 0, 0, null, false, MAX_MUFFLE_DB);

    private final float uniform;
    private final int originX, originY, step, width, height;
    private final float[] grid;
    /** True when the grid comes from True Weathers' regional weather. */
    public final boolean regional;
    private final float maxMuffleDb;
    private List<Storm> storms;

    /** A thundering storm: its cells (indices into the grid) and its strongest rain. */
    public record Storm(int[] cells, float peak) {}

    private Weather(float uniform, int originX, int originY, int step, int width, int height, float[] grid,
                    boolean regional, float maxMuffleDb) {
        this.uniform = uniform;
        this.originX = originX;
        this.originY = originY;
        this.step = step;
        this.width = width;
        this.height = height;
        this.grid = grid;
        this.regional = regional;
        this.maxMuffleDb = maxMuffleDb;
    }

    /** The whole map under one precipitation (vanilla weather). */
    public static Weather uniform(float precip, float maxMuffleDb) {
        return new Weather(clamp(precip), 0, 0, 1, 0, 0, null, false, maxMuffleDb);
    }

    /** Regional weather: {@code precip[y * width + x]} for the cell whose corner is origin + (x, y) * step tiles. */
    public static Weather regional(int originX, int originY, int step, int width, int height, float[] precip,
                                   float maxMuffleDb) {
        float[] g = precip.clone();
        for (int i = 0; i < g.length; i++) g[i] = clamp(g[i]);
        return new Weather(0f, originX, originY, Math.max(1, step), width, height, g, true, maxMuffleDb);
    }

    static float clamp(float p) { return Float.isNaN(p) ? 0f : Math.max(0f, Math.min(1f, p)); }

    /** Precipitation 0..1 at tile (x, y); outside the grid, dry. */
    public float precipAt(float x, float y) {
        if (grid == null) return uniform;
        int gx = (int) Math.floor((x - originX) / step), gy = (int) Math.floor((y - originY) / step);
        if (gx < 0 || gy < 0 || gx >= width || gy >= height) return 0f;
        return grid[gy * width + gx];
    }

    /** dB a sound from (sx, sy) loses on its way to (lx, ly): by the wetter of the two ends. */
    public float muffleDb(float sx, float sy, float lx, float ly) {
        return maxMuffleDb * Math.max(precipAt(sx, sy), precipAt(lx, ly));
    }

    public boolean inStorm(float x, float y) { return precipAt(x, y) >= IN_STORM; }

    /** Centre of grid cell {@code i} (tiles). */
    public float cellX(int i) { return originX + (i % width + 0.5f) * step; }

    public float cellY(int i) { return originY + (i / width + 0.5f) * step; }

    public int step() { return step; }

    /** Thundering storms (connected wet cells), worked out once per snapshot. Empty for vanilla weather. */
    public synchronized List<Storm> storms() {
        if (storms != null) return storms;
        List<Storm> out = new ArrayList<>();
        if (grid != null) {
            boolean[] seen = new boolean[grid.length];
            int[] queue = new int[grid.length];
            for (int i = 0; i < grid.length; i++) {
                if (seen[i] || grid[i] < THUNDER_STORM) continue;
                int head = 0, tail = 0;
                queue[tail++] = i;
                seen[i] = true;
                float peak = 0;
                while (head < tail) {
                    int c = queue[head++];
                    peak = Math.max(peak, grid[c]);
                    int cx = c % width, cy = c / width;
                    int[][] n = {{cx + 1, cy}, {cx - 1, cy}, {cx, cy + 1}, {cx, cy - 1}};
                    for (int[] p : n) {
                        if (p[0] < 0 || p[1] < 0 || p[0] >= width || p[1] >= height) continue;
                        int j = p[1] * width + p[0];
                        if (!seen[j] && grid[j] >= THUNDER_STORM) {
                            seen[j] = true;
                            queue[tail++] = j;
                        }
                    }
                }
                int[] cells = new int[tail];
                System.arraycopy(queue, 0, cells, 0, tail);
                out.add(new Storm(cells, peak));
            }
        }
        storms = Collections.unmodifiableList(out);
        return storms;
    }
}
