package truehordes.core;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import truehordes.sim.Simulation;
import truehordes.util.Log;

/**
 * Runs the off-screen simulation on its own daemon thread in short slices under the core's lock, sleeping between
 * them, so the game's main thread only ever waits for one slice at most. Stopping only raises a flag (the core stops
 * it while holding its lock); the thread sees the flag at its next slice and ends.
 */
public final class SimThread {
    private final Object lock;
    private final Simulation sim;
    private final DoubleSupplier nowHours;
    private final BooleanSupplier ready;
    private final long sliceNanos;
    private final long sleepMillis;
    private volatile boolean running;
    private Thread thread;
    private boolean failedOnce;

    public SimThread(Object lock, Simulation sim, DoubleSupplier nowHours, BooleanSupplier ready, long sliceNanos,
                     long sleepMillis) {
        this.lock = lock;
        this.sim = sim;
        this.nowHours = nowHours;
        this.ready = ready;
        this.sliceNanos = sliceNanos;
        this.sleepMillis = sleepMillis;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "TrueHordes-sim");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    /** Asks the thread to end; safe to call while holding the lock (does not wait). */
    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    public boolean running() {
        Thread t = thread;
        return running && t != null && t.isAlive();
    }

    /** Waits (without holding the lock) until the thread has ended. */
    public boolean awaitStopped(long millis) throws InterruptedException {
        Thread t = thread;
        if (t == null) return true;
        t.join(millis);
        return !t.isAlive();
    }

    /** Pause between slices: short while the simulation is behind the game clock (fast-forward), else the normal one. */
    public static long pauseMillis(long lagMinutes, long normalMillis) {
        return lagMinutes > LAG_MINUTES ? CATCH_UP_SLEEP_MILLIS : normalMillis;
    }

    /** Behind by more than this many in-game minutes: catch up. */
    static final long LAG_MINUTES = 10;
    /** Pause between slices while catching up (2 ms slices: about two thirds of one background core). */
    static final long CATCH_UP_SLEEP_MILLIS = 2;

    private void loop() {
        while (running) {
            long pause = sleepMillis;
            synchronized (lock) {
                if (!running) break;
                try {
                    if (ready.getAsBoolean()) {
                        sim.step(nowHours.getAsDouble(), sliceNanos);
                        pause = pauseMillis(sim.lagMinutes(), sleepMillis);
                    }
                } catch (Throwable t) {
                    if (!failedOnce) {
                        failedOnce = true;
                        Log.error("simulation step failed (the simulation keeps running)", t);
                    }
                }
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }
    }
}
