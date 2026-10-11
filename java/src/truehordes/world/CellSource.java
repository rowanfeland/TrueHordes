package truehordes.world;

import java.nio.file.Path;

/** Loads one cell's tiles: the save's copy when it reads cleanly, otherwise the map's copy. */
public final class CellSource {
    private CellSource() {}

    /** Null when neither file exists. */
    public static byte[] load(WorldSource src, int cx, int cy, BuildReport report) {
        Path save = src.saveFile(cx, cy);
        CellReader.Result fromSave = CellReader.read(save);
        if (fromSave.clean()) {
            report.cellsFromSave++;
            return fromSave.tiles;
        }
        if (!fromSave.missing()) report.damaged(save, fromSave.error + " (using the map's copy)");
        Path map = src.mapFile(cx, cy);
        CellReader.Result fromMap = CellReader.read(map);
        if (!fromMap.missing()) {
            if (!fromMap.clean()) report.damaged(map, fromMap.error);
            report.cellsFromMap++;
            return fromMap.tiles;
        }
        report.cellsMissing++;
        return null;
    }
}
