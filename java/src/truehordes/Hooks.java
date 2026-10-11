package truehordes;

import truehordes.core.Core;
import truehordes.util.Log;
import truehordes.world.World;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;

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

    public static void onChunkLoaded(IsoChunk chunk) {
        Core c = core;
        if (c == null || chunk == null || !LiveBits.available()) return;
        try {
            World w = c.world();
            if (w.state() == World.State.OFF) return;
            w.liveChunk(chunk.wx, chunk.wy, LiveBits.chunkBits());
        } catch (Throwable t) {
            Log.errorOnce("hook:chunk:" + t.getClass().getName(), "live map update (area loaded) failed", t);
        }
    }

    public static void onSquareChanged(IsoGridSquare square) {
        Core c = core;
        if (c == null || square == null || square.z != 0 || !LiveBits.available()) return;
        try {
            World w = c.world();
            if (w.state() == World.State.OFF) return;
            w.liveTile(square.x, square.y, LiveBits.squareBits(square));
        } catch (Throwable t) {
            Log.errorOnce("hook:square:" + t.getClass().getName(), "live map update (tile changed) failed", t);
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
