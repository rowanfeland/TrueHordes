package truehordes.sound;

import truehordes.sim.SoundModel;

/**
 * How a real zombie near a player scores a sound (sub-project 5a spec section 5): the level where it stands from the
 * energy model, less a little for walls; heard or not by one remembered roll of the lone-zombie chance; a heard sound
 * scores its sound pressure {@code 10^(level/20)}, so vanilla's own "twice as strong" switch rule is the 6 dB rule.
 * Pure: the patches gather the game's inputs and call this.
 */
public final class RealHearing {
    private final SoundModel model;

    public RealHearing(SoundModel model) { this.model = model; }

    /** True if a zombie hears a sound arriving at {@code level} dB, for a roll {@code u} in [0, 1). */
    public boolean rollHeard(float level, double u) { return u < model.loneChance(level); }

    /**
     * Wall loss in dB: vanilla scales the distance ratio by 1.2 for another room and by 1.4 more if one side is
     * outdoors, which is a radius shrunk by sqrt of that: -0.79 dB and -2.25 dB.
     */
    public static float roomLossDb(boolean otherRoom, boolean oneOutside) {
        if (!otherRoom) return 0f;
        double f = oneOutside ? 1.2 * 1.4 : 1.2;
        return (float) (10 * Math.log10(f));
    }

    /** The score of a heard sound arriving at {@code level} dB. */
    public static float score(float level) { return (float) Math.pow(10, level / 20.0); }

    /** A zombie's last few rolls (sound id, heard), oldest dropped first. Main thread only. */
    public static final class Memory {
        public static final int SIZE = 16;
        private final long[] ids = new long[SIZE];
        private final boolean[] heard = new boolean[SIZE];
        private int next;

        public Boolean get(long id) {
            for (int i = 0; i < SIZE; i++) if (ids[i] == id && id != 0) return heard[i];
            return null;
        }

        public void put(long id, boolean h) {
            ids[next] = id;
            heard[next] = h;
            next = (next + 1) % SIZE;
        }
    }
}
