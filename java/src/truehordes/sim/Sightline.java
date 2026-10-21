package truehordes.sim;

/**
 * Seeing a far figure past the loaded area (user, 2026-10-21: figures were seen through walls and buildings): the
 * simulation's map knows building chunks, so a sightline crossing one is blocked. The loaded part is the game's own
 * squares (truehordes.Occlusion).
 */
public final class Sightline {
    private Sightline() {}

    /**
     * True when the line from (x0, y0) to (x1, y1), from {@code from} tiles along it, crosses a building chunk before
     * the target's own chunk (a zombie in a town is still seen from the town's edge).
     */
    public static boolean buildingsBlock(MapView map, float x0, float y0, float x1, float y1, float from) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= from) return false;
        int tcx = Math.floorDiv((int) Math.floor(x1), 8), tcy = Math.floorDiv((int) Math.floor(y1), 8);
        float step = 4f;
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
        for (float d = Math.max(0, from); d < len; d += step) {
            float x = x0 + dx * d / len, y = y0 + dy * d / len;
            int cx = Math.floorDiv((int) Math.floor(x), 8), cy = Math.floorDiv((int) Math.floor(y), 8);
            if (cx == lastX && cy == lastY) continue;
            lastX = cx;
            lastY = cy;
            if (Math.abs(cx - tcx) <= 1 && Math.abs(cy - tcy) <= 1) return false;
            if (map.building(cx, cy)) return true;
        }
        return false;
    }
}
