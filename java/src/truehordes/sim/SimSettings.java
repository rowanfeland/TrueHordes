package truehordes.sim;

/**
 * Everything the off-screen simulation can be tuned with: the mod's sandbox options plus the game's own Sight and
 * Speed settings and day length. Immutable; read once when the core starts.
 */
public record SimSettings(int stragglerOneIn, int hordeFormSize, int hordeMinSize, double followChancePerHour,
                          double offRoadChance, double breakAwayChance, int sightSetting, int speedSetting,
                          double realSecondsPerGameMinute, double trailChance, double moanResponsePerHour,
                          double joinChancePerHour, int buildingStragglerOneIn,
                          double bumpFollowChance, int peakDay, double soundEdgeChance,
                          double soundCertainShare, int hearingSetting, double travellerShare,
                          double crowdDriftPerHour, double lockdownDays) {

    /** Share of the time an off-screen zombie spends walking rather than standing (vanilla zombies idle a lot). */
    public static final double DUTY = 0.5;
    /** Off-screen walking speeds in tiles per real second (the game's speed is animation-driven; estimates). */
    public static final double SHAMBLER = 0.45;
    public static final double FAST = 0.9;
    public static final double SPRINTER = 2.2;

    /** Sandbox lore.speed values. */
    public static final int SPEED_SPRINTER = 1;
    public static final int SPEED_FAST_SHAMBLER = 2;
    public static final int SPEED_SHAMBLER = 3;
    public static final int SPEED_RANDOM = 4;

    public SimSettings {
        if (stragglerOneIn < 1) throw new IllegalArgumentException("stragglerOneIn must be at least 1");
        if (buildingStragglerOneIn < 1) throw new IllegalArgumentException("buildingStragglerOneIn must be at least 1");
        if (hordeMinSize < 2 || hordeFormSize < 2) throw new IllegalArgumentException("horde sizes must be at least 2");
        if (hordeMinSize > hordeFormSize) throw new IllegalArgumentException("hordeMinSize above hordeFormSize");
        checkChance(followChancePerHour, "followChancePerHour");
        checkChance(offRoadChance, "offRoadChance");
        checkChance(breakAwayChance, "breakAwayChance");
        checkChance(trailChance, "trailChance");
        checkChance(moanResponsePerHour, "moanResponsePerHour");
        checkChance(joinChancePerHour, "joinChancePerHour");
        checkChance(bumpFollowChance, "bumpFollowChance");
        if (peakDay < 1) throw new IllegalArgumentException("peakDay must be at least 1");
        checkChance(soundEdgeChance, "soundEdgeChance");
        checkChance(travellerShare, "travellerShare");
        checkChance(crowdDriftPerHour, "crowdDriftPerHour");
        if (!(lockdownDays >= 0)) throw new IllegalArgumentException("lockdownDays must be >= 0");
        if (!(soundCertainShare > 0 && soundCertainShare <= 1)) {
            throw new IllegalArgumentException("soundCertainShare must be in (0, 1]");
        }
        if (!(realSecondsPerGameMinute > 0)) throw new IllegalArgumentException("realSecondsPerGameMinute must be > 0");
    }

    private static void checkChance(double v, String name) {
        if (!(v >= 0 && v <= 1)) throw new IllegalArgumentException(name + " must be in 0..1");
    }

    /**
     * Defaults: the user's decisions (2026-10-14) and the game's defaults (Normal sight, shamblers, 1-hour days). The
     * horde rates were tuned on the real map (LouisvilleScenario, 30,000 zombies): about half of Louisville in hordes
     * after 10 days, most after a month, two city-sized hordes after 2 to 3 months. The bump-follow chance is the full
     * chance reached on the population peak day (vanilla default day 28); it ramps up from none on day 1. Round 8
     * (millers stay packed in their neighbourhoods): bump 3% and crowd drift 2%/h give about 65-70% of Louisville in
     * hordes by day 7 (LouisvilleScenario, 30,000).
     */
    public static SimSettings defaults() {
        return new SimSettings(75, 10, 5, 0.01, 0.15, 0.005, 2, SPEED_SHAMBLER, 2.5, 0.30, 0.01, 0.05, 10, 0.03, 28, 0.40, 0.25, 2, 0.05, 0.03, 7);
    }

    public Builder toBuilder() { return new Builder(this); }

    public static final class Builder {
        private int stragglerOneIn, hordeFormSize, hordeMinSize, sightSetting, speedSetting, buildingStragglerOneIn,
            peakDay, hearingSetting;
        private double followChancePerHour, offRoadChance, breakAwayChance, realSecondsPerGameMinute, trailChance,
            moanResponsePerHour, joinChancePerHour, bumpFollowChance, soundEdgeChance, soundCertainShare,
            travellerShare, crowdDriftPerHour, lockdownDays;

        private Builder(SimSettings s) {
            stragglerOneIn = s.stragglerOneIn;
            hordeFormSize = s.hordeFormSize;
            hordeMinSize = s.hordeMinSize;
            followChancePerHour = s.followChancePerHour;
            offRoadChance = s.offRoadChance;
            breakAwayChance = s.breakAwayChance;
            sightSetting = s.sightSetting;
            speedSetting = s.speedSetting;
            realSecondsPerGameMinute = s.realSecondsPerGameMinute;
            trailChance = s.trailChance;
            moanResponsePerHour = s.moanResponsePerHour;
            joinChancePerHour = s.joinChancePerHour;
            buildingStragglerOneIn = s.buildingStragglerOneIn;
            bumpFollowChance = s.bumpFollowChance;
            peakDay = s.peakDay;
            soundEdgeChance = s.soundEdgeChance;
            soundCertainShare = s.soundCertainShare;
            hearingSetting = s.hearingSetting;
            travellerShare = s.travellerShare;
            crowdDriftPerHour = s.crowdDriftPerHour;
            lockdownDays = s.lockdownDays;
        }

        public Builder stragglerOneIn(int v) { stragglerOneIn = v; return this; }
        public Builder hordeFormSize(int v) { hordeFormSize = v; return this; }
        public Builder hordeMinSize(int v) { hordeMinSize = v; return this; }
        public Builder followChancePerHour(double v) { followChancePerHour = v; return this; }
        public Builder offRoadChance(double v) { offRoadChance = v; return this; }
        public Builder breakAwayChance(double v) { breakAwayChance = v; return this; }
        public Builder sightSetting(int v) { sightSetting = v; return this; }
        public Builder speedSetting(int v) { speedSetting = v; return this; }
        public Builder realSecondsPerGameMinute(double v) { realSecondsPerGameMinute = v; return this; }
        public Builder trailChance(double v) { trailChance = v; return this; }
        public Builder moanResponsePerHour(double v) { moanResponsePerHour = v; return this; }
        public Builder joinChancePerHour(double v) { joinChancePerHour = v; return this; }
        public Builder buildingStragglerOneIn(int v) { buildingStragglerOneIn = v; return this; }
        public Builder bumpFollowChance(double v) { bumpFollowChance = v; return this; }
        public Builder peakDay(int v) { peakDay = v; return this; }
        public Builder soundEdgeChance(double v) { soundEdgeChance = v; return this; }
        public Builder soundCertainShare(double v) { soundCertainShare = v; return this; }
        public Builder hearingSetting(int v) { hearingSetting = v; return this; }
        public Builder travellerShare(double v) { travellerShare = v; return this; }
        public Builder crowdDriftPerHour(double v) { crowdDriftPerHour = v; return this; }
        public Builder lockdownDays(double v) { lockdownDays = v; return this; }

        public SimSettings build() {
            return new SimSettings(stragglerOneIn, hordeFormSize, hordeMinSize, followChancePerHour, offRoadChance,
                breakAwayChance, sightSetting, speedSetting, realSecondsPerGameMinute, trailChance, moanResponsePerHour,
                joinChancePerHour, buildingStragglerOneIn, bumpFollowChance, peakDay, soundEdgeChance,
                soundCertainShare, hearingSetting, travellerShare, crowdDriftPerHour, lockdownDays);
        }
    }
}
