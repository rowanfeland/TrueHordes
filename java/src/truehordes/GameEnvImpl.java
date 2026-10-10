package truehordes;

import java.nio.file.Path;
import java.nio.file.Paths;
import truehordes.core.GameEnv;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.ZomboidFileSystem;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;

/** GameEnv backed by the running game. */
final class GameEnvImpl implements GameEnv {
    @Override
    public boolean isClient() { return GameClient.client; }

    @Override
    public boolean isServer() { return GameServer.server; }

    @Override
    public boolean isWorldReady() { return IsoWorld.instance != null && IsoWorld.instance.currentCell != null; }

    @Override
    public double worldAgeHours() { return GameTime.getInstance().getWorldAgeHours(); }

    @Override
    public Path stateFile() {
        return Paths.get(ZomboidFileSystem.instance.getFileNameInCurrentSave("truehordes", "state.bin"));
    }

    @Override
    public boolean enabled() { return sandboxBool("TrueHordes.Enabled", true); }

    @Override
    public boolean debugLogging() { return sandboxBool("TrueHordes.DebugLogging", false); }

    private static boolean sandboxBool(String name, boolean fallback) {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(name);
            return option == null ? fallback : Boolean.parseBoolean(option.asConfigOption().getValueAsString());
        } catch (Throwable t) {
            return fallback;
        }
    }
}
