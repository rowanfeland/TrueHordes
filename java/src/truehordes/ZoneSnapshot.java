package truehordes;

import java.util.ArrayList;
import java.util.List;
import truehordes.world.Terrain;
import truehordes.world.ZoneShape;
import zombie.iso.zones.Zone;

/** Copies the game's ground-level zones that matter for walking into plain ZoneShapes. Main thread only. */
final class ZoneSnapshot {
    private ZoneSnapshot() {}

    static List<ZoneShape> copy(List<Zone> zones) {
        List<ZoneShape> out = new ArrayList<>();
        for (Zone z : zones) {
            if (z == null || z.z != 0) continue;
            Terrain t = Terrain.fromZoneType(z.type);
            if (t == null) continue;
            if (z.isRectangle()) out.add(ZoneShape.rect(t, z.x, z.y, z.w, z.h));
            else if (z.isPolygon() && z.points.size() >= 6) out.add(ZoneShape.polygon(t, z.points.toArray()));
            else if (z.isPolyline() && z.points.size() >= 2 && z.polylineWidth > 0) {
                out.add(ZoneShape.polyline(t, z.points.toArray(), z.polylineWidth));
            }
        }
        return out;
    }
}
