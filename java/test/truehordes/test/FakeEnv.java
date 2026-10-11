package truehordes.test;

import java.nio.file.Path;
import truehordes.core.GameEnv;
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

    public FakeEnv(Path stateFile) { this.stateFile = stateFile; }

    @Override public boolean isClient() { return client; }
    @Override public boolean isServer() { return server; }
    @Override public boolean isWorldReady() { return worldReady; }
    @Override public double worldAgeHours() { return worldAgeHours; }
    @Override public Path stateFile() { return stateFile; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean debugLogging() { return debugLogging; }
    @Override public WorldSource worldSource() { return worldSource; }
}
