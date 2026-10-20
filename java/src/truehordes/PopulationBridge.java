package truehordes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import truehordes.core.Core;
import truehordes.population.PlayerNearby;
import truehordes.population.ZombieSnapshot;
import truehordes.store.ZombieRecord;
import truehordes.util.Log;
import zombie.ai.states.PathFindState;
import zombie.ai.states.WalkTowardState;
import zombie.characters.IsoZombie;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.popman.ZombiePopulationManager;
import zombie.popman.ZombieStateFlags;

/**
 * The game side of the takeover (main thread; the core's lock is taken around store changes because a dedicated
 * server saves from its shutdown thread). Tracks which chunks are loaded, takes zombies leaving into the mod's store,
 * spawns held zombies when their chunk loads, and zeroes the native spawner's population settings.
 */
final class PopulationBridge {
    /** The native settings that make it create zombies; the mod does the spawning instead. */
    private static final String[] NATIVE_ZEROED = {"PopulationMultiplier", "PopulationStartMultiplier",
        "PopulationPeakMultiplier", "RespawnMultiplier", "RespawnHours"};

    // Concurrent: the simulation thread reads these (PlayerNearby) while the main thread loads and unloads chunks.
    private static final Set<Long> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<Long, Integer> loadedPerCell = new java.util.concurrent.ConcurrentHashMap<>();
    /** Real zombies laid out from a horde, so they can rejoin it when they leave again (main thread; weak keys). */
    private static final Map<IsoZombie, Long> spawnedFromHorde = new java.util.WeakHashMap<>();

    static void rememberHorde(IsoZombie z, long hordeId) {
        if (hordeId != 0) spawnedFromHorde.put(z, hordeId);
    }

    private PopulationBridge() {}

    static long key(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    static void onConfigReloaded(Core c) {
        if (c == null || !c.takesOverPopulation() || !Spawner.ready()) return;
        try {
            for (String k : NATIVE_ZEROED) Spawner.configFloat(k, 0f);
            Log.info("native off-screen spawning switched off (the mod spawns the population)");
        } catch (Throwable t) {
            Log.errorOnce("bridge:config", "could not switch off native spawning", t);
        }
    }

    static void onChunkUnloading(Core c, IsoChunk chunk) {
        if (chunk == null) return;
        markUnloaded(chunk.wx, chunk.wy);
        if (c == null || !c.ownsPopulation()) return;
        List<IsoZombie> leaving = new ArrayList<>();
        for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                    if (sq == null) continue;
                    for (IsoMovingObject mo : sq.getMovingObjects()) {
                        if (mo instanceof IsoZombie zombie && !zombie.isReanimatedPlayer() && !zombie.isDead()) leaving.add(zombie);
                    }
                }
            }
        }
        if (leaving.isEmpty()) return;
        synchronized (c) {
            ZombiePopulationManager.saveLock.lock();
            try {
                for (IsoZombie zombie : leaving) take(c, zombie, unloadTarget(zombie));
            } finally {
                ZombiePopulationManager.saveLock.unlock();
            }
        }
    }

    /** Returns true when the mod took the zombie (the game's own hand-over is skipped). */
    static boolean onZombieWalkedOff(Core c, IsoZombie zombie) {
        if (c == null || zombie == null || zombie.isReanimatedPlayer() || !c.ownsPopulation()) return false;
        synchronized (c) {
            ZombiePopulationManager.saveLock.lock();
            try {
                take(c, zombie, true);
            } finally {
                ZombiePopulationManager.saveLock.unlock();
            }
        }
        return true;
    }

    static void onPopulationChunkLoaded(Core c, IsoChunk chunk) {
        if (chunk == null) return;
        markLoaded(chunk.wx, chunk.wy);
        if (c != null && c.ownsPopulation()) arrive(c, chunk.wx, chunk.wy);
    }

    static boolean onRoomSpotted(Core c) {
        return c != null && c.skipRoomSpawns();
    }

    /** Arrivals for every chunk loaded right now (after the core starts or the seeding finishes). */
    /** Held zombies walked into these chunks: make them real where the chunk is (still) loaded. */
    static void spawnChunks(Core c, long[] keys) {
        for (long k : keys) if (loaded.contains(k)) walkedIn += arrive(c, (int) (k >> 32), (int) k);
    }

    /** Held zombies that walked into the loaded area and were made real (for Hordes_stats). */
    static volatile int walkedIn;

    /**
     * Every loaded chunk's held zombies made real, after laying out the hordes touching the loaded area. One pass over
     * the hordes (performance pass: laying out per chunk walked every horde once for each of ~360 loaded chunks).
     */
    static void spawnLoadedChunks(Core c) {
        synchronized (c) {
            java.util.Set<Long> chunks = new java.util.HashSet<>(c.layOutHordesInLoaded(Spawner::floorAt));
            for (long k : loaded) {
                if (chunks.contains(k)) continue;
                if (!c.store().inChunk((int) (k >> 32), (int) k).isEmpty()) chunks.add(k);
            }
            for (long k : chunks) if (loaded.contains(k)) spawnChunk(c, (int) (k >> 32), (int) k);
        }
    }

    private static int arrive(Core c, int chunkX, int chunkY) {
        synchronized (c) {
            int n = 0;
            // Hordes touching this chunk are laid out first; members that land in other loaded chunks spawn there too.
            for (long k : c.layOutHordesTouching(chunkX, chunkY, Spawner::floorAt)) {
                int x = (int) (k >> 32), y = (int) k;
                if ((x != chunkX || y != chunkY) && loaded.contains(k)) n += spawnChunk(c, x, y);
            }
            return n + spawnChunk(c, chunkX, chunkY);
        }
    }

    /** Makes the chunk's held records real (caller holds the core's lock). */
    private static int spawnChunk(Core c, int chunkX, int chunkY) {
        List<ZombieRecord> spawn = c.takeover().arrivals(chunkX, chunkY, PopulationBridge::live, Spawner::floorAt);
        int n = 0;
        for (ZombieRecord r : spawn) {
            try {
                if (Spawner.spawn(r) == Spawner.Result.REFUSED) c.takeover().refused(r);
                else n++;
            } catch (Throwable t) {
                c.takeover().failed(r);
                Log.errorOnce("bridge:spawn:" + t.getClass().getName(), "spawning a held zombie failed (kept held)", t);
            }
        }
        return n;
    }

    private static boolean live(int persistentId) {
        try {
            return Spawner.isLive(persistentId);
        } catch (Throwable t) {
            Log.errorOnce("bridge:live", "could not read the live zombie registry", t);
            return false;
        }
    }

    /** The game's own rule (removeChunkFromWorld): only a zombie walking outdoors on the ground keeps its target. */
    private static boolean unloadTarget(IsoZombie z) {
        IsoGridSquare sq = z.getCurrentSquare();
        if (z.getZ() >= 1f || (sq != null && sq.getRoom() != null)) return false;
        return z.getCurrentState() == WalkTowardState.instance() || z.getCurrentState() == PathFindState.instance();
    }

    private static void take(Core c, IsoZombie z, boolean withTarget) {
        byte[] modData = null;
        try {
            modData = ModDataCodec.save(z);
        } catch (Throwable t) {
            Log.errorOnce("bridge:moddata", "could not save a zombie's mod data (zombie kept without it)", t);
        }
        truehordes.real.Pilot p = Hooks.PILOTS.existing(z);
        Long horde = spawnedFromHorde.remove(z);
        long hordeId = p != null ? p.hordeId : horde == null ? 0 : horde;
        double now = Pilots.nowHours();
        c.takeover().taken(new ZombieSnapshot(z.getX(), z.getY(), z.getZ(), (byte) z.getForwardIsoDirection().ordinal(),
            z.getPersistentOutfitID(), ZombieStateFlags.intFromZombie(z), z.persistentId,
            withTarget ? z.getPathTargetX() : ZombieRecord.NO_TARGET, withTarget ? z.getPathTargetY() : ZombieRecord.NO_TARGET,
            modData), hordeId, r -> { if (p != null) Pilots.carry(p, r, now); });
        z.removeFromWorld();
        z.removeFromSquare();
    }

    /** Tile (x, y) lies in a loaded chunk. */
    static boolean loadedAt(float x, float y) {
        return loaded.contains(key(Math.floorDiv((int) Math.floor(x), 8), Math.floorDiv((int) Math.floor(y), 8)));
    }

    /**
     * A piloted zombie reached the loaded area's edge heading out: the mod takes it (the game cannot path a zombie into
     * unloaded ground, so it never walked off by itself). It carries on off screen with its heading and horde.
     */
    static boolean walkOff(Core c, IsoZombie z, float outX, float outY) {
        if (c == null || z == null || z.isReanimatedPlayer() || !c.ownsPopulation()) return false;
        try {
            synchronized (c) {
                // Held just past the edge (inside it, the simulation would hand it straight back).
                z.setX(outX);
                z.setY(outY);
                ZombiePopulationManager.saveLock.lock();
                try {
                    take(c, z, false);
                } finally {
                    ZombiePopulationManager.saveLock.unlock();
                }
            }
            return true;
        } catch (Throwable t) {
            Log.errorOnce("bridge:walkoff:" + t.getClass().getName(), "taking a zombie off the loaded edge failed", t);
            return false;
        }
    }

    private static void markLoaded(int cx, int cy) {
        if (loaded.add(key(cx, cy))) loadedPerCell.merge(key(Math.floorDiv(cx, 32), Math.floorDiv(cy, 32)), 1, Integer::sum);
    }

    private static void markUnloaded(int cx, int cy) {
        if (loaded.remove(key(cx, cy))) {
            loadedPerCell.computeIfPresent(key(Math.floorDiv(cx, 32), Math.floorDiv(cy, 32)), (k, v) -> v <= 1 ? null : v - 1);
        }
    }

    static long[] loadedChunks() {
        long[] out = new long[loaded.size()];
        int i = 0;
        for (long k : loaded) out[i++] = k;
        return out;
    }

    static PlayerNearby playerNearby() {
        return new PlayerNearby() {
            @Override public boolean nearCell(int cellX, int cellY) { return loadedPerCell.containsKey(key(cellX, cellY)); }

            @Override public boolean nearChunk(int chunkX, int chunkY) { return loaded.contains(key(chunkX, chunkY)); }

        };
    }

    static Map<Long, Integer> realZombiesPerCell() {
        Map<Long, Integer> out = new HashMap<>();
        for (IsoZombie z : IsoWorld.instance.currentCell.getZombieList()) {
            if (z == null || z.isDead()) continue;
            out.merge(key(Math.floorDiv((int) z.getX(), 256), Math.floorDiv((int) z.getY(), 256)), 1, Integer::sum);
        }
        return out;
    }

    /** World unloaded. */
    static void clearLoaded() {
        loaded.clear();
        loadedPerCell.clear();
    }
}
