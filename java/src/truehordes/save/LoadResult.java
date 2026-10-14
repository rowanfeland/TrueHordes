package truehordes.save;

import java.util.List;
import truehordes.store.ZombieRecord;

/** Outcome of reading the mod's save file. */
public final class LoadResult {
    public enum Status { OK, MISSING, RECOVERED, FAILED }

    public final Status status;
    public final double worldAgeHours;
    public final long nextId;
    public final List<ZombieRecord> records;
    /** The mod seeded this world's population (a new game); false for a gradual takeover. */
    public final boolean seeded;
    /** The population bookkeeping (CellBook) as saved; empty when there is none. */
    public final byte[] population;
    public final String message;
    /** Hordes held off screen (version 3); empty for older saves. */
    public final List<SavedHorde> hordes;
    public final long hordeNextId;

    /** A saved horde: its members are whole records. */
    public record SavedHorde(long id, float x, float y, float heading, float pauseMinutes, List<ZombieRecord> members) {}

    LoadResult(Status status, double worldAgeHours, long nextId, List<ZombieRecord> records, boolean seeded,
               byte[] population, String message) {
        this(status, worldAgeHours, nextId, records, seeded, population, message, List.of(), 1);
    }

    LoadResult(Status status, double worldAgeHours, long nextId, List<ZombieRecord> records, boolean seeded,
               byte[] population, String message, List<SavedHorde> hordes, long hordeNextId) {
        this.hordes = hordes;
        this.hordeNextId = hordeNextId;
        this.status = status;
        this.worldAgeHours = worldAgeHours;
        this.nextId = nextId;
        this.records = records;
        this.seeded = seeded;
        this.population = population;
        this.message = message;
    }

    LoadResult withStatus(Status s, String msg) {
        return new LoadResult(s, worldAgeHours, nextId, records, seeded, population, msg, hordes, hordeNextId);
    }

    static LoadResult empty(Status status, String message) {
        return new LoadResult(status, Double.NaN, 1, List.of(), false, new byte[0], message);
    }
}
