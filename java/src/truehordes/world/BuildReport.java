package truehordes.world;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import truehordes.util.Log;

/** What a build read and how long it took. Filled by one thread, read after publishing. */
public final class BuildReport {
    public int cellsFromSave;
    public int cellsFromMap;
    public int cellsMissing;
    public int filesDamaged;
    /** The first few damaged files with their errors. */
    public final List<String> damaged = new ArrayList<>();
    public long millis;
    public long memoryBytes;
    public int[] terrainCounts = new int[16];
    /** Roads and trails read from the map folders' streets.xml and worldmap.xml. */
    public int streets;
    public int worldRoads;
    public int trails;
    /** Road files skipped or only partly read. */
    public final List<String> roadProblems = new ArrayList<>();

    public void damaged(Path file, String error) {
        filesDamaged++;
        if (damaged.size() < 5) damaged.add(file + ": " + error);
        Log.warn("collision file " + file + ": " + error);
    }
}
