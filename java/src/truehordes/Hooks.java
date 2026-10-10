package truehordes;

import truehordes.core.Core;
import truehordes.util.Log;

/** The only methods patch advice calls. Every call is wrapped so nothing throws into the game. */
public final class Hooks {
    private static volatile Core core;

    private Hooks() {}

    public static Core core() { return core; }

    static void install(Core c) { core = c; }

    public static void onTick() {
        Core c = core;
        if (c == null) return;
        try {
            c.tick();
        } catch (Throwable t) {
            Log.errorOnce("hook:tick:" + t.getClass().getName(), "update failed", t);
        }
    }

    public static void onSave() {
        Core c = core;
        if (c == null) return;
        try {
            c.save();
        } catch (Throwable t) {
            Log.errorOnce("hook:save:" + t.getClass().getName(), "save failed", t);
        }
    }

    public static void onHotSave() {
        Core c = core;
        if (c == null) return;
        try {
            c.hotSave();
        } catch (Throwable t) {
            Log.errorOnce("hook:hotsave:" + t.getClass().getName(), "hot save failed", t);
        }
    }

    public static void onStop() {
        Core c = core;
        if (c == null) return;
        try {
            c.stop();
        } catch (Throwable t) {
            Log.errorOnce("hook:stop:" + t.getClass().getName(), "stop failed", t);
        }
    }
}
