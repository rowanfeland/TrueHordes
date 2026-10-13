package truehordes.core;

import java.nio.file.Path;
import java.util.Map;
import truehordes.population.MapSource;
import truehordes.population.PlayerNearby;
import truehordes.population.PopulationSettings;
import truehordes.world.WorldSource;

/** What the core needs from the game. The game-backed implementation is truehordes.GameEnvImpl. */
public interface GameEnv {
    boolean isClient();

    boolean isServer();

    /** True once a world is loaded and has a current cell. */
    boolean isWorldReady();

    double worldAgeHours();

    /** The mod's save file inside the current save folder. */
    Path stateFile();

    /** Sandbox TrueHordes.Enabled. */
    boolean enabled();

    /** Sandbox TrueHordes.DebugLogging. */
    boolean debugLogging();

    /** Snapshot of the loaded world's map files, save folder and zones (main thread); null if unavailable. */
    WorldSource worldSource();

    /** The vanilla sandbox zombie settings and True Hordes' population options, read now. */
    PopulationSettings populationSettings();

    /** No True Hordes save and the game never saved a native population for this world: a new game. */
    boolean newWorld();

    /** A copy of the map data seeding needs (main thread). */
    MapSource mapSource();

    /** Bring held zombies into every chunk loaded right now. */
    void spawnLoadedChunks();

    /** Chunks loaded right now (chunkX << 32 | chunkY). */
    long[] loadedChunks();

    PlayerNearby playerNearby();

    /** Real (live) zombies per 256x256 cell (cellX << 32 | cellY). */
    Map<Long, Integer> realZombiesPerCell();
}
