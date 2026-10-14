package truehordes.sim;

import java.util.List;

/**
 * Who hears what off screen (spec 4.4): loudness in dB falls off with distance like real sound (-20 dB per tenfold
 * distance) and is heard down to 30 dB. Any sound event beats every horde's moaning (moaning is always the lowest
 * priority); a horde never hears itself, and its own moaning drowns out any horde quieter than itself, so a small horde
 * hears a big one (when it is audible where the small one is) and follows, while the big one does not notice.
 * Sub-project 5 retunes the physics on this core.
 */
public final class Hearing {
    /** Quietest level a zombie reacts to, in dB. */
    public static final float THRESHOLD_DB = 30f;
    /** How strongly a heard sound steers a walker's heading (Movement pull weight). */
    public static final float EVENT_PULL = 3f;
    /** How strongly moaning steers a walker (weaker than a sound event). */
    public static final float MOAN_PULL = 1.5f;

    private Hearing() {}

    /** A walker's reason to head somewhere: towards (x, y) with a Movement pull weight; {@code event} null = moaning. */
    public record Pull(float x, float y, float weight, SoundEvent event, Horde horde) {}

    public static float levelAt(float sourceDb, float distance) {
        return sourceDb - 20f * (float) Math.log10(Math.max(1f, distance));
    }

    public static boolean audible(float sourceDb, float distance) { return levelAt(sourceDb, distance) >= THRESHOLD_DB; }

    /** Distance in tiles at which a sound fades to the threshold. */
    public static float range(float sourceDb) { return (float) Math.pow(10, (sourceDb - THRESHOLD_DB) / 20.0); }

    /** Range of a horde's moaning measured from its centre (its circle edge carries the sound). */
    public static float moanRange(Horde h) { return range(h.loudnessDb()) + h.spread(); }

    /** The loudness of a listener's own moaning: hordes no louder than this are drowned out; 0 for a lone wanderer. */
    public static float maskingDb(Horde h) {
        return h == null ? 0f : h.loudnessDb();
    }

    /**
     * What a walker at (x, y) heads for: the loudest audible sound event if any; otherwise the loudest audible moaning
     * of another horde that is louder than the walker's own horde ({@code ownDb}, see maskingDb); otherwise null.
     */
    public static Pull choose(float x, float y, long ownHordeId, float ownDb, List<SoundEvent> events,
                              List<Horde> nearbyHordes) {
        SoundEvent bestEvent = null;
        float bestLevel = THRESHOLD_DB;
        for (int i = 0; i < events.size(); i++) {
            SoundEvent e = events.get(i);
            if (e.moaning()) continue;
            float level = levelAt(e.loudnessDb(), dist(x, y, e.x(), e.y()));
            if (level >= bestLevel) {
                bestLevel = level;
                bestEvent = e;
            }
        }
        if (bestEvent != null) return new Pull(bestEvent.x(), bestEvent.y(), EVENT_PULL, bestEvent, null);
        Horde bestHorde = null;
        for (int i = 0; i < nearbyHordes.size(); i++) {
            Horde h = nearbyHordes.get(i);
            if (h.id == ownHordeId) continue;
            float level = levelAt(h.loudnessDb(), dist(x, y, h.x, h.y) - h.spread());
            if (level >= THRESHOLD_DB && h.loudnessDb() > ownDb && (bestHorde == null || level > bestLevel)) {
                bestLevel = level;
                bestHorde = h;
            }
        }
        return bestHorde == null ? null : new Pull(bestHorde.x, bestHorde.y, MOAN_PULL, null, bestHorde);
    }

    static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
