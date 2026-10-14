package truehordes.test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import truehordes.core.GameEnv;
import truehordes.population.MapSource;
import truehordes.population.PlayerNearby;
import truehordes.population.PopulationSettings;
import truehordes.world.WorldSource;

/** Test stand-in for the game. */
public final class FakeEnv implements GameEnv {
    public boolean client;
    public boolean server;
    public boolean worldReady = true;
    public double worldAgeHours = 100.0;
    public Path stateFile;
    public boolean enabled = true;
    public boolean debugLogging;
    public WorldSource worldSource;
    public PopulationSettings settings = PopulationSettings.vanillaDefaults();
    public boolean newWorld;
    public MapSource map;
    public int spawnLoadedCalls;
    public long[] loaded = new long[0];
    public Map<Long, Integer> realPerCell = new HashMap<>();
    public PlayerNearby nearby = new PlayerNearby() {
        @Override public boolean nearCell(int cellX, int cellY) { return false; }
        @Override public boolean nearChunk(int chunkX, int chunkY) { return false; }
    };

    public FakeEnv(Path stateFile) { this.stateFile = stateFile; }

    @Override public boolean isClient() { return client; }
    @Override public boolean isServer() { return server; }
    @Override public boolean isWorldReady() { return worldReady; }
    @Override public double worldAgeHours() { return worldAgeHours; }
    @Override public Path stateFile() { return stateFile; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean debugLogging() { return debugLogging; }
    @Override public WorldSource worldSource() { return worldSource; }
    @Override public PopulationSettings populationSettings() { return settings; }
    public truehordes.sim.SimSettings sim = truehordes.sim.SimSettings.defaults();
    @Override public truehordes.sim.SimSettings simSettings() { return sim; }
    @Override public boolean newWorld() { return newWorld; }
    @Override public MapSource mapSource() { return map; }
    @Override public void spawnLoadedChunks() { spawnLoadedCalls++; }
    @Override public long[] loadedChunks() { return loaded; }
    @Override public PlayerNearby playerNearby() { return nearby; }
    @Override public Map<Long, Integer> realZombiesPerCell() { return realPerCell; }
}
