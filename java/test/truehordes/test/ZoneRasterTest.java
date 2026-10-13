package truehordes.test;

import java.util.List;
import truehordes.world.Terrain;
import truehordes.world.ZoneRaster;
import truehordes.world.ZoneShape;

public final class ZoneRasterTest {
    /** A 4x4-chunk area at the origin. */
    private static int code(byte[] codes, int cx, int cy) { return codes[cy * 4 + cx]; }

    public static void testRectNeedsChunkCentre() {
        // Covers tiles 0..11 in x: chunk 0 fully, chunk 1 up to tile 11 (its centre tile 12 is outside).
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.rect(Terrain.FOREST, 0, 0, 12, 8)), 0, 0, 4, 4);
        Check.equal(Terrain.FOREST.code, code(c, 0, 0), "chunk 0");
        Check.equal(0, code(c, 1, 0), "chunk 1 centre not covered");
    }

    public static void testThinRoadMarksChunk() {
        // A 4-tile-wide road along the right edge of chunk column 1 (tiles 12..15), all rows.
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.rect(Terrain.ROAD, 12, 0, 4, 32)), 0, 0, 4, 4);
        for (int cy = 0; cy < 4; cy++) {
            Check.equal(Terrain.ROAD.code, code(c, 1, cy), "road chunk row " + cy);
            Check.equal(0, code(c, 0, cy), "left of road row " + cy);
            Check.equal(0, code(c, 2, cy), "right of road row " + cy);
        }
    }

    public static void testPriorityWhereZonesOverlap() {
        List<ZoneShape> zones = List.of(
            ZoneShape.rect(Terrain.FOREST, 0, 0, 32, 32),
            ZoneShape.rect(Terrain.TOWN, 0, 0, 16, 16),
            ZoneShape.rect(Terrain.OPEN, 0, 0, 32, 32),
            ZoneShape.rect(Terrain.ROAD, 0, 0, 1, 1));
        byte[] c = ZoneRaster.rasterise(zones, 0, 0, 4, 4);
        Check.equal(Terrain.ROAD.code, code(c, 0, 0), "road beats town");
        Check.equal(Terrain.TOWN.code, code(c, 1, 1), "town beats forest");
        Check.equal(Terrain.FOREST.code, code(c, 3, 3), "forest beats open");
    }

    public static void testPolygonByCentre() {
        // Right triangle with corners (0,0), (32,0), (0,32): chunk (0,0) and (2,0) centres are inside, (3,3) is not.
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.polygon(Terrain.DEEP_FOREST, new int[] {0, 0, 32, 0, 0, 32})), 0, 0, 4, 4);
        Check.equal(Terrain.DEEP_FOREST.code, code(c, 0, 0), "corner chunk");
        Check.equal(Terrain.DEEP_FOREST.code, code(c, 2, 0), "along the top");
        Check.equal(0, code(c, 3, 3), "outside the hypotenuse");
        Check.equal(0, code(c, 2, 2), "centre (20.5,20.5) is outside");
    }

    public static void testRoadPolygonAnyTile() {
        // A thin road polygon covering only tiles x 14..15 in rows 0..7 (inside chunk (1,0), not its centre).
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.polygon(Terrain.ROAD, new int[] {14, 0, 16, 0, 16, 8, 14, 8})), 0, 0, 4, 4);
        Check.equal(Terrain.ROAD.code, code(c, 1, 0), "any tile counts for roads");
        Check.equal(0, code(c, 2, 0), "neighbour untouched");
    }

    public static void testPolylineByDistance() {
        ZoneShape line = ZoneShape.polyline(Terrain.ROAD, new int[] {0, 4, 32, 4}, 2);
        Check.isTrue(line.contains(10, 4), "on the line");
        Check.isTrue(!line.contains(10, 7), "3.5 tiles off");
        byte[] c = ZoneRaster.rasterise(List.of(line), 0, 0, 4, 4);
        for (int cx = 0; cx < 4; cx++) Check.equal(Terrain.ROAD.code, code(c, cx, 0), "row 0 chunk " + cx);
        Check.equal(0, code(c, 0, 1), "row 1 untouched");
    }

    public static void testOffsetOriginAndClipping() {
        // Origin at chunk (10,20); a zone partly outside the area must not throw and must land in place.
        byte[] c = ZoneRaster.rasterise(List.of(ZoneShape.rect(Terrain.FARM, 72, 152, 64, 64)), 10, 20, 4, 4);
        Check.equal(Terrain.FARM.code, (int) c[(20 - 20) * 4 + (10 - 10)], "first chunk (80..87, 160..167)");
        Check.equal(Terrain.FARM.code, (int) c[3 * 4 + 3], "last chunk");
    }

    public static void testContainsRect() {
        ZoneShape r = ZoneShape.rect(Terrain.TOWN, 5, 5, 2, 2);
        Check.isTrue(r.contains(5, 5) && r.contains(6, 6), "inside");
        Check.isTrue(!r.contains(7, 5) && !r.contains(4, 5), "outside");
    }

    public static void testTrailPriorityAndAnyTile() {
        ZoneShape trail = ZoneShape.rect(Terrain.TRAIL, 14, 0, 2, 32);
        byte[] alone = ZoneRaster.rasterise(List.of(trail), 0, 0, 4, 4);
        Check.equal(Terrain.TRAIL.code, code(alone, 1, 0), "any tile counts for trails");
        byte[] inTown = ZoneRaster.rasterise(List.of(ZoneShape.rect(Terrain.TOWN, 0, 0, 32, 32), trail), 0, 0, 4, 4);
        Check.equal(Terrain.TOWN.code, code(inTown, 1, 0), "a trail in town stays town");
        byte[] inForest = ZoneRaster.rasterise(List.of(ZoneShape.rect(Terrain.FOREST, 0, 0, 32, 32), trail), 0, 0, 4, 4);
        Check.equal(Terrain.TRAIL.code, code(inForest, 1, 0), "a trail through forest is trail");
    }

    /** Brute force: a chunk is marked when any tile centre (any-tile kinds) or the centre tile (others) is inside. */
    private static byte[] bruteForce(ZoneShape z, int w, int h) {
        byte[] out = new byte[w * h];
        for (int cy = 0; cy < h; cy++) {
            for (int cx = 0; cx < w; cx++) {
                boolean hit = false;
                if (z.terrain.anyTile) {
                    for (int t = 0; t < 64 && !hit; t++) hit = z.contains(cx * 8 + t % 8, cy * 8 + t / 8);
                } else {
                    hit = z.contains(cx * 8 + 4, cy * 8 + 4);
                }
                if (hit) out[cy * w + cx] = (byte) z.terrain.code;
            }
        }
        return out;
    }

    public static void testPolylinesMatchBruteForce() {
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 60; i++) {
            int n = 2 + rnd.nextInt(5);
            int[] pts = new int[n * 2];
            for (int k = 0; k < pts.length; k++) pts[k] = rnd.nextInt(160);
            Terrain t = i % 3 == 0 ? Terrain.FOREST : (i % 3 == 1 ? Terrain.ROAD : Terrain.TRAIL);
            ZoneShape z = ZoneShape.polyline(t, pts, 1 + rnd.nextInt(9));
            byte[] fast = ZoneRaster.rasterise(List.of(z), 0, 0, 20, 20);
            Check.isTrue(java.util.Arrays.equals(bruteForce(z, 20, 20), fast), "polyline " + i + " " + java.util.Arrays.toString(pts));
        }
    }

    public static void testLongPolylineIsFast() {
        // One long diagonal street with many segments across a 1000x1000-chunk area (the vanilla map is about 2464x1984).
        int[] pts = new int[202];
        for (int k = 0; k <= 100; k++) {
            pts[2 * k] = k * 79;
            pts[2 * k + 1] = k * 79 + (k % 2) * 5;
        }
        ZoneShape street = ZoneShape.polyline(Terrain.ROAD, pts, 6);
        long t0 = System.nanoTime();
        byte[] c = ZoneRaster.rasterise(List.of(street), 0, 0, 1000, 1000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Check.equal(Terrain.ROAD.code, (int) c[500 * 1000 + 500], "on the line");
        Check.isTrue(ms < 200, "rasterised in " + ms + " ms");
    }
}
