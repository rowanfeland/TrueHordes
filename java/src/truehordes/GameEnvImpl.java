package truehordes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import truehordes.core.GameEnv;
import truehordes.population.MapSource;
import truehordes.population.PlayerNearby;
import truehordes.population.PopulationSettings;
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

    @Override
    public PopulationSettings populationSettings() {
        SandboxOptions.ZombieConfig z = SandboxOptions.instance.zombieConfig;
        PopulationSettings.Builder b = PopulationSettings.vanillaDefaults().toBuilder()
            .multiplier((float) z.populationMultiplier.getValue())
            .startMultiplier((float) z.populationStartMultiplier.getValue())
            .peakMultiplier((float) z.populationPeakMultiplier.getValue())
            .peakDay(z.populationPeakDay.getValue())
            .respawnHours((float) z.respawnHours.getValue())
            .respawnUnseenHours((float) z.respawnUnseenHours.getValue())
            .respawnMultiplier((float) z.respawnMultiplier.getValue())
            .zombiesSetting(SandboxOptions.instance.zombies.getValue())
            .uniformDistribution(SandboxOptions.instance.distribution.getValue() == 2)
            .buildingZombiesSeeded(sandboxBool("TrueHordes.BuildingZombiesSeeded", true))
            .edgeArrivalMonths((float) sandboxDouble("TrueHordes.EdgeArrivalMonths", 12.0));
        try {
            b.minPerChunk(Spawner.minPerChunk()).maxPerChunk(Spawner.maxPerChunk());
        } catch (Throwable t) {
            // keep the game's defaults (0 and 255)
        }
        return b.build();
    }

    @Override
    public truehordes.sim.SimSettings simSettings() {
        truehordes.sim.SimSettings d = truehordes.sim.SimSettings.defaults();
        int form = (int) sandboxDouble("TrueHordes.HordeFormSize", d.hordeFormSize());
        int min = Math.min(form, (int) sandboxDouble("TrueHordes.HordeMinSize", d.hordeMinSize()));
        return d.toBuilder()
            .stragglerOneIn(Math.max(1, (int) sandboxDouble("TrueHordes.StragglerOneIn", d.stragglerOneIn())))
            .hordeFormSize(form)
            .hordeMinSize(min)
            .followChancePerHour(sandboxDouble("TrueHordes.FollowChance", d.followChancePerHour()))
            .offRoadChance(sandboxDouble("TrueHordes.OffRoadChance", d.offRoadChance()))
            .trailChance(sandboxDouble("TrueHordes.TrailChance", d.trailChance()))
            .moanResponsePerHour(sandboxDouble("TrueHordes.MoanResponseChance", d.moanResponsePerHour()))
            .joinChancePerHour(sandboxDouble("TrueHordes.JoinChance", d.joinChancePerHour()))
            .buildingStragglerOneIn(Math.max(1, (int) sandboxDouble("TrueHordes.BuildingStragglerOneIn",
                d.buildingStragglerOneIn())))
            .breakAwayChance(sandboxDouble("TrueHordes.BreakAwayChance", d.breakAwayChance()))
            .sightSetting(SandboxOptions.instance.lore.sight.getValue())
            .speedSetting(SandboxOptions.instance.lore.speed.getValue())
            // GameTime.minutesPerDay is real minutes per in-game day (1440 in-game minutes).
            .realSecondsPerGameMinute(GameTime.getInstance().getMinutesPerDay() * 60.0 / 1440.0)
            .build();
    }

    private static String newWorldDir;
    private static boolean newWorldFlag;

    /**
     * Called while the game initialises the population (before it first saves a new world): remembers whether this
     * world has never been saved. The game saves a new world (map_t.bin, zpop) before the core's first tick, so the
     * check cannot wait until then.
     */
    static synchronized void noteWorldLoading() {
        String dir = ZomboidFileSystem.instance.getFileNameInCurrentSave("");
        if (dir.equals(newWorldDir)) return;
        newWorldDir = dir;
        newWorldFlag = neverSaved();
    }

    private static boolean neverSaved() {
        return !java.nio.file.Files.exists(Paths.get(ZomboidFileSystem.instance.getFileNameInCurrentSave("truehordes", "state.bin")))
            && !java.nio.file.Files.exists(Paths.get(ZomboidFileSystem.instance.getFileNameInCurrentSave("map_t.bin")));
    }

    @Override
    public boolean newWorld() {
        synchronized (GameEnvImpl.class) {
            if (ZomboidFileSystem.instance.getFileNameInCurrentSave("").equals(newWorldDir)) return newWorldFlag;
        }
        return neverSaved();
    }

    @Override
    public MapSource mapSource() { return new GameMapSource(IsoWorld.instance.getMetaGrid()); }

    @Override
    public void spawnLoadedChunks() { PopulationBridge.spawnLoadedChunks(Hooks.core()); }

    @Override
    public long[] loadedChunks() { return PopulationBridge.loadedChunks(); }

    @Override
    public PlayerNearby playerNearby() { return PopulationBridge.playerNearby(); }

    @Override
    public Map<Long, Integer> realZombiesPerCell() { return PopulationBridge.realZombiesPerCell(); }

    private static double sandboxDouble(String name, double fallback) {
        try {
            SandboxOptions.SandboxOption option = SandboxOptions.instance.getOptionByName(name);
            return option == null ? fallback : Double.parseDouble(option.asConfigOption().getValueAsString());
        } catch (Throwable t) {
            return fallback;
        }
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
