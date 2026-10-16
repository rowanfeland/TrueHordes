package truehordes;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.WeakHashMap;
import truehordes.core.Core;
import truehordes.real.BreakIn;
import truehordes.util.Log;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.network.GameClient;

/**
 * Crowd-scaled break-in (D15, sub-project 6 spec goal 12). Around a zombie's thump on a door, window, barricade or
 * player-built object: before it, the zombie's strength is set so that vanilla's own damage path deals the D15 damage
 * for the crowd at the object (fractions carried per object); after it, objects whose vanilla damage ignores strength
 * (a lone zombie at a door, player-built walls) are topped up to the same damage, and broken through vanilla's own break
 * path. Main thread (or the server's). Off on clients (the server applies thumps there).
 */
final class BreakInHook {
    boolean on = true;
    static final int CROWD_RADIUS = 3;
    static final long COUNT_MS = 1000;

    private final WeakHashMap<Object, double[]> carry = new WeakHashMap<>();
    private final WeakHashMap<Object, long[]> crowd = new WeakHashMap<>();
    private final ArrayDeque<int[]> frames = new ArrayDeque<>();
    private boolean inner;
    private static Field windowMax;

    void readSettings() { on = GameEnvImpl.sandboxBool("TrueHordes.CrowdBreakIn", true); }

    /** Before vanilla's Thump: returns nothing; remembers the zombie's strength and the object's health. */
    void enter(Object target, Object thumper, int count) {
        if (inner || !on || GameClient.client || !(thumper instanceof IsoZombie z) || !(target instanceof IsoObject o)) {
            frames.push(new int[] {Integer.MIN_VALUE});
            return;
        }
        try {
            Core c = Hooks.core();
            if (c == null || c.simSettings() == null) {
                frames.push(new int[] {Integer.MIN_VALUE});
                return;
            }
            int n = crowdAt(o);
            boolean window = o instanceof IsoWindow;
            double per = BreakIn.damagePerThump(maxHealth(o), n, window, c.simSettings().realSecondsPerGameMinute());
            double[] acc = carry.computeIfAbsent(o, k -> new double[1]);
            acc[0] += per * Math.max(1, count);
            int k = (int) Math.floor(acc[0]);
            acc[0] -= k;
            int strength = z.strength;
            z.strength = count > 1 ? k / count : k;
            frames.push(new int[] {strength, health(o), k});
        } catch (Throwable t) {
            frames.push(new int[] {Integer.MIN_VALUE});
            Log.errorOnce("breakin:enter:" + t.getClass().getName(), "crowd break-in failed (vanilla kept)", t);
        }
    }

    /** After vanilla's Thump: restores the strength and tops up objects vanilla damaged less than D15 asks. */
    void exit(Object target, Object thumper) {
        int[] f = frames.isEmpty() ? null : frames.pop();
        if (f == null || f[0] == Integer.MIN_VALUE || !(thumper instanceof IsoZombie z)) return;
        z.strength = f[0];
        try {
            IsoObject o = (IsoObject) target;
            if (!(o instanceof IsoDoor || o instanceof IsoThumpable) || destroyed(o)) return;
            int after = health(o), done = f[1] - after, missing = f[2] - done;
            if (missing <= 0) return;
            int left = after - missing;
            if (o instanceof IsoDoor d) d.setHealth(Math.max(0, left));
            else ((IsoThumpable) o).setHealth(Math.max(0, left));
            if (left <= 0) {
                // Vanilla's break path (sounds, debris, sync) runs on a thump with nothing more to deal.
                int s = z.strength;
                inner = true;
                z.strength = 0;
                try {
                    ((zombie.iso.objects.interfaces.Thumpable) o).Thump(z, 0);
                } finally {
                    z.strength = s;
                    inner = false;
                }
            }
        } catch (Throwable t) {
            Log.errorOnce("breakin:exit:" + t.getClass().getName(), "crowd break-in top-up failed", t);
        }
    }

    /** Zombies within CROWD_RADIUS tiles of the object, counted at most once a second per object. */
    private int crowdAt(IsoObject o) {
        long now = System.currentTimeMillis();
        long[] c = crowd.get(o);
        if (c != null && now - c[1] < COUNT_MS) return (int) c[0];
        int n = 0;
        IsoGridSquare sq = o.getSquare();
        if (sq != null) {
            for (int dx = -CROWD_RADIUS; dx <= CROWD_RADIUS; dx++) {
                for (int dy = -CROWD_RADIUS; dy <= CROWD_RADIUS; dy++) {
                    IsoGridSquare s = IsoWorld.instance.currentCell.getGridSquare(sq.getX() + dx, sq.getY() + dy, sq.getZ());
                    if (s == null) continue;
                    for (IsoMovingObject m : s.getMovingObjects()) if (m instanceof IsoZombie zz && !zz.isDead()) n++;
                }
            }
        }
        n = Math.max(1, n);
        crowd.put(o, new long[] {n, now});
        return n;
    }

    static boolean destroyed(IsoObject o) {
        if (o instanceof IsoDoor d) return d.isDestroyed();
        if (o instanceof IsoThumpable t) return t.isDestroyed();
        return false;
    }

    static int health(IsoObject o) {
        if (o instanceof IsoDoor d) return d.getHealth();
        if (o instanceof IsoWindow w) return w.getHealth();
        if (o instanceof IsoThumpable t) return t.getHealth();
        if (o instanceof IsoBarricade b) return b.getHealth();
        return 0;
    }

    static double maxHealth(IsoObject o) throws ReflectiveOperationException {
        if (o instanceof IsoDoor d) return Math.max(1, d.getMaxHealth());
        if (o instanceof IsoThumpable t) return Math.max(1, t.getMaxHealth());
        if (o instanceof IsoBarricade b) return Math.max(1, b.getMaxHealth());
        if (o instanceof IsoWindow w) {
            if (windowMax == null) {
                Field f = IsoWindow.class.getDeclaredField("maxHealth");
                f.setAccessible(true);
                windowMax = f;
            }
            return Math.max(1, windowMax.getInt(w));
        }
        return 100;
    }
}
