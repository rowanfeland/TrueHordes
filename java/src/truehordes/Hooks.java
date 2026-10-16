package truehordes;

import truehordes.core.Core;
import truehordes.util.Log;
import truehordes.world.World;
import zombie.iso.IsoChunk;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;

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
        if (c.soundSink() == null || c.state() != Core.State.RUNNING) return;
        try {
            if (!eventsStarted) {
                eventsStarted = true;
                EVENTS.readSettings();
                WEATHER.readSettings();
            }
            long now = System.currentTimeMillis();
            WEATHER.tick(c, now);
            EVENTS.tick(c);
        } catch (Throwable t) {
            Log.errorOnce("hook:events:" + t.getClass().getName(), "meta events / weather / lights failed", t);
        }
        try {
            if (!pilotsStarted) {
                pilotsStarted = true;
                PILOTS.readSettings();
                BREAK_IN.readSettings();
            }
            long now = System.currentTimeMillis();
            PILOTS.tick(c, now);
            PopulationBridge.scanSeen(c, now);
        } catch (Throwable t) {
            Log.errorOnce("hook:pilots:" + t.getClass().getName(), "steering real zombies failed", t);
        }
    }

    /** Before a zombie's thump (sub-project 6 crowd break-in). */
    public static void thumpEnter(Object target, Object thumper, int count) {
        try {
            BREAK_IN.enter(target, thumper, count);
        } catch (Throwable t) {
            Log.errorOnce("hook:thump:" + t.getClass().getName(), "crowd break-in failed (vanilla kept)", t);
        }
    }

    /** After a zombie's thump. */
    public static void thumpExit(Object target, Object thumper) {
        try {
            BREAK_IN.exit(target, thumper);
        } catch (Throwable t) {
            Log.errorOnce("hook:thumpexit:" + t.getClass().getName(), "crowd break-in failed", t);
        }
    }

    /** Real zombies steered by the simulation's rules (sub-project 6). */
    static final Pilots PILOTS = new Pilots();
    static final BreakInHook BREAK_IN = new BreakInHook();
    private static boolean pilotsStarted;

    /** Meta events, lights (sub-project 5b) and the weather the simulation hears through. */
    static final EventDirector EVENTS = new EventDirector();
    static final WeatherBridge WEATHER = new WeatherBridge();
    private static boolean eventsStarted;

    /** The mod runs meta events now: vanilla's gunshot schedule and random dogs and screams are skipped. */
    static boolean ownsMetaEvents() {
        Core c = core;
        return c != null && c.soundSink() != null && c.state() == Core.State.RUNNING && EVENTS.metaOn;
    }

    /** Vanilla's gunshot meta event: skipped (true) while the mod runs meta events, else labelled "meta event". */
    public static boolean metaGun() {
        try {
            if (ownsMetaEvents()) return true;
        } catch (Throwable t) {
            Log.errorOnce("hook:metagun:" + t.getClass().getName(), "meta gunshot hook failed (vanilla kept)", t);
        }
        SoundCapture.during = truehordes.sim.SoundEvent.META;
        return false;
    }

    /** World radius of a lightning strike's crack (Claude's call). */
    static final int LIGHTNING_RADIUS = 150;
    /** Vanilla's helicopter radius: A-Life's helicopters are heard like it. */
    static final int HELICOPTER_RADIUS = 500;

    /** A lightning strike at (x, y): a world sound that is not thunder, so zombies inside the storm hear it. */
    public static void lightning(int x, int y, boolean strike) {
        if (!strike) return;
        Core c = core;
        if (c == null || c.soundSink() == null || c.state() != Core.State.RUNNING || !WEATHER.on) return;
        try {
            soundAs(truehordes.sim.SoundEvent.LIGHTNING, x, y, LIGHTNING_RADIUS);
        } catch (Throwable t) {
            Log.errorOnce("hook:lightning:" + t.getClass().getName(), "lightning strike sound failed", t);
        }
    }

    /** A world sound made by the mod, labelled {@code kind} for the capture. */
    static void soundAs(int kind, int x, int y, int radius) {
        int before = SoundCapture.during;
        SoundCapture.during = kind;
        try {
            zombie.WorldSoundManager.instance.addSound(null, x, y, 0, radius, radius);
        } finally {
            SoundCapture.during = before;
        }
    }

    /** Vanilla's random ambient: skipped (true) while the mod runs meta events; owls and wolves are kept. */
    public static boolean randomAmbient() {
        try {
            if (!ownsMetaEvents()) return false;
            EVENTS.wildlife();
            return true;
        } catch (Throwable t) {
            Log.errorOnce("hook:ambient:" + t.getClass().getName(), "random ambient hook failed (vanilla kept)", t);
            return false;
        }
    }

    private static final SoundCapture SOUNDS = new SoundCapture();

    /** A world sound was added (main thread, every addSound overload's exit). Lock-free. */
    public static void onWorldSound(Object sound) {
        Core c = core;
        if (c == null || !(sound instanceof zombie.WorldSoundManager.WorldSound w)) return;
        try {
            SOUNDS.capture(w, c.tickCount(), c.soundSink());
        } catch (Throwable t) {
            Log.errorOnce("hook:sound:" + t.getClass().getName(), "sound capture failed", t);
        }
    }

    private static final RealZombieHearing REAL = new RealZombieHearing();

    /** Labels sounds made while vanilla's meta-event or thunder code runs (0 = none). */
    public static void soundsDuring(int kind) { SoundCapture.during = kind; }

    /** Real zombie, current sound: our score, or vanilla's when the mod is not running or on any error. */
    public static float soundAttract(Object sound, Object zombie, float vanilla) {
        Core c = core;
        if (c == null || c.soundSink() == null || !(zombie instanceof IsoZombie z)) return vanilla;
        try {
            return REAL.attract((zombie.WorldSoundManager.WorldSound) sound, z, c.soundSink().settings(), SOUNDS);
        } catch (Throwable t) {
            Log.errorOnce("hook:attract:" + t.getClass().getName(), "real-zombie hearing failed (vanilla kept)", t);
            return vanilla;
        }
    }

    /** Real zombie, biggest sound where it stands: our pick written into vanilla's result (left as is on error). */
    public static void biggestSound(int x, int y, int zl, boolean ignoreBySameType, Object zombie, Object result) {
        Core c = core;
        if (c == null || c.soundSink() == null || !(result instanceof zombie.WorldSoundManager.ResultBiggestSound out)) return;
        try {
            REAL.biggest(x, y, zl, ignoreBySameType, zombie instanceof IsoZombie z ? z : null, out,
                c.soundSink().settings(), SOUNDS);
        } catch (Throwable t) {
            Log.errorOnce("hook:biggest:" + t.getClass().getName(), "real-zombie hearing failed (vanilla kept)", t);
        }
    }

    public static void onSave() {
        Core c = core;
        if (c == null) return;
        try {
            c.save();
            if (c.state() == Core.State.RUNNING) EVENTS.save(c.stateFile());
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

    public static void onConfigReloaded() {
        try {
            GameEnvImpl.noteWorldLoading();
            PopulationBridge.onConfigReloaded(core);
        } catch (Throwable t) {
            Log.errorOnce("hook:config:" + t.getClass().getName(), "population config hook failed", t);
        }
    }

    public static void onChunkUnloading(IsoChunk chunk) {
        try {
            PopulationBridge.onChunkUnloading(core, chunk);
        } catch (Throwable t) {
            Log.errorOnce("hook:unload:" + t.getClass().getName(), "taking zombies from an unloading area failed", t);
        }
    }

    /** True when the mod took the zombie and the game's own hand-over must be skipped. */
    public static boolean onZombieWalkedOff(IsoZombie zombie) {
        try {
            return PopulationBridge.onZombieWalkedOff(core, zombie);
        } catch (Throwable t) {
            Log.errorOnce("hook:walkoff:" + t.getClass().getName(), "taking a zombie that walked off failed", t);
            return false;
        }
    }

    public static void onPopulationChunkLoaded(IsoChunk chunk) {
        try {
            PopulationBridge.onPopulationChunkLoaded(core, chunk);
        } catch (Throwable t) {
            Log.errorOnce("hook:arrive:" + t.getClass().getName(), "bringing held zombies into a loaded area failed", t);
        }
    }

    /** True when the game's room zombie spawn must be skipped (the mod seeded the buildings). */
    public static boolean onRoomSpotted(IsoRoom room) {
        try {
            return PopulationBridge.onRoomSpotted(core);
        } catch (Throwable t) {
            Log.errorOnce("hook:room:" + t.getClass().getName(), "room spawn hook failed", t);
            return false;
        }
    }

    /** True = skip the game's rally-group update (the mod owns hordes). */
    public static boolean rallyOff() {
        try {
            Core c = core;
            return c != null && c.rallyOff();
        } catch (Throwable t) {
            Log.errorOnce("hook:rally:" + t.getClass().getName(), "rally hook failed", t);
            return false;
        }
    }

    public static void onStop() {
        PopulationBridge.clearLoaded();
        try {
            EVENTS.stop();
            WEATHER.clear();
            eventsStarted = false;
            PILOTS.clear();
            pilotsStarted = false;
        } catch (Throwable t) {
            Log.errorOnce("hook:stopevents:" + t.getClass().getName(), "clearing meta events failed", t);
        }
        Core c = core;
        if (c == null) return;
        try {
            c.stop();
        } catch (Throwable t) {
            Log.errorOnce("hook:stop:" + t.getClass().getName(), "stop failed", t);
        }
    }
}
