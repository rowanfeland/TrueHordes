package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import truehordes.core.Core;
import truehordes.save.SaveFile;
import truehordes.util.Log;

public final class CoreTest {
    private static Path file() throws Exception {
        return Files.createTempDirectory("th-core-test").resolve("truehordes/state.bin");
    }

    /** Records scattered around (1000, 2000), standing, no outfit or ID. */
    private static void addRecords(Core core, int n, Random rnd) {
        for (int i = 0; i < n; i++) StoreTest.add(core.store(), 1000 + rnd.nextInt(300), 2000 + rnd.nextInt(300));
    }

    public static void testStartsOnFirstTickAndRunsSystems() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        List<Double> runs = new ArrayList<>();
        core.scheduler().register("probe", 0, runs::add);
        Check.equal(Core.State.IDLE, core.state(), "idle before first tick");
        core.tick();
        Check.equal(Core.State.RUNNING, core.state(), "running after first tick");
        env.worldAgeHours += 0.5;
        core.tick();
        Check.equal(1, runs.size(), "system ran once time moved");
        Check.near(0.5, runs.get(0), 1e-12, "elapsed game time");
    }

    public static void testSimSettingsReachTheCore() throws Exception {
        FakeEnv env = new FakeEnv(file());
        env.sim = truehordes.sim.SimSettings.defaults().toBuilder().stragglerOneIn(40).build();
        Core core = new Core(env);
        core.tick();
        Check.equal(40, core.simSettings().stragglerOneIn(), "sandbox value reaches the core");
    }

    public static void testSimSettingsValidated() {
        Check.throwsAny(() -> truehordes.sim.SimSettings.defaults().toBuilder().hordeMinSize(11).build(),
            "min size above form size");
        Check.throwsAny(() -> truehordes.sim.SimSettings.defaults().toBuilder().stragglerOneIn(0).build(),
            "straggler one-in below 1");
        truehordes.sim.SimSettings d = truehordes.sim.SimSettings.defaults();
        Check.equal(75, d.stragglerOneIn(), "default stragglers");
        Check.equal(10, d.hordeFormSize(), "default form size");
        Check.equal(5, d.hordeMinSize(), "default min size");
    }

    public static void testSimThreadStartsAndStops() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        Check.isTrue(core.simThread() != null && core.simThread().running(), "simulation thread runs once started");
        truehordes.core.SimThread t = core.simThread();
        core.stop();
        Check.isTrue(t.awaitStopped(1000), "thread ends within a second of stop");
    }

    public static void testSimThreadSurvivesAnException() throws Exception {
        int[] calls = {0};
        truehordes.sim.MapView flaky = new truehordes.sim.MapView() {
            @Override public int rank(int cx, int cy) {
                if (calls[0]++ == 0) throw new IllegalStateException("test failure");
                return 4;
            }
            @Override public boolean inMap(int cx, int cy) { return true; }
            @Override public boolean tight(int cx, int cy) { return false; }
        };
        truehordes.store.Store store = new truehordes.store.Store();
        for (int i = 0; i < 10; i++) {
            store.add(100 + i, 100, 0, (byte) 0, 0, 0, 0, truehordes.store.ZombieRecord.NO_TARGET,
                truehordes.store.ZombieRecord.NO_TARGET, null, false);
        }
        truehordes.sim.Simulation sim = new truehordes.sim.Simulation(store, flaky,
            truehordes.sim.SimSettings.defaults(), 1, 0);
        Object lock = new Object();
        double[] now = {0};
        truehordes.core.SimThread t = new truehordes.core.SimThread(lock, sim, () -> now[0] += 0.05, () -> true,
            2_000_000L, 1);
        t.start();
        Thread.sleep(300);
        Check.isTrue(t.running(), "still running after a failed step");
        Check.isTrue(calls[0] > 1, "kept stepping");
        t.stop();
        Check.isTrue(t.awaitStopped(1000), "stops");
    }

    public static void testStatusShowsHordes() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        Check.isTrue(core.status().contains("hordes"), "status lists hordes: " + core.status());
        core.stop();
    }

    public static void testHotSaveDoesNotWriteOnCallerThread() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        Check.isTrue(core.hotSave(), "hot save submitted");
        boolean saveThread = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("TrueHordes-save")) saveThread = true;
        Check.isTrue(saveThread, "the file is written by the save thread");
        core.stop();
        Check.equal(truehordes.save.LoadResult.Status.OK, SaveFile.read(env.stateFile).status, "on disk after stop");
    }

    public static void testStopFlushesPendingSave() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        core.hotSave();
        core.stop();
        Check.equal(truehordes.save.LoadResult.Status.OK, SaveFile.read(env.stateFile).status,
            "stop waited for the pending write");
    }

    public static void testHordesSurviveSaveAndReload() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        synchronized (core) {
            java.util.List<truehordes.store.ZombieRecord> m = new java.util.ArrayList<>();
            for (int i = 0; i < 15; i++) {
                m.add(new truehordes.store.ZombieRecord(900_000 + i, 50, 50, 0, (byte) 0, 0, 0, 0,
                    truehordes.store.ZombieRecord.NO_TARGET, truehordes.store.ZombieRecord.NO_TARGET, null, false));
            }
            core.simulation().restoreHorde(core.simulation().hordes().nextId(), 50, 50, 0, 0, m);
        }
        Check.isTrue(core.save(), "saved");
        core.stop();
        Core again = new Core(env);
        again.tick();
        synchronized (again) {
            Check.equal(1, again.simulation().hordes().size(), "the horde came back");
            Check.equal(15, again.simulation().hordes().memberCount(), "with its members");
        }
        again.stop();
    }

    /** Final review I1: hordes already touching the loaded area at load are laid out right after the simulation starts. */
    public static void testArrivalsRunAgainOnceTheSimulationExists() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        Check.isTrue(env.spawnLoadedCalls >= 2, "arrivals run again after the simulation started (" + env.spawnLoadedCalls + ")");
        core.stop();
    }

    /** Final review I2: the rally hook runs per real zombie per frame and must not wait for the simulation's lock. */
    public static void testRallyCheckDoesNotWaitForTheLock() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(() -> {
            synchronized (core) {
                held.countDown();
                try { Thread.sleep(500); } catch (InterruptedException e) { }
            }
        });
        t.start();
        held.await();
        long t0 = System.nanoTime();
        boolean off = core.rallyOff();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Check.isTrue(off, "rally groups off while the mod runs");
        Check.isTrue(ms < 100, "answered without waiting for the lock (" + ms + " ms)");
        t.join();
        core.stop();
    }

    /** The simulation thread hurries (1 ms pauses) only while it is behind the game clock. */
    public static void testSimThreadCatchesUpWhenBehind() {
        Check.equal(10L, truehordes.core.SimThread.pauseMillis(0, 10), "keeping up: normal pause");
        Check.equal(2L, truehordes.core.SimThread.pauseMillis(120, 10), "two in-game hours behind: catch up");
    }

    public static void testWaitsForWorld() throws Exception {
        FakeEnv env = new FakeEnv(file());
        env.worldReady = false;
        Core core = new Core(env);
        core.tick();
        Check.equal(Core.State.IDLE, core.state(), "no world yet");
    }

    public static void testSaveThenRestartRestoresEntities() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        addRecords(core, 500, new Random(1));
        Check.isTrue(core.save(), "saved");
        core.stop();
        Check.equal(0, core.store().size(), "store cleared on stop");
        core.tick();
        Check.equal(500, core.store().size(), "restored after restart");
    }

    public static void testStopThenStartOtherSaveDoesNotLeak() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        addRecords(core, 10, new Random(2));
        core.save();
        core.stop();
        env.stateFile = file();          // a different save
        env.worldAgeHours = 5.0;
        core.tick();
        Check.equal(0, core.store().size(), "nothing from the other save");
        env.worldAgeHours = 5.25;
        List<Double> runs = new ArrayList<>();
        core.scheduler().register("probe", 0, runs::add);
        core.tick();
        Check.near(0.25, runs.get(0), 1e-12, "clock restarted from the new world's time");
    }

    public static void testMismatchedSaveTimeWarnsButLoads() throws Exception {
        List<String> lines = new ArrayList<>();
        Log.setSink(lines::add);
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        addRecords(core, 3, new Random(3));
        core.save();               // mod save at hour 100
        core.stop();
        env.worldAgeHours = 104.0; // world save from 4 hours later (e.g. crash between the two writes)
        core.tick();
        Check.equal(3, core.store().size(), "still loads the newest valid file");
        Check.isTrue(lines.stream().anyMatch(l -> l.contains("WARN") && l.contains("world")), "warned about mismatch");
        Log.setSink(null);
    }

    public static void testClientNeverRunsOrWrites() throws Exception {
        FakeEnv env = new FakeEnv(file());
        env.client = true;
        Core core = new Core(env);
        core.tick();
        Check.equal(Core.State.IDLE, core.state(), "client stays idle");
        Check.isTrue(!core.save(), "client does not save");
        Check.isTrue(!Files.exists(env.stateFile), "no file written");
    }

    public static void testSandboxDisabledStaysOff() throws Exception {
        FakeEnv env = new FakeEnv(file());
        env.enabled = false;
        Core core = new Core(env);
        core.tick();
        Check.equal(Core.State.OFF, core.state(), "off by sandbox");
        Check.isTrue(!core.save(), "no save when off");
        Check.isTrue(!Files.exists(env.stateFile), "no file written");
        core.stop();
        env.enabled = true;
        core.tick();
        Check.equal(Core.State.RUNNING, core.state(), "next world can run");
    }

    public static void testDisabledIsPermanent() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.disable("missing game method");
        core.tick();
        core.stop();
        core.tick();
        Check.equal(Core.State.DISABLED, core.state(), "stays disabled");
        Check.isTrue(core.status().contains("missing game method"), "status explains");
    }

    /** Review Important #2: a dedicated server's shutdown hook saves on another thread while the main loop ticks. */
    public static void testSaveFromAnotherThreadWhileTicking() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        core.scheduler().register("churn", 0, dt -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 2000; i++) ids.add(StoreTest.add(core.store(), i, i).id);
            for (long id : ids) core.store().remove(id);
        });
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread saver = new Thread(() -> {
            for (int i = 0; i < 300; i++) {
                try {
                    core.save();
                } catch (Throwable t) {
                    errors.add(t);
                }
            }
        });
        saver.start();
        while (saver.isAlive()) {
            env.worldAgeHours += 0.001;
            core.tick();
        }
        saver.join();
        Check.equal(List.of(), errors, "saves while ticking must not fail");
        Check.equal(truehordes.save.LoadResult.Status.OK, SaveFile.read(env.stateFile).status, "last save readable");
    }

    /** Review Important #1: B42 hot-saves the world continuously; the mod follows at most every HOT_SAVE_MIN_MS. */
    public static void testHotSaveIsRateLimitedButFullSaveIsNot() throws Exception {
        FakeEnv env = new FakeEnv(file());
        long[] now = {1_000_000L};
        Core core = new Core(env, () -> now[0]);
        core.tick();
        Check.isTrue(core.hotSave(), "first hot save writes");
        now[0] += 5_000;
        Check.isTrue(!core.hotSave(), "hot save 5 s later is skipped");
        now[0] += 1_000;
        Check.isTrue(core.save(), "full save always writes");
        now[0] += 4_000;
        Check.isTrue(!core.hotSave(), "hot save 4 s after a full save is skipped");
        now[0] += Core.HOT_SAVE_MIN_MS;
        Check.isTrue(core.hotSave(), "hot save after the interval writes");
        env.client = true;
        now[0] += Core.HOT_SAVE_MIN_MS;
        Check.isTrue(!core.hotSave(), "never on an MP client");
    }

    public static void testCoreStartsAndStopsTheWorldMap() throws Exception {
        truehordes.util.Log.setSink(line -> {});
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("th-core-world");
            FakeEnv env = new FakeEnv(dir.resolve("state.bin"));
            env.worldSource = new truehordes.world.WorldSource(0, 0, 0, 0, new java.util.HashMap<>(), null, java.util.List.of());
            truehordes.core.Core core = new truehordes.core.Core(env);
            core.tick();
            Check.isTrue(core.world().state() != truehordes.world.World.State.OFF, "build started by the first tick");
            Check.isTrue(core.world().awaitBuilt(10_000), "built");
            core.tick();
            Check.equal(truehordes.world.World.State.READY, core.world().state(), "published by a later tick");
            core.stop();
            Check.equal(truehordes.world.World.State.OFF, core.world().state(), "dropped on stop");
        } finally {
            truehordes.util.Log.setSink(null);
        }
    }

    public static void testNoWorldSourceStillRuns() throws Exception {
        truehordes.util.Log.setSink(line -> {});
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("th-core-world");
            FakeEnv env = new FakeEnv(dir.resolve("state.bin"));
            truehordes.core.Core core = new truehordes.core.Core(env);
            core.tick();
            Check.equal(truehordes.core.Core.State.RUNNING, core.state(), "core runs without a map");
            Check.equal(truehordes.world.World.State.OFF, core.world().state(), "no map");
        } finally {
            truehordes.util.Log.setSink(null);
        }
    }

    /** Two all-walkable cells of collision files and a matching map source (intensity 4: about 2 per chunk). */
    private static FakeEnv seedEnv() throws Exception {
        Path dir = Files.createTempDirectory("th-core-seed");
        java.util.Map<Long, Path> files = new java.util.HashMap<>();
        files.put(truehordes.world.WorldSource.key(0, 0), FakeCells.cell().write(dir, "chunkdata_0_0.bin"));
        files.put(truehordes.world.WorldSource.key(1, 0), FakeCells.cell().write(dir, "chunkdata_1_0.bin"));
        FakeEnv env = new FakeEnv(dir.resolve("truehordes/state.bin"));
        env.worldSource = new truehordes.world.WorldSource(0, 0, 1, 0, files, null, List.of());
        env.map = new SeederTest.FakeMap(4);
        env.worldAgeHours = 0.0;
        return env;
    }

    private static void runUntilSeeded(Core core, FakeEnv env) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end && !core.status().contains("population seeded")) {
            core.tick();
            Thread.sleep(5);
        }
        Check.isTrue(core.status().contains("population seeded"), "seeded in time: " + core.status());
    }

    public static void testNewWorldSeedsOnceWorldMapReady() throws Exception {
        Log.setSink(line -> {});
        try {
            FakeEnv env = seedEnv();
            env.newWorld = true;
            Core core = new Core(env);
            core.tick();
            Check.equal(0, core.store().size(), "nothing before the world map is ready");
            Check.isTrue(core.skipRoomSpawns(), "room spawns already skipped while seeding (no double sets)");
            runUntilSeeded(core, env);
            Check.isTrue(core.store().size() > 1000, "seeded: " + core.store().size());
            Check.isTrue(core.skipRoomSpawns(), "room spawns skipped with the option on");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testSeedingSpawnsLoadedChunksAfterwards() throws Exception {
        Log.setSink(line -> {});
        try {
            FakeEnv env = seedEnv();
            env.newWorld = true;
            Core core = new Core(env);
            core.tick();
            Check.equal(2, env.spawnLoadedCalls, "arrivals for chunks loaded at start (again once the simulation exists)");
            runUntilSeeded(core, env);
            Check.equal(3, env.spawnLoadedCalls, "and again once the seeded zombies exist");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testExistingSaveIsTakenOverGradually() throws Exception {
        Log.setSink(line -> {});
        try {
            FakeEnv env = seedEnv();
            env.newWorld = false;
            Core core = new Core(env);
            for (int i = 0; i < 50; i++) {
                core.tick();
                Thread.sleep(5);
            }
            Check.equal(0, core.store().size(), "no seeding in an existing save");
            Check.isTrue(!core.skipRoomSpawns(), "rooms keep the vanilla spawn");
            Check.isTrue(core.status().contains("population gradual"), core.status());
        } finally {
            Log.setSink(null);
        }
    }

    public static void testSeededFlagSurvivesReload() throws Exception {
        Log.setSink(line -> {});
        try {
            FakeEnv env = seedEnv();
            env.newWorld = true;
            Core core = new Core(env);
            core.tick();
            runUntilSeeded(core, env);
            int seeded = core.store().size();
            Check.isTrue(core.save(), "saved");
            core.stop();
            env.newWorld = false; // the save exists now
            core.tick();
            Check.equal(seeded, core.store().size(), "same population, no second seeding");
            Check.isTrue(core.status().contains("population seeded"), core.status());
            Check.isTrue(core.skipRoomSpawns(), "still a mod-seeded world");
        } finally {
            Log.setSink(null);
        }
    }

    public static void testHourlyUpdateGrowsUnvisitedCells() throws Exception {
        Log.setSink(line -> {});
        try {
            FakeEnv env = seedEnv();
            env.newWorld = true;
            Core core = new Core(env);
            core.tick();
            runUntilSeeded(core, env);
            int before = core.store().size();
            env.worldAgeHours = 40 * 24.0; // past the peak day (peak 1.5x)
            core.tick();
            Check.isTrue(core.store().size() > before * 1.3, "grew toward the peak: " + before + " -> " + core.store().size());
        } finally {
            Log.setSink(null);
        }
    }

    public static void testStatusShowsCounts() throws Exception {
        Log.setSink(line -> {});
        try {
            FakeEnv env = seedEnv();
            env.newWorld = true;
            Core core = new Core(env);
            core.tick();
            runUntilSeeded(core, env);
            String s = core.status();
            for (String part : List.of("held ", "real ", "target ", "hand-over: out ")) Check.isTrue(s.contains(part), part + " in: " + s);
        } finally {
            Log.setSink(null);
        }
    }

    /** Final review: rooms seen in the first frames of a new game, before the core starts, must not spawn a second set. */
    public static void testRoomSpawnsSkippedBeforeStartInNewWorld() throws Exception {
        FakeEnv env = new FakeEnv(file());
        env.newWorld = true;
        Core core = new Core(env);
        Check.equal(Core.State.IDLE, core.state(), "not started yet");
        Check.isTrue(core.skipRoomSpawns(), "new world, building option on: skipped from the first frame");
        env.settings = env.settings.toBuilder().buildingZombiesSeeded(false).build();
        Check.isTrue(!core.skipRoomSpawns(), "option off: vanilla room spawns");
        env.settings = env.settings.toBuilder().buildingZombiesSeeded(true).build();
        env.newWorld = false;
        Check.isTrue(!core.skipRoomSpawns(), "existing save: vanilla room spawns");
        env.newWorld = true;
        env.enabled = false;
        Check.isTrue(!core.skipRoomSpawns(), "mod switched off in the sandbox: vanilla");
    }
}
