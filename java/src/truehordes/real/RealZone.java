package truehordes.real;

/**
 * Where zombies are real game objects (sub-project 6 spec goal 6): within {@code radius} tiles of a player. Held
 * zombies inside the loaded area but further out stay in the simulation (still walking) unless a player can see their
 * spot; real zombies go back to the simulation only past {@code radius + DEMAT_MARGIN}, unseen and left alone by
 * vanilla. Radius 0 switches the trick off (the whole loaded area is real). Player positions are published by the main
 * thread and read by the simulation thread.
 */
public final class RealZone {
    /** A real zombie goes back to the simulation only this much further out than it became real (tiles). */
    public static final float DEMAT_MARGIN = 15f;

    private volatile float[] players = new float[0];
    private volatile float radius;

    /** Player positions {x0, y0, x1, y1, ...} and the radius (0 = off). */
    public void set(float[] xy, float r) {
        players = xy == null ? new float[0] : xy;
        radius = Math.max(0f, r);
    }

    public boolean on() { return radius > 0; }

    public float radius() { return radius; }

    /** Distance from (x, y) to the nearest player (infinite with none). */
    public float nearest(float x, float y) {
        float[] p = players;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < p.length; i += 2) best = Math.min(best, Math.hypot(p[i] - x, p[i + 1] - y));
        return (float) best;
    }

    /** (x, y) is within the radius plus {@code extra} of a player. */
    public boolean near(float x, float y, float extra) { return nearest(x, y) <= radius + extra; }

    /**
     * Every tile of chunk (cx, cy) lies within the radius of one player, so a held zombie anywhere in it becomes real
     * (the simulation stops walking it there and asks the main thread to spawn it).
     */
    public boolean chunkInside(int cx, int cy) {
        float[] p = players;
        float r = radius, x0 = cx * 8f, y0 = cy * 8f, x1 = x0 + 8, y1 = y0 + 8;
        for (int i = 0; i + 1 < p.length; i += 2) {
            float dx = Math.max(Math.abs(p[i] - x0), Math.abs(p[i] - x1));
            float dy = Math.max(Math.abs(p[i + 1] - y0), Math.abs(p[i + 1] - y1));
            if (dx * dx + dy * dy <= r * r) return true;
        }
        return false;
    }
}
