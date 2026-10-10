package truehordes.sim;

import java.util.ArrayList;
import java.util.List;
import truehordes.util.Log;
import truehordes.util.Stats;

/** Runs systems at a cadence in in-game minutes (0 = every tick in which time moved) and times them. Main thread only. */
public final class Scheduler {
    public interface Task {
        void run(double elapsedHours);
    }

    private static final class Entry {
        final String name;
        final double intervalHours;
        final Task task;
        double pending;

        Entry(String name, double intervalHours, Task task) {
            this.name = name;
            this.intervalHours = intervalHours;
            this.task = task;
        }
    }

    /** Tolerance for accumulated floating-point time (about 4 microseconds of game time). */
    private static final double EPSILON_HOURS = 1e-9;

    private final List<Entry> entries = new ArrayList<>();
    private final Stats stats;

    public Scheduler(Stats stats) {
        this.stats = stats;
    }

    public void register(String name, double everyGameMinutes, Task task) {
        if (everyGameMinutes < 0) throw new IllegalArgumentException("negative interval for " + name);
        entries.add(new Entry(name, everyGameMinutes / 60.0, task));
    }

    /** Advances all systems by the elapsed in-game hours. A failing system is logged once and skipped for this run. */
    public void run(double elapsedHours) {
        for (Entry e : entries) {
            e.pending += elapsedHours;
            boolean due = e.intervalHours > 0 ? e.pending >= e.intervalHours - EPSILON_HOURS : elapsedHours > 0;
            if (!due) continue;
            double dt = e.pending;
            e.pending = 0;
            long t0 = System.nanoTime();
            try {
                e.task.run(dt);
            } catch (Throwable t) {
                Log.errorOnce("system:" + e.name + ":" + t.getClass().getName(),
                    "system " + e.name + " failed and was skipped this run", t);
            }
            stats.time(e.name, System.nanoTime() - t0);
        }
    }

    public int size() { return entries.size(); }
}
