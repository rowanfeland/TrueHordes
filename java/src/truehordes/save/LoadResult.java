package truehordes.save;

import java.util.List;
import truehordes.store.Entity;

/** Outcome of reading the mod's save file. */
public final class LoadResult {
    public enum Status { OK, MISSING, RECOVERED, FAILED }

    public final Status status;
    public final double worldAgeHours;
    public final long nextId;
    public final List<Entity> entities;
    public final String message;

    LoadResult(Status status, double worldAgeHours, long nextId, List<Entity> entities, String message) {
        this.status = status;
        this.worldAgeHours = worldAgeHours;
        this.nextId = nextId;
        this.entities = entities;
        this.message = message;
    }

    static LoadResult empty(Status status, String message) {
        return new LoadResult(status, Double.NaN, 1, List.of(), message);
    }
}
