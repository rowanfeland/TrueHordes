package truehordes;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoZombie;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;

/** ZombieBuddy patches. Must stay directly in package truehordes (ZombieBuddy does not scan sub-packages). */
public final class Patches {
    private Patches() {}

    /** Every frame (single player) or server frame, after the vanilla population update. */
    @Patch(className = HookTargets.POPMAN, methodName = HookTargets.TICK)
    public static class Tick {
        @Patch.OnExit
        public static void exit() { Hooks.onTick(); }
    }

    /** Called from the single-player and the server world save, where the vanilla population also saves. */
    @Patch(className = HookTargets.MCD, methodName = HookTargets.SAVE)
    public static class Save {
        @Patch.OnExit
        public static void exit() { Hooks.onSave(); }
    }

    /** World unloading (exit to menu, quit, server shutdown). */
    @Patch(className = HookTargets.POPMAN, methodName = HookTargets.STOP)
    public static class Stop {
        @Patch.OnEnter
        public static void enter() { Hooks.onStop(); }
    }

    /** Single-player hot-save (ChunkSaveWorker gathers game time on the main thread between full saves). */
    @Patch(className = HookTargets.GAMETIME, methodName = HookTargets.HOT_SAVE_SP)
    public static class HotSaveSinglePlayer {
        @Patch.OnExit
        public static void exit() { Hooks.onHotSave(); }
    }

    /** Dedicated server: periodic game-time save from the main loop (ServerMap TIME_TICK). */
    @Patch(className = HookTargets.SERVER_CHUNK_LOADER, methodName = HookTargets.HOT_SAVE_SERVER)
    public static class HotSaveServer {
        @Patch.OnExit
        public static void exit() { Hooks.onHotSave(); }
    }

    /** An area loaded around a player; the game has just computed its collision bits. */
    @Patch(className = HookTargets.MCD, methodName = HookTargets.CHUNK_LOADED)
    public static class ChunkLoaded {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoChunk chunk) { Hooks.onChunkLoaded(chunk); }
    }

    /** One tile changed (building, breaking, burning). */
    @Patch(className = HookTargets.MCD, methodName = HookTargets.SQUARE_CHANGED)
    public static class SquareChanged {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGridSquare square) { Hooks.onSquareChanged(square); }
    }

    /** The game sent its population settings to the native spawner: send zeros instead (the mod spawns). */
    @Patch(className = HookTargets.POPMAN, methodName = HookTargets.CONFIG_RELOADED)
    public static class ConfigReloaded {
        @Patch.OnExit
        public static void exit() { Hooks.onConfigReloaded(); }
    }

    /** An area is unloading: the mod takes its zombies before the game hands them to the native population. */
    @Patch(className = HookTargets.POPMAN, methodName = HookTargets.CHUNK_UNLOADING)
    public static class ChunkUnloading {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoChunk chunk) { Hooks.onChunkUnloading(chunk); }
    }

    /** A zombie walked off the loaded area: the mod takes it instead of the native population (true = skip). */
    @Patch(className = HookTargets.POPMAN, methodName = HookTargets.WALKED_OFF)
    public static class WalkedOff {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoZombie zombie) { return Hooks.onZombieWalkedOff(zombie); }
    }

    /** An area loaded: the mod's zombies held there come back real. */
    @Patch(className = HookTargets.POPMAN, methodName = HookTargets.POP_CHUNK_LOADED)
    public static class PopulationChunkLoaded {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoChunk chunk) { Hooks.onPopulationChunkLoaded(chunk); }
    }

    /** A room is seen for the first time: skip the game's zombie spawn when the mod seeded the buildings (true = skip). */
    @Patch(className = HookTargets.VZM, methodName = HookTargets.ROOM_SPOTTED)
    public static class RoomSpotted {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoRoom room) { return Hooks.onRoomSpotted(room); }
    }

    /** Vanilla rally groups: skipped while the mod runs the population, so groups form only through its rules. */
    @Patch(className = HookTargets.GROUP_MANAGER, methodName = HookTargets.RALLY)
    public static class RallyOff {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return Hooks.rallyOff(); }
    }

    /** Every world sound (every addSound overload; nested ones are taken once): the off-screen model hears it too. */
    @Patch(className = HookTargets.WSM, methodName = HookTargets.ADD_SOUND)
    public static class WorldSound {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = true) Object sound) { Hooks.onWorldSound(sound); }
    }

    /** A real zombie's current sound strength: energy falloff with one remembered roll (vanilla's on any error). */
    @Patch(className = HookTargets.WSM, methodName = HookTargets.SOUND_ATTRACT)
    public static class SoundAttract {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object sound, @Patch.Argument(1) Object zombie,
                                @Patch.Return(readOnly = false) float attract) {
            attract = Hooks.soundAttract(sound, zombie, attract);
        }
    }

    /** The biggest sound a real zombie hears where it stands: rewritten into vanilla's result object. */
    @Patch(className = HookTargets.WSM, methodName = HookTargets.BIGGEST_SOUND)
    public static class BiggestSound {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int x, @Patch.Argument(1) int y, @Patch.Argument(2) int z,
                                @Patch.Argument(3) boolean ignoreBySameType, @Patch.Argument(4) Object zombie,
                                @Patch.Return(readOnly = true) Object result) {
            Hooks.biggestSound(x, y, z, ignoreBySameType, zombie, result);
        }
    }
}
