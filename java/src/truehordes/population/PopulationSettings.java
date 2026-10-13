package truehordes.population;

/**
 * The population settings the mod uses instead of the native spawner: the vanilla sandbox zombie settings plus True
 * Hordes' own options. Immutable; build with {@link #toBuilder()}.
 */
public final class PopulationSettings {
    public final float multiplier, startMultiplier, peakMultiplier;
    public final int peakDay;
    public final float respawnHours, respawnUnseenHours, respawnMultiplier;
    public final float minPerChunk, maxPerChunk, uniformPerChunk;
    public final boolean uniformDistribution;
    /** Sandbox "Zombies" (1 insane .. 5 low; 4 normal), used by the room formula. */
    public final int zombiesSetting;
    public final boolean buildingZombiesSeeded;
    public final float edgeArrivalMonths;

    private PopulationSettings(Builder b) {
        multiplier = b.multiplier;
        startMultiplier = b.startMultiplier;
        peakMultiplier = b.peakMultiplier;
        peakDay = b.peakDay;
        respawnHours = b.respawnHours;
        respawnUnseenHours = b.respawnUnseenHours;
        respawnMultiplier = b.respawnMultiplier;
        minPerChunk = b.minPerChunk;
        maxPerChunk = b.maxPerChunk;
        uniformPerChunk = b.uniformPerChunk;
        uniformDistribution = b.uniformDistribution;
        zombiesSetting = b.zombiesSetting;
        buildingZombiesSeeded = b.buildingZombiesSeeded;
        edgeArrivalMonths = b.edgeArrivalMonths;
    }

    /** 42.21 sandbox defaults; min/max per chunk are ZombiePopulationManager's field defaults. */
    public static PopulationSettings vanillaDefaults() { return new Builder().build(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.multiplier = multiplier;
        b.startMultiplier = startMultiplier;
        b.peakMultiplier = peakMultiplier;
        b.peakDay = peakDay;
        b.respawnHours = respawnHours;
        b.respawnUnseenHours = respawnUnseenHours;
        b.respawnMultiplier = respawnMultiplier;
        b.minPerChunk = minPerChunk;
        b.maxPerChunk = maxPerChunk;
        b.uniformPerChunk = uniformPerChunk;
        b.uniformDistribution = uniformDistribution;
        b.zombiesSetting = zombiesSetting;
        b.buildingZombiesSeeded = buildingZombiesSeeded;
        b.edgeArrivalMonths = edgeArrivalMonths;
        return b;
    }

    public static final class Builder {
        private float multiplier = 0.65f, startMultiplier = 1f, peakMultiplier = 1.5f;
        private int peakDay = 28;
        private float respawnHours = 72f, respawnUnseenHours = 16f, respawnMultiplier = 0.1f;
        private float minPerChunk = 0f, maxPerChunk = 255f, uniformPerChunk = 0.2f;
        private boolean uniformDistribution;
        private int zombiesSetting = 4;
        private boolean buildingZombiesSeeded = true;
        private float edgeArrivalMonths = 12f;

        public Builder multiplier(float v) { multiplier = v; return this; }
        public Builder startMultiplier(float v) { startMultiplier = v; return this; }
        public Builder peakMultiplier(float v) { peakMultiplier = v; return this; }
        public Builder peakDay(int v) { peakDay = v; return this; }
        public Builder respawnHours(float v) { respawnHours = v; return this; }
        public Builder respawnUnseenHours(float v) { respawnUnseenHours = v; return this; }
        public Builder respawnMultiplier(float v) { respawnMultiplier = v; return this; }
        public Builder minPerChunk(float v) { minPerChunk = v; return this; }
        public Builder maxPerChunk(float v) { maxPerChunk = v; return this; }
        public Builder uniformPerChunk(float v) { uniformPerChunk = v; return this; }
        public Builder uniformDistribution(boolean v) { uniformDistribution = v; return this; }
        public Builder zombiesSetting(int v) { zombiesSetting = v; return this; }
        public Builder buildingZombiesSeeded(boolean v) { buildingZombiesSeeded = v; return this; }
        public Builder edgeArrivalMonths(float v) { edgeArrivalMonths = v; return this; }

        public PopulationSettings build() { return new PopulationSettings(this); }
    }
}
