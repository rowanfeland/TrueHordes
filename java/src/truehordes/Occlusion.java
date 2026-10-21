package truehordes;

import truehordes.sim.MapView;
import truehordes.sim.Sightline;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;

/**
 * Far figures hidden by what stands between them and the player (user, 2026-10-21): walls, closed doors, cars and woods in
 * the loaded area (the game's squares, as its own sight tests them), building chunks beyond it (the simulation's map).
 * Main thread.
 */
final class Occlusion {
    /** This many tree squares along the loaded part of the line make it woods: nothing seen through. */
    static final int TREES = 3;

    private Occlusion() {}

    /** Drops the marks of {@code stride} floats (x, y first) hidden from (px, py, pz); the rest in order. */
    static float[] visible(float[] f, int stride, MapView map, float px, float py, int pz, float edge) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return f;
        IsoGridSquare from = cell.getGridSquare((int) Math.floor(px), (int) Math.floor(py), pz);
        float[] out = new float[f.length];
        int n = 0;
        for (int i = 0; i + stride <= f.length; i += stride) {
            float x = f[i], y = f[i + 1];
            if (from != null && blockedNear(cell, from, px, py, pz, x, y, edge)) continue;
            if (map != null && Sightline.buildingsBlock(map, px, py, x, y, edge)) continue;
            System.arraycopy(f, i, out, n, stride);
            n += stride;
        }
        return n == f.length ? f : java.util.Arrays.copyOf(out, n);
    }

    /** The loaded part of the line, square by square, out to {@code edge} tiles. */
    private static boolean blockedNear(IsoCell cell, IsoGridSquare from, float px, float py, int pz, float tx, float ty,
                                       float edge) {
        float dx = tx - px, dy = ty - py;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return false;
        float reach = Math.min(len, edge);
        IsoGridSquare at = from;
        int trees = 0;
        zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
        zombie.vehicles.BaseVehicle own = p == null ? null : p.getVehicle();
        for (float d = 0.5f; d <= reach; d += 0.5f) {
            int sx = (int) Math.floor(px + dx * d / len), sy = (int) Math.floor(py + dy * d / len);
            if (sx == at.getX() && sy == at.getY()) continue;
            int ox = Integer.signum(sx - at.getX()), oy = Integer.signum(sy - at.getY());
            LosUtil.TestResults r = at.testVisionAdjacent(ox, oy, 0, false, false);
            if (r == LosUtil.TestResults.Blocked || r == LosUtil.TestResults.ClearThroughClosedDoor) return true;
            IsoGridSquare next = cell.getGridSquare(at.getX() + ox, at.getY() + oy, pz);
            if (next == null) return false; // past the loaded squares (or open air upstairs)
            if (d > 3 && next.HasTree() && ++trees >= TREES) return true;
            // A car in the way (not the one the player sits in).
            zombie.vehicles.BaseVehicle car = next.getVehicleContainer();
            if (car != null && car != own) return true;
            at = next;
        }
        return false;
    }
}
