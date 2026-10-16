package truehordes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import truehordes.store.ZombieRecord;
import zombie.characters.IsoZombie;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.popman.ZombiePopulationManager;
import zombie.popman.ZombieStateFlags;

/**
 * Calls the game's own private ZombiePopulationManager members (resolved once): the spawn methods the native
 * population uses (sitting against walls, crawlers, fake dead, outfits, the player spawn-point rule), the native config
 * setter, and the registry of live zombie IDs.
 */
final class Spawner {
    enum Result { SPAWNED, REFUSED }

    private static Method addStanding, addMoving, configFloat, isLive;
    private static Field realZombies, minPerChunk, maxPerChunk;
    private static volatile boolean ready;

    private Spawner() {}

    /** Returns null when ready, otherwise why the takeover cannot run. */
    static String init() {
        try {
            Class<?> pm = ZombiePopulationManager.class;
            addStanding = pm.getDeclaredMethod(HookTargets.ADD_STANDING, float.class, float.class, float.class,
                IsoDirections.class, int.class, ZombieStateFlags.class, int.class);
            addMoving = pm.getDeclaredMethod(HookTargets.ADD_MOVING, float.class, float.class, float.class,
                IsoDirections.class, int.class, ZombieStateFlags.class, int.class, int.class, int.class);
            configFloat = pm.getDeclaredMethod(HookTargets.CONFIG_FLOAT, String.class, float.class);
            realZombies = pm.getDeclaredField(HookTargets.REFLECTED_FIELDS[0]);
            minPerChunk = pm.getDeclaredField(HookTargets.REFLECTED_FIELDS[1]);
            maxPerChunk = pm.getDeclaredField(HookTargets.REFLECTED_FIELDS[2]);
            for (var m : new java.lang.reflect.AccessibleObject[] {addStanding, addMoving, configFloat, realZombies,
                    minPerChunk, maxPerChunk}) {
                m.setAccessible(true);
            }
            isLive = realZombies.getType().getDeclaredMethod("isLive", int.class);
            isLive.setAccessible(true);
            ready = true;
            return null;
        } catch (Throwable t) {
            ready = false;
            return t.toString();
        }
    }

    static boolean ready() { return ready; }

    /** The same floor test the game's spawn uses before it would hand a zombie back to the native population. */
    static boolean floorAt(int x, int y, int z) {
        IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
        if (sq == null) return false;
        return sq.solidFloorCached ? sq.solidFloor : sq.TreatAsSolidFloor();
    }

    static boolean isLive(int persistentId) throws ReflectiveOperationException {
        return (Boolean) isLive.invoke(realZombies.get(null), persistentId);
    }

    static Result spawn(ZombieRecord r) throws ReflectiveOperationException {
        ArrayList<IsoZombie> list = IsoWorld.instance.currentCell.getZombieList();
        int before = list.size();
        IsoDirections dir = IsoDirections.fromIndex(ZombieRecord.dirOf(r.heading)); // where it is walking now
        ZombieStateFlags state = ZombieStateFlags.fromInt(r.stateFlags);
        ZombiePopulationManager pm = ZombiePopulationManager.instance;
        if (r.hasTarget()) {
            addMoving.invoke(pm, r.x(), r.y(), r.z, dir, r.outfitId, state, r.targetX, r.targetY, r.persistentId);
        } else {
            addStanding.invoke(pm, r.x(), r.y(), r.z, dir, r.outfitId, state, r.persistentId);
        }
        if (list.size() <= before) return Result.REFUSED;
        IsoZombie z = list.get(list.size() - 1);
        ModDataCodec.load(z, r.modData);
        PopulationBridge.rememberHorde(z, r.hordeId);
        Hooks.PILOTS.adopt(z, r);
        return Result.SPAWNED;
    }

    /** Sends a value to the native spawner's config. */
    static void configFloat(String key, float value) throws ReflectiveOperationException {
        configFloat.invoke(null, key, value);
    }

    static float minPerChunk() throws IllegalAccessException { return minPerChunk.getFloat(ZombiePopulationManager.instance); }

    static float maxPerChunk() throws IllegalAccessException { return maxPerChunk.getFloat(ZombiePopulationManager.instance); }
}
