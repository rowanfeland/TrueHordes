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
