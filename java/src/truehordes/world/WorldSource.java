package truehordes.world;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Everything the build needs, captured on the main thread at world start. Immutable. */
public final class WorldSource {
    private static final Pattern CHUNKDATA = Pattern.compile("chunkdata_(-?\\d+)_(-?\\d+)\\.bin");

    /** Cell bounds, inclusive. */
    public final int minCellX, minCellY, maxCellX, maxCellY;
    public final Map<Long, Path> mapFiles;
    /** The save's chunkdata folder; null when there is none (tests, no save). */
    public final Path saveChunkDir;
    public final List<ZoneShape> zones;
    /** Map folders in the game's priority order; their streets.xml and worldmap.xml add roads and trails. */
    public final List<Path> mapFolders;

    public WorldSource(int minCellX, int minCellY, int maxCellX, int maxCellY, Map<Long, Path> mapFiles,
                       Path saveChunkDir, List<ZoneShape> zones) {
        this(minCellX, minCellY, maxCellX, maxCellY, mapFiles, saveChunkDir, zones, List.of());
    }

    public WorldSource(int minCellX, int minCellY, int maxCellX, int maxCellY, Map<Long, Path> mapFiles,
                       Path saveChunkDir, List<ZoneShape> zones, List<Path> mapFolders) {
        // The game's meta grid can report bounds far wider than the map (seen in 42.21: ~500x500 cells instead of
        // ~70x70), which made the terrain map ~1.5 GB. Clamp to the cells that have map files.
        if (!mapFiles.isEmpty()) {
            int fx0 = Integer.MAX_VALUE, fy0 = Integer.MAX_VALUE, fx1 = Integer.MIN_VALUE, fy1 = Integer.MIN_VALUE;
            for (long k : mapFiles.keySet()) {
                int x = (int) (k >> 32), y = (int) k;
                fx0 = Math.min(fx0, x); fy0 = Math.min(fy0, y); fx1 = Math.max(fx1, x); fy1 = Math.max(fy1, y);
            }
            if (fx0 > minCellX || fy0 > minCellY || fx1 < maxCellX || fy1 < maxCellY) {
                truehordes.util.Log.info("map grid " + minCellX + "," + minCellY + " to " + maxCellX + "," + maxCellY
                    + " clamped to the map files " + fx0 + "," + fy0 + " to " + fx1 + "," + fy1);
            }
            minCellX = Math.max(minCellX, fx0);
            minCellY = Math.max(minCellY, fy0);
            maxCellX = Math.min(maxCellX, fx1);
            maxCellY = Math.min(maxCellY, fy1);
        }
        this.minCellX = minCellX;
        this.minCellY = minCellY;
        this.maxCellX = maxCellX;
        this.maxCellY = maxCellY;
        this.mapFiles = Map.copyOf(mapFiles);
        this.saveChunkDir = saveChunkDir;
        this.zones = List.copyOf(zones);
        this.mapFolders = List.copyOf(mapFolders);
    }

    public static long key(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    public Path mapFile(int cx, int cy) { return mapFiles.get(key(cx, cy)); }

    public Path saveFile(int cx, int cy) {
        return saveChunkDir == null ? null : saveChunkDir.resolve("chunkdata_" + cx + "_" + cy + ".bin");
    }

    /**
     * Each map folder's file names (name to absolute path), highest priority first (the game's IsoLot.MapFiles order).
     * The first folder that has a cell's collision file wins.
     */
    public static Map<Long, Path> resolveMapFiles(List<Map<String, String>> foldersInPriority) {
        Map<Long, Path> out = new HashMap<>();
        for (Map<String, String> folder : foldersInPriority) {
            for (Map.Entry<String, String> e : folder.entrySet()) {
                Matcher m = CHUNKDATA.matcher(e.getKey());
                if (!m.matches() || e.getValue() == null) continue;
                out.putIfAbsent(key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), Paths.get(e.getValue()));
            }
        }
        return out;
    }
}
