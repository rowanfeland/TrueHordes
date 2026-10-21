package truehordes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import truehordes.util.Log;
import zombie.WorldSoundManager;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.weather.ClimateManager;
import zombie.network.GameServer;

/**
 * Doors swinging shut behind zombies (sub-project 8). A zombie that walks in from outside through an open door (one a
 * player left open too) may have it swing closed behind it a moment later, more often in wind. Zombies cannot open
 * doors, so it is shut in until it breaks out or someone lets it out. Main thread where the population is owned (the
 * server's in multiplayer, which sends the door's state to clients).
 */
final class DoorSwing {
    static final long CHECK_MS = 250;
    /** A door waits this long for its doorway to clear before it gives up. */
    static final long WAIT_MS = 6000;

    /** Chance per zombie walking in (0..1), at no wind; full wind triples it. */
    double chance = 0.10;
    private long lastMs;
    private final WeakHashMap<IsoZombie, IsoGridSquare> last = new WeakHashMap<>();

    private record Due(IsoObject door, IsoGridSquare out, IsoGridSquare in, long atMs) {}

    private final List<Due> due = new ArrayList<>();

    void readSettings() {
        chance = Math.max(0, Math.min(1, GameEnvImpl.sandboxDouble("TrueHordes.DoorSwingChance", 10) / 100));
    }

    void tick(long now) {
        if (chance <= 0 || now - lastMs < CHECK_MS || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;
        lastMs = now;
        closeDue(now);
        double p = Math.min(1, chance * (1 + 2 * ClimateManager.getInstance().getWindIntensity()));
        for (IsoZombie z : IsoWorld.instance.currentCell.getZombieList()) {
            IsoGridSquare sq = z.getCurrentSquare();
            IsoGridSquare was = last.put(z, sq);
            if (sq == null || was == null || was == sq || was.getRoom() != null || sq.getRoom() == null) continue;
            IsoObject door = sq.getDoorTo(was);
            if (!open(door) || ThreadLocalRandom.current().nextDouble() >= p) continue;
            due.add(new Due(door, was, sq, now + 800 + ThreadLocalRandom.current().nextInt(2000)));
        }
    }

    private static boolean open(IsoObject o) {
        if (o instanceof IsoDoor d) return d.IsOpen() && !d.isBarricaded() && !d.isDestroyed();
        if (o instanceof IsoThumpable t) return t.isDoor() && t.IsOpen() && !t.isBarricaded() && !t.isDestroyed();
        return false;
    }

    private void closeDue(long now) {
        Iterator<Due> it = due.iterator();
        while (it.hasNext()) {
            Due d = it.next();
            if (now < d.atMs()) continue;
            if (!open(d.door())) {
                it.remove();
                continue;
            }
            // Someone still in the doorway: wait for it to clear.
            if (!d.out().getMovingObjects().isEmpty() || !d.in().getMovingObjects().isEmpty()) {
                if (now - d.atMs() > WAIT_MS) it.remove();
                continue;
            }
            it.remove();
            try {
                shut(d.door());
            } catch (Throwable t) {
                Log.errorOnce("doorswing:" + t.getClass().getName(), "a door swinging shut failed", t);
            }
        }
    }

    private static void shut(IsoObject door) {
        if (door instanceof IsoDoor d) d.ToggleDoorSilent();
        else ((IsoThumpable) door).ToggleDoorSilent();
        door.sync(0);
        IsoGridSquare sq = door.getSquare();
        // A slam: heard by zombies near it, and by the player in single player.
        WorldSoundManager.instance.addSound(null, sq.getX(), sq.getY(), sq.getZ(), 12, 12);
        if (!GameServer.server) IsoWorld.instance.getFreeEmitter(sq.getX() + 0.5f, sq.getY() + 0.5f, sq.getZ()).playSound("DoorWoodMediumClose");
        Log.debug("core", "door swung shut behind a zombie at " + sq.getX() + "," + sq.getY());
    }
}
