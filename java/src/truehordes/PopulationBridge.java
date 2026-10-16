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
        for (long k : keys) if (loaded.contains(k)) arrive(c, (int) (k >> 32), (int) k);
    }

    static void spawnLoadedChunks(Core c) {
        for (long k : new ArrayList<>(loaded)) arrive(c, (int) (k >> 32), (int) k);
    }

    private static void arrive(Core c, int chunkX, int chunkY) {
        synchronized (c) {
            // Hordes touching this chunk are laid out first; members that land in other loaded chunks spawn there too.
            for (long k : c.layOutHordesTouching(chunkX, chunkY, Spawner::floorAt)) {
                int x = (int) (k >> 32), y = (int) k;
                if ((x != chunkX || y != chunkY) && loaded.contains(k)) spawnChunk(c, x, y);
            }
            spawnChunk(c, chunkX, chunkY);
        }
    }

    /** Makes the chunk's held records real (caller holds the core's lock). */
    private static void spawnChunk(Core c, int chunkX, int chunkY) {
        List<ZombieRecord> spawn = c.takeover().arrivals(chunkX, chunkY, PopulationBridge::live, Spawner::floorAt,
            r -> realAt(r.x(), r.y(), r.z));
        for (ZombieRecord r : spawn) {
            try {
                if (Spawner.spawn(r) == Spawner.Result.REFUSED) c.takeover().refused(r);
            } catch (Throwable t) {
                c.takeover().failed(r);
                Log.errorOnce("bridge:spawn:" + t.getClass().getName(), "spawning a held zombie failed (kept held)", t);
            }
        }
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

    /**
     * A real zombie far from every player and out of sight goes back to the simulation where it stands, still walking
     * (sub-project 6 spec goal 6). Returns true when taken.
     */
    static boolean sendBack(Core c, IsoZombie z, truehordes.real.Pilot p) {
        if (c == null || z == null || z.isReanimatedPlayer() || !c.ownsPopulation()) return false;
        try {
            synchronized (c) {
                ZombiePopulationManager.saveLock.lock();
                try {
                    take(c, z, false);
                } finally {
                    ZombiePopulationManager.saveLock.unlock();
                }
            }
            return true;
        } catch (Throwable t) {
            Log.errorOnce("bridge:sendback:" + t.getClass().getName(), "sending a real zombie back failed", t);
            return false;
        }
    }

    /**
     * Where a held zombie should be real right now (main thread): in a loaded chunk and within the real radius of a
     * player, or where a local player can see it. With the radius 0, anywhere loaded.
     */
    static boolean realAt(float x, float y, float z) {
        int cx = Math.floorDiv((int) Math.floor(x), 8), cy = Math.floorDiv((int) Math.floor(y), 8);
        if (!loaded.contains(key(cx, cy))) return false;
        truehordes.real.RealZone zone = Hooks.PILOTS.zone;
        return !zone.on() || zone.near(x, y, 0.5f) || Pilots.seen(x, y, z);
    }

    private static long lastSeenScanMs;

    /**
     * Held zombies in the loaded area beyond the real radius that a player can now see become real (main thread, twice
     * a second): wanderers in loaded chunks, and hordes touching the loaded area.
     */
    static void scanSeen(Core c, long nowMs) {
        if (c == null || !c.ownsPopulation() || !Hooks.PILOTS.zone.on() || nowMs - lastSeenScanMs < 500) return;
        lastSeenScanMs = nowMs;
        List<Long> chunks = new ArrayList<>();
        synchronized (c) {
            for (long k : loaded) {
                int cx = (int) (k >> 32), cy = (int) k;
                if (Hooks.PILOTS.zone.chunkInside(cx, cy)) continue;
                for (ZombieRecord r : c.store().inChunk(cx, cy)) {
                    if (realAt(r.x(), r.y(), r.z)) {
                        chunks.add(k);
                        break;
                    }
                }
            }
            for (long k : c.layOutHordesInLoaded(Spawner::floorAt)) if (loaded.contains(k) && !chunks.contains(k)) chunks.add(k);
            for (long k : chunks) spawnChunk(c, (int) (k >> 32), (int) k);
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

            @Override public boolean realChunk(int chunkX, int chunkY) {
                truehordes.real.RealZone zone = Hooks.PILOTS.zone;
                return loaded.contains(key(chunkX, chunkY)) && (!zone.on() || zone.chunkInside(chunkX, chunkY));
            }

            @Override public boolean realAt(float x, float y) { return PopulationBridge.realAt(x, y, 0); }
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
