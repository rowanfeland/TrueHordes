package truehordes;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;

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
}
