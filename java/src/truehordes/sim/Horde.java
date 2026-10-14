package truehordes.sim;

import java.util.ArrayList;
import java.util.List;
import truehordes.store.ZombieRecord;

/**
 * A horde off screen: one moving point carrying its members' records whole (outfit, persistent ID, state flags,
 * ModData), so the right zombies come back when it becomes real. Members are not moved individually (spec 3).
 * Changed only under the core's lock.
 */
public final class Horde {
    public final long id;
    public float x, y;
    /** Radians, 0 = east, y grows south. */
    public float heading;
    public float pauseMinutes;
    /** The louder horde this one is trailing (0 none); informational, for Hordes_hordes(). */
    public long followHordeId;
    public final List<ZombieRecord> members;
    /** Scheduling (not saved): the in-game minute of its next update and the hour of its last. */
    long dueMinute = Long.MIN_VALUE;
    double simHours = Double.NaN;
    /** Query stamp used by SpatialGrid to report a horde once per query. */
    int seenStamp;

    Horde(long id, float x, float y, float heading, List<ZombieRecord> members) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.heading = heading;
        this.members = new ArrayList<>(members);
    }

    public int size() { return members.size(); }

    /** Radius in tiles the horde covers: grows with the square root of its size (10: ~3.6, 10,000: 52). */
    public float spread() { return spreadOf(members.size()); }

    public static float spreadOf(int n) { return (float) (2 + Math.sqrt(n) * 0.5); }

    /** Moaning loudness at the horde in dB: 40 + 10 log10(size) (10: 50 dB, 1,000: 70 dB, 10,000: 80 dB). */
    public float loudnessDb() { return (float) (40 + 10 * Math.log10(Math.max(1, members.size()))); }

    /** Every member crawls (ZombieStateFlag.Crawling): a horde keeps its walkers' pace, so only these are slow. */
    public boolean allCrawlers() {
        for (ZombieRecord r : members) if ((r.stateFlags & 2) == 0) return false;
        return !members.isEmpty();
    }
}
