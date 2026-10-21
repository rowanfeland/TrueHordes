package truehordes.events;

import truehordes.sim.SoundEvent;

/**
 * Project A-Life's ambient world sounds (its meta events: distant gunfire, sirens, car crashes, screams) are played to
 * the player only; True Hordes makes the ones with a real source a world sound where they play (user, 2026-10-21), so
 * zombies there hear them. Radii are True Hordes' defaults; eerie ambience, jets and radio stay sound only, helicopters
 * come through A-Life's HeliDirector.
 */
public final class ALifeSounds {
    private ALifeSounds() {}

    public static final int SIREN_RADIUS = 200, CAR_RADIUS = 150, SCREAM_RADIUS = 60, DOG_RADIUS = 40;

    /** {kind, radius} for A-Life's sound {@code name}, or null when it is no world sound. */
    public static int[] classify(String name, double louderMultiplier) {
        if (name == null) return null;
        switch (name) {
            case "MetaPistol1", "MetaPistol2", "MetaPistol3", "MetaShotgun1", "MetaAssaultRifle1", "PALWorldShootout",
                 "PALWorldShootout2", "PALWorldHunterRifle":
                return new int[] {SoundEvent.ALIFE, Gunfire.rifleRadius(louderMultiplier)};
            case "PALWorldArtillery1", "PALWorldArtillery2", "PALWorldArtillery3", "PALWorldWarExplosions":
                return new int[] {SoundEvent.META, Gunfire.VANILLA_META_RADIUS};
            case "PALWorldPoliceSiren", "PALWorldSirensServices", "PALWorldSessionAlarm":
                return new int[] {SoundEvent.ALARM, SIREN_RADIUS};
            case "PALWorldCarCrash1", "PALWorldCarCrash2", "PALWorldCarCrash3", "PALWorldCarAlarm", "PALWorldCarAlarm2":
                return new int[] {SoundEvent.CAR_ALARM, CAR_RADIUS};
            case "MetaScream", "PALWorldScream", "PALWorldDistress1", "PALWorldDistress2", "PALWorldTortured":
                return new int[] {SoundEvent.SCREAM, SCREAM_RADIUS};
            case "MetaDogBark":
                return new int[] {SoundEvent.DOG, DOG_RADIUS};
            default:
                return null;
        }
    }
}
