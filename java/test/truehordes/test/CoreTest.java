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
        Check.equal(500, core.seedTest(500, 1000, 2000, 300, new Random(1)), "seeded");
        Check.isTrue(core.save(), "saved");
        core.stop();
        Check.equal(0, core.store().size(), "store cleared on stop");
        core.tick();
        Check.equal(500, core.store().size(), "restored after restart");
        Check.equal(500, core.clearTest(), "cleared placeholders");
    }

    public static void testStopThenStartOtherSaveDoesNotLeak() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        core.tick();
        core.seedTest(10, 0, 0, 50, new Random(2));
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
        core.seedTest(3, 0, 0, 10, new Random(3));
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
            for (int i = 0; i < 2000; i++) core.store().create((byte) 0, i, i, (byte) 0);
            core.clearTest();
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

    public static void testSeedOnlyWhileRunning() throws Exception {
        FakeEnv env = new FakeEnv(file());
        Core core = new Core(env);
        Check.equal(0, core.seedTest(5, 0, 0, 10, new Random(4)), "not running yet");
        core.tick();
        Check.equal(5, core.seedTest(5, 0, 0, 10, new Random(4)), "running");
        Check.isTrue(Files.notExists(SaveFile.backupOf(env.stateFile)), "seeding does not save");
    }
}
