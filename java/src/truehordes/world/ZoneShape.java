package truehordes.world;

import java.util.Arrays;

/**
 * An immutable copy of one map zone, with the mod's own geometry (the game's Zone shares a static helper that is not
 * thread-safe, so the build thread never touches game zones). Tile (tx, ty) is inside when its centre is.
 */
public final class ZoneShape {
    public enum Kind { RECT, POLYGON, POLYLINE }

    public final Terrain terrain;
    public final Kind kind;
    /** Bounds in tiles: [x, x + w) by [y, y + h). */
    public final int x, y, w, h;
    /** Polygon corners or polyline points (x0, y0, x1, y1, ...) in tiles; decimals kept (streets use half tiles). */
    private final double[] points;
    private final double halfWidth;

    private ZoneShape(Terrain terrain, Kind kind, int x, int y, int w, int h, double[] points, double halfWidth) {
        this.terrain = terrain;
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.points = points;
        this.halfWidth = halfWidth;
    }

    public static ZoneShape rect(Terrain terrain, int x, int y, int w, int h) {
        return new ZoneShape(terrain, Kind.RECT, x, y, w, h, null, 0);
    }

    /** Corners as x0, y0, x1, y1, ... (closed implicitly). */
    public static ZoneShape polygon(Terrain terrain, int[] points) { return polygon(terrain, toDoubles(points)); }

    public static ZoneShape polygon(Terrain terrain, double[] points) {
        int[] b = bounds(points, 0);
        return new ZoneShape(terrain, Kind.POLYGON, b[0], b[1], b[2], b[3], points.clone(), 0);
    }

    /** A line through x0, y0, x1, y1, ... covering everything within width / 2 of it. */
    public static ZoneShape polyline(Terrain terrain, int[] points, int width) {
        return polyline(terrain, toDoubles(points), width);
    }

    public static ZoneShape polyline(Terrain terrain, double[] points, int width) {
        double half = width / 2.0;
        int[] b = bounds(points, half);
        return new ZoneShape(terrain, Kind.POLYLINE, b[0], b[1], b[2], b[3], points.clone(), half);
    }

    private static double[] toDoubles(int[] pts) {
        double[] d = new double[pts.length];
        for (int i = 0; i < pts.length; i++) d[i] = pts[i];
        return d;
    }

    /** Every tile whose centre can be inside: floor(min - pad) .. ceil(max + pad), inclusive. */
    private static int[] bounds(double[] pts, double pad) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i + 1 < pts.length; i += 2) {
            minX = Math.min(minX, pts[i]);
            maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        if (minX > maxX) return new int[] {0, 0, 0, 0};
        int x0 = (int) Math.floor(minX - pad), y0 = (int) Math.floor(minY - pad);
        return new int[] {x0, y0, (int) Math.ceil(maxX + pad) - x0 + 1, (int) Math.ceil(maxY + pad) - y0 + 1};
    }

    public boolean contains(int tx, int ty) {
        if (tx < x || tx >= x + w || ty < y || ty >= y + h) return false;
        double px = tx + 0.5, py = ty + 0.5;
        switch (kind) {
            case RECT: return true;
            case POLYGON: return insidePolygon(px, py);
            default: return nearPolyline(px, py);
        }
    }

    /** Polygon only: sorted x positions where the horizontal line at py crosses the outline. */
    double[] crossings(double py) {
        int n = points.length / 2;
        double[] xs = new double[n];
        int count = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = points[2 * i], yi = points[2 * i + 1], xj = points[2 * j], yj = points[2 * j + 1];
            if ((yi > py) != (yj > py)) xs[count++] = xi + (py - yi) * (xj - xi) / (yj - yi);
        }
        double[] out = Arrays.copyOf(xs, count);
        Arrays.sort(out);
        return out;
    }

    private boolean insidePolygon(double px, double py) {
        int right = 0;
        for (double cx : crossings(py)) if (cx > px) right++;
        return (right & 1) == 1;
    }

    private boolean nearPolyline(double px, double py) {
        double limit = halfWidth * halfWidth;
        for (int i = 0; i + 3 < points.length; i += 2) {
            if (distSq(px, py, points[i], points[i + 1], points[i + 2], points[i + 3]) <= limit) return true;
        }
        return points.length == 2 && distSq(px, py, points[0], points[1], points[0], points[1]) <= limit;
    }

    /** Polyline only: the points (x0, y0, x1, y1, ...); shared, never modified. */
    double[] points() { return points; }

    /** Polyline only: half the line's width. */
    double halfWidth() { return halfWidth; }

    static double distSq(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
        double ex = ax + t * dx - px, ey = ay + t * dy - py;
        return ex * ex + ey * ey;
    }
}
