package truehordes.world;

import java.util.List;

/**
 * Zones to one Terrain code per chunk. A road or trail marks a chunk if it covers any of its tiles (narrow ones are
 * never lost); every other zone needs the chunk's centre tile (cx * 8 + 4, cy * 8 + 4). Where zones overlap, the higher
 * Terrain.zonePriority wins. Polygons are filled by scanlines, so big forests cost one pass per chunk row.
 */
public final class ZoneRaster {
    private ZoneRaster() {}

    public static byte[] rasterise(List<ZoneShape> zones, int originX, int originY, int width, int height) {
        byte[] codes = new byte[width * height];
        for (ZoneShape z : zones) {
            int cx0 = Math.max(Math.floorDiv(z.x, 8), originX);
            int cx1 = Math.min(Math.floorDiv(z.x + z.w - 1, 8), originX + width - 1);
            int cy0 = Math.max(Math.floorDiv(z.y, 8), originY);
            int cy1 = Math.min(Math.floorDiv(z.y + z.h - 1, 8), originY + height - 1);
            if (cx0 > cx1 || cy0 > cy1) continue;
            boolean road = z.terrain.anyTile;
            switch (z.kind) {
                case RECT:
                    for (int cy = cy0; cy <= cy1; cy++) {
                        for (int cx = cx0; cx <= cx1; cx++) {
                            if (road || z.contains(cx * 8 + 4, cy * 8 + 4)) mark(codes, originX, originY, width, cx, cy, z.terrain);
                        }
                    }
                    break;
                case POLYGON:
                    if (road) polygonAnyTile(z, codes, originX, originY, width, height);
                    else polygonCentres(z, codes, originX, originY, width, cy0, cy1);
                    break;
                default:
                    polyline(z, codes, originX, originY, width, height);
            }
        }
        return codes;
    }

    private static void polygonCentres(ZoneShape z, byte[] codes, int ox, int oy, int width, int cy0, int cy1) {
        for (int cy = cy0; cy <= cy1; cy++) {
            double[] xs = z.crossings(cy * 8 + 4.5);
            for (int i = 0; i + 1 < xs.length; i += 2) {
                // chunk centres cx * 8 + 4.5 inside [xs[i], xs[i + 1])
                int from = Math.max((int) Math.ceil((xs[i] - 4.5) / 8), ox);
                int to = Math.min((int) Math.ceil((xs[i + 1] - 4.5) / 8) - 1, ox + width - 1);
                for (int cx = from; cx <= to; cx++) mark(codes, ox, oy, width, cx, cy, z.terrain);
            }
        }
    }

    private static void polygonAnyTile(ZoneShape z, byte[] codes, int ox, int oy, int width, int height) {
        int ty0 = Math.max(z.y, oy * 8), ty1 = Math.min(z.y + z.h - 1, (oy + height) * 8 - 1);
        for (int ty = ty0; ty <= ty1; ty++) {
            double[] xs = z.crossings(ty + 0.5);
            for (int i = 0; i + 1 < xs.length; i += 2) {
                // tile centres tx + 0.5 inside [xs[i], xs[i + 1])
                int from = (int) Math.ceil(xs[i] - 0.5);
                int to = (int) Math.ceil(xs[i + 1] - 0.5) - 1;
                if (from > to) continue;
                int cxFrom = Math.max(Math.floorDiv(from, 8), ox);
                int cxTo = Math.min(Math.floorDiv(to, 8), ox + width - 1);
                for (int cx = cxFrom; cx <= cxTo; cx++) mark(codes, ox, oy, width, cx, Math.floorDiv(ty, 8), z.terrain);
            }
        }
    }

    /** Chunk centre to its farthest tile centre: 3.5 * sqrt(2). */
    private static final double CHUNK_REACH = 3.5 * Math.sqrt(2);

    /**
     * Segment by segment, only over the chunks around each segment (a long street's bounding box can span the whole
     * map). Same result as testing every tile with ZoneShape.contains.
     */
    private static void polyline(ZoneShape z, byte[] codes, int ox, int oy, int width, int height) {
        int[] p = z.points();
        double half = z.halfWidth(), limit = half * half;
        int last = p.length >= 4 ? p.length - 4 : 0;
        for (int i = 0; i <= last; i += 2) {
            double ax = p[i], ay = p[i + 1];
            double bx = p.length >= 4 ? p[i + 2] : ax, by = p.length >= 4 ? p[i + 3] : ay;
            int cx0 = Math.max(Math.floorDiv((int) Math.floor(Math.min(ax, bx) - half - 1), 8), ox);
            int cx1 = Math.min(Math.floorDiv((int) Math.ceil(Math.max(ax, bx) + half), 8), ox + width - 1);
            int cy0 = Math.max(Math.floorDiv((int) Math.floor(Math.min(ay, by) - half - 1), 8), oy);
            int cy1 = Math.min(Math.floorDiv((int) Math.ceil(Math.max(ay, by) + half), 8), oy + height - 1);
            double reach = (half + CHUNK_REACH) * (half + CHUNK_REACH);
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    boolean hit;
                    if (z.terrain.anyTile) {
                        if (ZoneShape.distSq(cx * 8 + 4, cy * 8 + 4, ax, ay, bx, by) > reach) continue;
                        hit = false;
                        for (int t = 0; t < 64 && !hit; t++) {
                            hit = ZoneShape.distSq(cx * 8 + t % 8 + 0.5, cy * 8 + t / 8 + 0.5, ax, ay, bx, by) <= limit;
                        }
                    } else {
                        hit = ZoneShape.distSq(cx * 8 + 4.5, cy * 8 + 4.5, ax, ay, bx, by) <= limit;
                    }
                    if (hit) mark(codes, ox, oy, width, cx, cy, z.terrain);
                }
            }
        }
    }

    private static void mark(byte[] codes, int ox, int oy, int width, int cx, int cy, Terrain t) {
        int i = (cy - oy) * width + (cx - ox);
        if (t.zonePriority > Terrain.ofCode(codes[i]).zonePriority) codes[i] = (byte) t.code;
    }
}
