package truehordes;

import java.lang.reflect.Field;
import java.util.List;
import truehordes.core.Core;
import truehordes.real.BreakIn;
import truehordes.sim.Aftermath;
import truehordes.sim.Simulation;
import truehordes.util.Log;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.characters.IsoZombie;
import zombie.core.random.Rand;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoGenerator;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;

/**
 * Consequences while away (sub-project 8), the game's side. When a chunk loads: doors, windows, barricades and
 * player-built objects near where an off-screen horde stood are broken (or worn down) by the crowd break-in times (D15),
 * with blood and glass, and the zombies shot off screen lie there as corpses. When a chunk unloads with a running
 * generator: its lit building keeps luring off-screen zombies only until the generator's fuel would have run out.
 * Main thread (the server's in multiplayer).
 */
final class AftermathBridge {
    private AftermathBridge() {}

    private static Field windowHealth, generatorUse;

    static void onLoaded(Core c, IsoChunk chunk) {
        if (c == null || chunk == null || !c.ownsPopulation()) return;
        List<Aftermath.Siege> sieges;
        List<float[]> corpses;
        synchronized (c) {
            Simulation sim = c.simulation();
            if (sim == null) return;
            sieges = sim.aftermath().takeSieges(chunk.wx, chunk.wy);
            corpses = sim.aftermath().takeCorpses(chunk.wx, chunk.wy);
        }
        for (Aftermath.Siege s : sieges) {
            try {
                siege(chunk, s);
            } catch (Throwable t) {
                Log.errorOnce("aftermath:siege:" + t.getClass().getName(), "applying off-screen break-ins failed", t);
            }
        }
        for (float[] k : corpses) {
            try {
                corpse(chunk, k[0], k[1], (int) k[2], (int) k[3]);
            } catch (Throwable t) {
                Log.errorOnce("aftermath:corpse:" + t.getClass().getName(), "placing an off-screen corpse failed", t);
            }
        }
    }

    /** How far from where a crowd stood it reaches doors and windows. */
    static float reach(int crowd) { return (float) Math.min(10, 3 + Math.sqrt(crowd) / 2); }

    private static void siege(IsoChunk chunk, Aftermath.Siege s) throws ReflectiveOperationException {
        float r = reach(s.crowd);
        int x0 = (int) Math.floor(s.x - r), x1 = (int) Math.floor(s.x + r);
        int y0 = (int) Math.floor(s.y - r), y1 = (int) Math.floor(s.y + r);
        // Barricades first: a window or door behind one that holds is not reached.
        for (int pass = 0; pass < 2; pass++) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    if (Math.hypot(x + 0.5 - s.x, y + 0.5 - s.y) > r) continue;
                    IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, 0);
                    if (sq == null) continue;
                    IsoObject[] objs = sq.getObjects().toArray(new IsoObject[0]);
                    for (IsoObject o : objs) {
                        if ((o instanceof IsoBarricade) == (pass == 0)) press(chunk, sq, o, s);
                    }
                }
            }
        }
        // The crowd itself: blood and mess where it stood, more for a bigger crowd.
        int splats = (int) Math.min(30, 2 + Math.sqrt(s.crowd) * Math.min(1, s.hours));
        for (int i = 0; i < splats; i++) blood(s.x + Rand.Next(-1f, 1f) * r * 0.6f, s.y + Rand.Next(-1f, 1f) * r * 0.6f);
    }

    private static void press(IsoChunk chunk, IsoGridSquare sq, IsoObject o, Aftermath.Siege s)
        throws ReflectiveOperationException {
        if (o instanceof IsoBarricade b) {
            double f = s.hours / BreakIn.hours(false, s.crowd);
            b.Damage((float) Math.min(b.getHealth() + 1, f * 1000));
            if (f >= 1) broke(sq);
        } else if (o instanceof IsoWindow w) {
            if (w.isDestroyed() || w.IsOpen() || w.isBarricaded()) return;
            double f = s.hours / BreakIn.hours(true, s.crowd);
            if (f >= 1) {
                w.smashWindow(false, SandboxOptions.getInstance().lore.triggerHouseAlarm.getValue());
                w.addBrokenGlass(true);
                broke(sq);
            } else {
                if (windowHealth == null) {
                    windowHealth = IsoWindow.class.getDeclaredField("health");
                    windowHealth.setAccessible(true);
                }
                int max = (int) BreakInHook.maxHealth(w);
                windowHealth.setInt(w, Math.max(1, w.getHealth() - (int) (f * max)));
            }
        } else if (o instanceof IsoDoor d) {
            if (d.isDestroyed() || d.IsOpen() || d.isBarricaded()) return;
            double f = s.hours / BreakIn.hours(false, s.crowd);
            if (f >= 1) {
                d.destroy();
                broke(sq);
            } else {
                d.setHealth(Math.max(1, d.getHealth() - (int) (f * Math.max(1, d.getMaxHealth()))));
            }
        } else if (o instanceof IsoThumpable t && t.isThumpable() && !t.isDestroyed()) {
            if (t.isDoor() && t.IsOpen()) return;
            double f = s.hours / BreakIn.hours(false, s.crowd);
            if (f >= 1) {
                t.destroy();
                broke(sq);
            } else {
                t.setHealth(Math.max(1, t.getHealth() - (int) (f * Math.max(1, t.getMaxHealth()))));
            }
        }
    }

    /** Blood where a crowd broke something. */
    private static void broke(IsoGridSquare sq) {
        for (int i = 0; i < 3; i++) blood(sq.getX() + Rand.Next(-1.5f, 2.5f), sq.getY() + Rand.Next(-1.5f, 2.5f));
    }

    private static void blood(float x, float y) {
        IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare((int) Math.floor(x), (int) Math.floor(y), 0);
        if (sq == null || sq.getChunk() == null || !sq.isSolidFloor()) return;
        sq.getChunk().addBloodSplat(x, y, 0, Rand.Next(8));
    }

    /** A zombie shot off screen: made real where it fell (its own outfit), then a body. */
    private static void corpse(IsoChunk chunk, float x, float y, int z, int outfit) throws ReflectiveOperationException {
        IsoZombie zed = Spawner.standing(x, y, z, outfit);
        if (zed == null) return;
        new IsoDeadBody(zed, false);
        for (int i = 0; i < 2; i++) blood(x + Rand.Next(-1f, 1f), y + Rand.Next(-1f, 1f));
    }

    // ---- generators ----

    /** A chunk unloading with running generators: their lights stop luring when the fuel would have run out. */
    static void onUnloading(IsoChunk chunk) {
        if (chunk == null) return;
        try {
            for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                        if (sq == null) continue;
                        for (IsoObject o : sq.getObjects()) {
                            if (o instanceof IsoGenerator g && g.isActivated()) generator(g, sq);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.errorOnce("aftermath:generator:" + t.getClass().getName(), "reading a generator's fuel failed", t);
        }
    }

    private static void generator(IsoGenerator g, IsoGridSquare sq) throws ReflectiveOperationException {
        if (generatorUse == null) {
            generatorUse = IsoGenerator.class.getDeclaredField("totalPowerUsing");
            generatorUse.setAccessible(true);
        }
        double perHour = generatorUse.getFloat(g) * SandboxOptions.instance.generatorFuelConsumption.getValue();
        double hours = perHour <= 0 ? Double.POSITIVE_INFINITY : g.getFuel() / perHour;
        int radius = SandboxOptions.getInstance().generatorTileRange.getValue();
        Hooks.EVENTS.lights.poweredUntil(sq.getX() + 0.5f, sq.getY() + 0.5f, radius,
            GameTime.getInstance().getWorldAgeHours() + hours);
    }
}
