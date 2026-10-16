package truehordes.core;

import java.io.IOException;
import java.util.Locale;
import java.util.function.LongSupplier;
import truehordes.Version;
import truehordes.save.LoadResult;
import truehordes.save.SaveFile;
import truehordes.sim.Scheduler;
import truehordes.sim.SimClock;
import truehordes.population.Takeover;
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
    private final Takeover takeover = new Takeover(store);
    private State state = State.IDLE;
    private String disabledReason = "";
    private String lastLoad = "";
    private double lastSaveHours = Double.NaN;
    private long lastWriteMillis = Long.MIN_VALUE;
    private final PopulationRunner population;
    private final SaveWriter saveWriter = new SaveWriter();
    /** How long a full save or stop waits for the save thread, in real milliseconds. */
    static final long SAVE_FLUSH_MS = 10_000L;
    private truehordes.sim.SimSettings simSettings = truehordes.sim.SimSettings.defaults();
    private truehordes.sim.Simulation sim;
    private SimThread simThread;
    /** World hour as of the last tick, for the simulation thread (it never calls into the game). */
    private volatile double simNowHours;
    /** Mirrors takesOverPopulation() without the lock, for the per-zombie, per-frame rally hook. */
    private volatile boolean rallyOff;
    /** The running simulation for the sound capture (read lock-free on the main thread), and a tick counter. */
    private volatile truehordes.sim.Simulation soundSink;
    private volatile long ticks;
    private double lastConservationHours = Double.NaN;

    /** Background slice length and pause (spec 5). */
    static final long SIM_SLICE_NANOS = 2_000_000L;
    static final long SIM_SLEEP_MILLIS = 10L;

    public Core(GameEnv env) {
        this(env, System::currentTimeMillis);
    }

    public Core(GameEnv env, LongSupplier wallClockMillis) {
        this.env = env;
        this.wallClockMillis = wallClockMillis;
        this.population = new PopulationRunner(env, store, world, takeover);
    }

    public State state() { return state; }

    public Store store() { return store; }

    public Stats stats() { return stats; }

    public Scheduler scheduler() { return scheduler; }

    public World world() { return world; }

    /** The off-screen simulation (null unless running). Use under this core's lock. */
    public synchronized truehordes.sim.Simulation simulation() { return sim; }

    public synchronized SimThread simThread() { return simThread; }

    /** The simulation settings read when the core started (defaults before that). */
    public synchronized truehordes.sim.SimSettings simSettings() { return simSettings; }

    /** Hand-over bookkeeping; changed only under this core's lock. */
    public Takeover takeover() { return takeover; }

    /** The mod owns the zombies right now: running on the machine that owns the simulation. */
    public synchronized boolean ownsPopulation() { return state == State.RUNNING && !env.isClient(); }

    /** The native spawner must stay switched off (even before the core starts, at world load). */
    public synchronized boolean takesOverPopulation() {
        return state != State.DISABLED && !env.isClient() && env.enabled();
    }

    /**
     * The game's room zombie spawn is skipped: the mod seeds (or seeded) this world's buildings. Also before the core
     * starts in a new world, because the game can see the first rooms in the frames before the core's first tick.
     */
    public synchronized boolean skipRoomSpawns() {
        if (state == State.RUNNING) return population.seededWorld() && population.buildingZombiesSeeded();
        return state == State.IDLE && !env.isClient() && env.enabled() && env.populationSettings().buildingZombiesSeeded
            && env.newWorld();
    }

    /** Vanilla rally groups are skipped (lock-free; refreshed every tick). */
    public boolean rallyOff() { return rallyOff && state != State.DISABLED; }

    public truehordes.sim.Simulation soundSink() { return soundSink; }

    public long tickCount() { return ticks; }

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
        rallyOff = !env.isClient() && env.enabled();
        ticks++;
        population.tick(env.worldAgeHours());
        simNowHours = env.worldAgeHours();
        if (sim != null && sim.takeLayoutDue()) env.spawnLoadedChunks();
        if (sim != null && Log.isDebug("core") && (Double.isNaN(lastConservationHours)
                || simNowHours - lastConservationHours >= 1.0)) {
            lastConservationHours = simNowHours;
            String problem = sim.checkConservation("hourly check");
            if (!problem.isEmpty()) Log.warn(problem);
        }
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
        try {
            simSettings = env.simSettings();
        } catch (Throwable t) {
            Log.error("could not read the simulation settings; using defaults", t);
            simSettings = truehordes.sim.SimSettings.defaults();
        }
        LoadResult r = SaveFile.read(env.stateFile());
        store.clear();
        for (ZombieRecord e : r.records) store.restore(e);
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
        population.start(r);
        startSimulation(r, now);
        env.spawnLoadedChunks(); // again, now that the simulation exists: lays out hordes already at the loaded area
        return true;
    }

    private void startSimulation(LoadResult r, double now) {
        simNowHours = now;
        sim = new truehordes.sim.Simulation(store, new truehordes.sim.WorldMapView(world), simSettings, System.nanoTime(),
            now);
        soundSink = sim;
        for (LoadResult.SavedHorde h : r.hordes) {
            sim.restoreHorde(h.id(), h.x(), h.y(), h.heading(), h.pauseMinutes(), h.members(), h.dormant());
        }
        sim.hordes().setNextId(r.hordeNextId);
        try {
            sim.setPlayers(env.playerNearby());
        } catch (Throwable t) {
            Log.error("could not read where the players are; the simulation treats every area as far away", t);
        }
        takeover.setRejoin(sim.handOver()::rejoin);
        population.setHordeCounter(sim::hordeMembersPerCell);
        simThread = new SimThread(this, sim, () -> simNowHours, world::ready, SIM_SLICE_NANOS, SIM_SLEEP_MILLIS);
        simThread.start();
        Log.info("simulation started: " + store.size() + " wanderers, " + sim.hordes().size() + " hordes");
    }

    /**
     * Lays out every held horde touching chunk (cx, cy) as held records (the arrivals then make them real). Returns the
     * chunks the members landed in.
     */
    public synchronized java.util.Set<Long> layOutHordesTouching(int cx, int cy, truehordes.population.Takeover.FloorCheck floors) {
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        if (sim == null || state != State.RUNNING) return chunks;
        for (ZombieRecord r : sim.handOver().layOutTouching(cx, cy, floors)) {
            chunks.add(((long) r.chunkX() << 32) | (r.chunkY() & 0xFFFFFFFFL));
        }
        return chunks;
    }

    /** Writes the mod's state; called from the game's save. Returns true if a file was written. */
    public synchronized boolean save() {
        if (!submitSave()) return false;
        // A full save (the game's own save, quitting) waits until the file is on disk.
        if (!saveWriter.flush(SAVE_FLUSH_MS)) Log.warn("the mod's save is still being written");
        return true;
    }

    /** Encodes the state in memory (under this lock) and hands the file write to the save thread. */
    private boolean submitSave() {
        if (state != State.RUNNING || env.isClient()) return false;
        try {
            byte[] data = SaveFile.encode(store, sim == null ? null : sim.hordes(), env.worldAgeHours(),
                population.mode() == PopulationRunner.Mode.SEEDED, population.blob());
            saveWriter.submit(env.stateFile(), data);
            lastSaveHours = env.worldAgeHours();
            lastWriteMillis = wallClockMillis.getAsLong();
            Log.debug("core", "saved " + store.size() + " wanderers and " + (sim == null ? 0 : sim.hordes().memberCount())
                + " zombies in hordes");
            return true;
        } catch (IOException e) {
            Log.error("could not encode the mod's save", e);
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
        return submitSave(); // written on the save thread: the main thread only pays for the encode
    }

    /** World unloaded: forget everything (no save here; the game's save already ran). */
    public synchronized void stop() {
        if (state == State.RUNNING) Log.info("stopped");
        if (state != State.DISABLED) state = State.IDLE;
        if (!saveWriter.flush(SAVE_FLUSH_MS)) Log.warn("the mod's last save was still being written at stop");
        if (simThread != null) simThread.stop();
        simThread = null;
        sim = null;
        soundSink = null;
        takeover.setRejoin(null);
        population.setHordeCounter(null);
        lastConservationHours = Double.NaN;
        store.setListener(null);
        store.clear();
        takeover.reset();
        world.stop();
        clock.clear();
        stats.clear();
        lastSaveHours = Double.NaN;
        lastWriteMillis = Long.MIN_VALUE;
        population.stop();
    }

    public synchronized String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("True Hordes ").append(Version.VERSION).append(" | state ").append(state);
        if (state == State.DISABLED) sb.append(" (").append(disabledReason).append(')');
        sb.append(" | mode ").append(env.isClient() ? "MP client" : env.isServer() ? "dedicated server" : "single player");
        if (state == State.RUNNING) sb.append(population.status(env.worldAgeHours()));
        if (sim != null) {
            int biggest = 0;
            for (truehordes.sim.Horde h : sim.hordes().all()) biggest = Math.max(biggest, h.size());
            sb.append(" | wanderers ").append(store.size()).append(", hordes ").append(sim.hordes().size())
                .append(" (biggest ").append(biggest).append(", ").append(sim.hordes().memberCount()).append(" in hordes)");
        }
        else sb.append(" | held ").append(store.size());
        sb.append(" | last load ").append(lastLoad.isEmpty() ? "-" : lastLoad);
        sb.append(" | last save ").append(Double.isNaN(lastSaveHours) ? "-" : String.format(Locale.ROOT, "world hour %.2f", lastSaveHours));
        return sb.toString();
    }
}
