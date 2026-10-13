package truehordes.core;

import java.io.IOException;
import java.util.Locale;
import java.util.function.LongSupplier;
import truehordes.Version;
import truehordes.save.LoadResult;
import truehordes.save.SaveFile;
import truehordes.sim.Scheduler;
import truehordes.sim.SimClock;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;
import truehordes.util.Log;
import truehordes.util.Stats;
import truehordes.world.World;
import truehordes.world.WorldSource;

/**
 * Lifecycle of the simulation for one loaded world. Starts on the first tick after a world is ready, saves when the
 * game saves, clears everything on stop. Only the machine that owns the simulation runs it (never an MP client).
 * Entry points are synchronized: a dedicated server's shutdown hook saves and stops on another thread.
 */
public final class Core {
    public enum State { IDLE, RUNNING, OFF, DISABLED }

    /** A mod save more than this many in-game hours away from the world's time is reported. */
    public static final double MISMATCH_HOURS = 0.25;

    /** Hot saves (B42 writes the world continuously) are written at most this often, in real milliseconds. */
    public static final long HOT_SAVE_MIN_MS = 20_000L;

    private final GameEnv env;
    private final LongSupplier wallClockMillis;
    private final Store store = new Store();
    private final Stats stats = new Stats();
    private final SimClock clock = new SimClock();
    private final Scheduler scheduler = new Scheduler(stats);
    private final World world = new World();
    private State state = State.IDLE;
    private String disabledReason = "";
    private String lastLoad = "";
    private double lastSaveHours = Double.NaN;
    private long lastWriteMillis = Long.MIN_VALUE;
    /** This world's population was seeded by the mod (a new game). Kept across saves. */
    private boolean seeded;
    /** Population bookkeeping blob, kept across saves (filled from sub-project 3 task 7). */
    private byte[] population = new byte[0];

    public Core(GameEnv env) {
        this(env, System::currentTimeMillis);
    }

    public Core(GameEnv env, LongSupplier wallClockMillis) {
        this.env = env;
        this.wallClockMillis = wallClockMillis;
    }

    public State state() { return state; }

    public Store store() { return store; }

    public Stats stats() { return stats; }

    public Scheduler scheduler() { return scheduler; }

    public World world() { return world; }

    /** Permanently switches the mod off for this game session. */
    public synchronized void disable(String reason) {
        state = State.DISABLED;
        disabledReason = reason;
        Log.warn("disabled: " + reason);
    }

    public synchronized void tick() {
        if (state == State.DISABLED || state == State.OFF || env.isClient() || !env.isWorldReady()) return;
        if (state == State.IDLE && !start()) return;
        world.tick();
        long t0 = System.nanoTime();
        scheduler.run(clock.advance(env.worldAgeHours()));
        stats.count("held", store.size());
        stats.time("tick", System.nanoTime() - t0);
    }

    private boolean start() {
        if (!env.enabled()) {
            state = State.OFF;
            Log.info("switched off in sandbox options for this world");
            return false;
        }
        Log.setDebug("core", env.debugLogging());
        LoadResult r = SaveFile.read(env.stateFile());
        store.clear();
        for (ZombieRecord e : r.records) store.restore(e);
        seeded = r.seeded;
        population = r.population;
        store.setNextId(r.nextId);
        double now = env.worldAgeHours();
        if (r.status == LoadResult.Status.OK || r.status == LoadResult.Status.RECOVERED) {
            double diff = now - r.worldAgeHours;
            if (Math.abs(diff) > MISMATCH_HOURS) {
                Log.warn(String.format(Locale.ROOT,
                    "mod save is from world hour %.2f but the world is at hour %.2f (%.2f h apart); using it anyway",
                    r.worldAgeHours, now, diff));
            }
        }
        lastLoad = r.status + ": " + r.message;
        clock.reset(now);
        state = State.RUNNING;
        Log.info("started with " + store.size() + " held zombies (" + lastLoad + ")");
        try {
            WorldSource src = env.worldSource();
            if (src != null) world.start(src);
            else Log.warn("map data unavailable; terrain knowledge is off");
        } catch (Throwable t) {
            Log.error("could not read the map data; terrain knowledge is off", t);
        }
        return true;
    }

    /** Writes the mod's state; called from the game's save. Returns true if a file was written. */
    public synchronized boolean save() {
        if (state != State.RUNNING || env.isClient()) return false;
        try {
            SaveFile.write(env.stateFile(), store, env.worldAgeHours(), seeded, population);
            lastSaveHours = env.worldAgeHours();
            lastWriteMillis = wallClockMillis.getAsLong();
            Log.debug("core", "saved " + store.size() + " held zombies");
            return true;
        } catch (IOException e) {
            Log.error("could not write the mod's save file " + env.stateFile(), e);
            return false;
        }
    }

    /**
     * Called when the game hot-saves the world between full saves (single player after chunk saves, the dedicated
     * server on its game-time timer). Writes at most every HOT_SAVE_MIN_MS so the main thread does not hitch.
     */
    public synchronized boolean hotSave() {
        if (state != State.RUNNING || env.isClient()) return false;
        long now = wallClockMillis.getAsLong();
        if (lastWriteMillis != Long.MIN_VALUE && now - lastWriteMillis < HOT_SAVE_MIN_MS) return false;
        return save();
    }

    /** World unloaded: forget everything (no save here; the game's save already ran). */
    public synchronized void stop() {
        if (state == State.RUNNING) Log.info("stopped");
        if (state != State.DISABLED) state = State.IDLE;
        store.clear();
        world.stop();
        clock.clear();
        stats.clear();
        lastSaveHours = Double.NaN;
        lastWriteMillis = Long.MIN_VALUE;
        seeded = false;
        population = new byte[0];
    }

    public synchronized String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("True Hordes ").append(Version.VERSION).append(" | state ").append(state);
        if (state == State.DISABLED) sb.append(" (").append(disabledReason).append(')');
        sb.append(" | mode ").append(env.isClient() ? "MP client" : env.isServer() ? "dedicated server" : "single player");
        sb.append(" | held ").append(store.size());
        sb.append(" | last load ").append(lastLoad.isEmpty() ? "-" : lastLoad);
        sb.append(" | last save ").append(Double.isNaN(lastSaveHours) ? "-" : String.format(Locale.ROOT, "world hour %.2f", lastSaveHours));
        return sb.toString();
    }
}
