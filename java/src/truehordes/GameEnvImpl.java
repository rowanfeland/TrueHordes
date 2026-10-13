package truehordes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import truehordes.core.GameEnv;
import truehordes.world.WorldSource;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.ZomboidFileSystem;
import zombie.iso.IsoLot;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.iso.MapFiles;
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

    @Override
    public WorldSource worldSource() {
        IsoMetaGrid grid = IsoWorld.instance == null ? null : IsoWorld.instance.getMetaGrid();
        if (grid == null || grid.maxX < grid.minX) return null;
        List<Map<String, String>> folders = new ArrayList<>();
        List<Path> mapFolders = new ArrayList<>();
        for (MapFiles mf : IsoLot.MapFiles) {
            folders.add(new HashMap<>(mf.infoFileNames));
            mapFolders.add(Paths.get(mf.mapDirectoryAbsolutePath));
        }
        Path saveDir = Paths.get(ZomboidFileSystem.instance.getFileNameInCurrentSave("chunkdata"));
        return new WorldSource(grid.minX, grid.minY, grid.maxX, grid.maxY, WorldSource.resolveMapFiles(folders),
            saveDir, ZoneSnapshot.copy(grid.zones), mapFolders);
    }

    private static boolean sandboxBool(String name, boolean fallback) {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(name);
            return option == null ? fallback : Boolean.parseBoolean(option.asConfigOption().getValueAsString());
        } catch (Throwable t) {
            return fallback;
        }
    }
}
