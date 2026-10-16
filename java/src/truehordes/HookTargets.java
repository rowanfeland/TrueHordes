package truehordes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** The game methods the mod hooks. Shared by the patches, the start-up self-check and the offline test. */
public final class HookTargets {
    public static final String POPMAN = "zombie.popman.ZombiePopulationManager";
    public static final String MCD = "zombie.MapCollisionData";
    public static final String TICK = "updateMain";
    public static final String SAVE = "save";
    public static final String STOP = "stop";
    /** Single-player hot-save: called on the main thread when B42 writes game time between full saves. */
    public static final String GAMETIME = "zombie.GameTime";
    public static final String HOT_SAVE_SP = "saveToBufferMap";
    /** Dedicated server: game time is queued for saving on a timer from the main loop. */
    public static final String SERVER_CHUNK_LOADER = "zombie.network.ServerChunkLoader";
    public static final String HOT_SAVE_SERVER = "saveLater";
    /** Population takeover (sub-project 3). */
    public static final String VZM = "zombie.VirtualZombieManager";
    public static final String CONFIG_RELOADED = "onConfigReloaded";
    public static final String CHUNK_UNLOADING = "removeChunkFromWorld";
    public static final String WALKED_OFF = "virtualizeZombie";
    public static final String POP_CHUNK_LOADED = "addChunkToWorld";
    public static final String ROOM_SPOTTED = "roomSpotted";
    /** Vanilla rally groups (sub-project 4): only ZombieGroupManager.update creates them. */
    public static final String GROUP_MANAGER = "zombie.ai.ZombieGroupManager";
    /** Every world sound zombies can hear goes through WorldSoundManager.addSound (all overloads; sub-project 5a). */
    public static final String WSM = "zombie.WorldSoundManager";
    public static final String ADD_SOUND = "addSound";
    /** Where a real zombie scores its current sound and the biggest sound where it stands (sub-project 5a). */
    public static final String SOUND_ATTRACT = "getSoundAttract";
    public static final String BIGGEST_SOUND = "getBiggestSoundZomb";
    /** Vanilla's meta events and thunder (single player / client, and server): their sounds get labelled. */
    public static final String AMBIENT_STREAM = "zombie.AmbientStreamManager";
    public static final String AMBIENT_SOUND = "zombie.AmbientSoundManager";
    public static final String GUN_EVENT = "doGunEvent";
    public static final String THUNDER_EVENT = "handleThunderEvent";
    /** Vanilla's random dogs, screams, owls and wolves (sub-project 5b: the mod plays these). */
    public static final String RANDOM_AMBIENT = "addRandomAmbient";
    /** Lightning strikes (vanilla storms and True Weathers): a strike is also a sound inside the storm (5b). */
    /** Crowd-scaled break-in (sub-project 6): a zombie's thump on each kind of thumpable object. */
    public static final String DOOR = "zombie.iso.objects.IsoDoor";
    public static final String WINDOW = "zombie.iso.objects.IsoWindow";
    public static final String THUMPABLE = "zombie.iso.objects.IsoThumpable";
    public static final String BARRICADE = "zombie.iso.objects.IsoBarricade";
    public static final String THUMP = "Thump";
    public static final String THUNDERSTORM = "zombie.iso.weather.ThunderStorm";
    public static final String TRIGGER_THUNDER = "triggerThunderEvent";
    public static final String RALLY = "update";
    /** Private ZombiePopulationManager members the takeover calls or reads by reflection. */
    public static final String ADD_STANDING = "addZombieStanding";
    public static final String ADD_MOVING = "addZombieMoving";
    public static final String CONFIG_FLOAT = "n_configFloat";
    public static final String[] REFLECTED_FIELDS = {"realZombies", "zombiesMinPerChunk", "zombiesMaxPerChunk"};
    /** An area loaded around a player (MapCollisionData has just computed its 64 tile bits). */
    public static final String CHUNK_LOADED = "addChunkToWorld";
    /** One tile changed (building, breaking, burning). */
    public static final String SQUARE_CHANGED = "squareChanged";

    public static final String[][] ALL = {
        {POPMAN, TICK},
        {MCD, SAVE},
        {POPMAN, STOP},
        {GAMETIME, HOT_SAVE_SP},
        {SERVER_CHUNK_LOADER, HOT_SAVE_SERVER},
        {POPMAN, CONFIG_RELOADED},
        {POPMAN, CHUNK_UNLOADING},
        {POPMAN, WALKED_OFF},
        {POPMAN, POP_CHUNK_LOADED},
        {VZM, ROOM_SPOTTED},
        {GROUP_MANAGER, RALLY},
        {WSM, ADD_SOUND},
        {WSM, SOUND_ATTRACT},
        {WSM, BIGGEST_SOUND},
        {AMBIENT_STREAM, GUN_EVENT},
        {AMBIENT_STREAM, THUNDER_EVENT},
        {AMBIENT_STREAM, RANDOM_AMBIENT},
        {THUNDERSTORM, TRIGGER_THUNDER},
        {DOOR, THUMP},
        {WINDOW, THUMP},
        {THUMPABLE, THUMP},
        {BARRICADE, THUMP},
        {AMBIENT_SOUND, GUN_EVENT},
        {AMBIENT_SOUND, THUNDER_EVENT},
    };

    /** Live map updates. Optional: if missing, the world map still builds from the files. */
    public static final String[][] LIVE = {
        {MCD, CHUNK_LOADED},
        {MCD, SQUARE_CHANGED},
    };

    /** MapCollisionData's private per-tile rule methods, called by LiveBits. */
    public static final String[] RULES = {"isSolid", "isBlockedN", "isBlockedW", "isWater", "isRoom"};

    private HookTargets() {}

    /** Returns "class.method" for every required target the lookup does not report. */
    public static List<String> missing(Function<String, Set<String>> methodsOfClass) {
        return missing(ALL, methodsOfClass);
    }

    public static List<String> missing(String[][] targets, Function<String, Set<String>> methodsOfClass) {
        List<String> out = new ArrayList<>();
        for (String[] t : targets) {
            Set<String> names = methodsOfClass.apply(t[0]);
            if (names == null || !names.contains(t[1])) out.add(t[0] + "." + t[1]);
        }
        return out;
    }
}
