package truehordes.core;

import java.nio.file.Path;

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
}
