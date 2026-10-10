package truehordes;

import me.zed_0xff.zombie_buddy.Patch;

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
}
