package truehordes.sim;

/** Simulation time from in-game world age (hours). Paused = no change; higher game speed = bigger steps. */
public final class SimClock {
    private double lastHours = Double.NaN;
    private double totalHours;

    /** Starts counting from this world age. */
    public void reset(double worldAgeHours) {
        lastHours = worldAgeHours;
        totalHours = 0;
    }

    /** Forgets everything (world unloaded). */
    public void clear() {
        lastHours = Double.NaN;
        totalHours = 0;
    }

    /** Elapsed in-game hours since the previous call; 0 when paused, on the first call, or if time went back. */
    public double advance(double worldAgeHours) {
        if (Double.isNaN(lastHours)) {
            reset(worldAgeHours);
            return 0;
        }
        double d = worldAgeHours - lastHours;
        lastHours = worldAgeHours;
        if (!(d > 0)) return 0;
        totalHours += d;
        return d;
    }

    public boolean started() { return !Double.isNaN(lastHours); }

    public double totalHours() { return totalHours; }
}
