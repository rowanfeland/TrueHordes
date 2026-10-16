package truehordes.sim;

/**
 * A sound zombies can hear, active from {@code startHours} to {@code endHours} (world hours). Since sub-project 5a every
 * world sound the game makes becomes one (captured on the main thread, see Simulation.offerSound); a sound the game
 * re-sends every tick (an alarm, a generator) is one ongoing event whose end is pushed back on each re-send. Horde
 * moaning is not an event: hordes are heard directly (see Hearing), always at the lowest priority. Used on the
 * simulation thread only; the counters feed the sound viewer.
 */
public final class SoundEvent {
    public static final int OTHER = 0, GUNFIRE = 1, VEHICLE = 2, APPLIANCE = 3, ALARM = 4, DEBUG = 5, HELICOPTER = 6,
        META = 7, THUNDER = 8, SCREAM = 9, DOG = 10, CAR_ALARM = 11, ALIFE = 12, STORM_THUNDER = 13;

    /** Thunder heard through the game, or made by a storm away from the players (sub-project 5b). */
    public boolean thunder() { return kind == THUNDER || kind == STORM_THUNDER; }

    private final long id;
    private final float x, y, loudnessDb;
    private final double startHours;
    private double endHours;
    private final boolean moaning;
    private final long sourceHordeId;
    public final int kind;
    public final boolean repeating;
    /** Identifies the game's source object for folding re-sends (0 = none). */
    public final long sourceKey;
    /** Wanderers and hordes that went for it, and zombies that split off a horde for it. */
    public int wanderers, hordes, splitOff;
    /** In-game minute an ongoing sound was last checked against walkers that had not rolled for it yet. */
    long lastBroadcastMinute = Long.MIN_VALUE;

    public SoundEvent(long id, float x, float y, float loudnessDb, double startHours, double endHours, boolean moaning,
                      long sourceHordeId) {
        this(id, x, y, loudnessDb, startHours, endHours, moaning, sourceHordeId, OTHER, false, 0);
    }

    public SoundEvent(long id, float x, float y, float loudnessDb, double startHours, double endHours, boolean moaning,
                      long sourceHordeId, int kind, boolean repeating, long sourceKey) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.loudnessDb = loudnessDb;
        this.startHours = startHours;
        this.endHours = endHours;
        this.moaning = moaning;
        this.sourceHordeId = sourceHordeId;
        this.kind = kind;
        this.repeating = repeating;
        this.sourceKey = sourceKey;
    }

    public long id() { return id; }
    public float x() { return x; }
    public float y() { return y; }
    public float loudnessDb() { return loudnessDb; }
    public double startHours() { return startHours; }
    public double endHours() { return endHours; }
    public boolean moaning() { return moaning; }
    public long sourceHordeId() { return sourceHordeId; }

    /** Reach in tiles (where it fades to the hearing threshold): the game's radius. */
    public float radius() { return Hearing.range(loudnessDb); }

    void extendTo(double hours) { if (hours > endHours) endHours = hours; }

    public boolean activeAt(double hours) { return hours >= startHours && hours < endHours; }
}
