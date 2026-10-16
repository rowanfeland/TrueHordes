package truehordes;

import truehordes.sim.SoundEvent;
import truehordes.sim.Simulation;
import zombie.WorldSoundManager;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.objects.IsoClothingDryer;
import zombie.iso.objects.IsoClothingWasher;
import zombie.iso.objects.IsoCombinationWasherDryer;
import zombie.iso.objects.IsoGenerator;
import zombie.iso.objects.IsoJukebox;
import zombie.iso.objects.IsoRadio;
import zombie.iso.objects.IsoStove;
import zombie.iso.objects.IsoTelevision;
import zombie.vehicles.BaseVehicle;

/**
 * Records every world sound for the off-screen model (sub-project 5a spec section 3): called on the game's main thread
 * from the exit of every {@code WorldSoundManager.addSound} overload. Nested overloads hand back the same sound object
 * on the way out, so a sound is taken only the first time it is seen on a tick (the game pools sound objects, so the
 * same object on a later tick is a new sound). Never takes the core lock: the simulation's queue is lock-free.
 */
public final class SoundCapture {
    private Object last;
    private long lastTick = -1;
    /** Ids of live sound objects (the game pools them), for real zombies' remembered rolls; swept now and then. */
    private final java.util.IdentityHashMap<Object, Long> ids = new java.util.IdentityHashMap<>();
    private long nextId = 1;
    private long lastSweepTick;

    /** The sound's id: one per sound object while it lives; a repeating sound has one id per source and spot. */
    long idOf(WorldSoundManager.WorldSound w) {
        Long id = ids.get(w);
        return id != null ? id : System.identityHashCode(w); // added before the patch was in: still stable
    }

    private long assign(WorldSoundManager.WorldSound w, long key) {
        long id = w.repeating ? (key | (1L << 62)) : nextId++;
        ids.put(w, id);
        return id;
    }

    /** Drops ids of sounds the game has released (every 300 ticks). */
    void sweep(long tick) {
        if (tick - lastSweepTick < 300 || ids.isEmpty()) return;
        lastSweepTick = tick;
        java.util.Set<Object> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        live.addAll(WorldSoundManager.instance.soundList);
        ids.keySet().removeIf(o -> !live.contains(o));
    }

    /** True the first time this sound object is seen on tick {@code tick}. */
    public boolean firstTime(Object sound, long tick) {
        if (sound == last && tick == lastTick) return false;
        last = sound;
        lastTick = tick;
        return true;
    }

    /** Set while vanilla's meta-event or thunder code runs (main thread), so its sounds are labelled. */
    static int during;

    /** The viewer's kind: a meta event or thunder by where it was made, then the helicopter, then the source. */
    public static int kindOf(int during, boolean atHelicopter, boolean gunInHand, boolean vehicle, boolean appliance,
                             boolean repeatingNoSource) {
        if (during != 0) return during;
        if (atHelicopter) return SoundEvent.HELICOPTER;
        return kindOf(gunInHand, vehicle, appliance, repeatingNoSource);
    }

    /** The viewer's kind for a sound, from what made it. */
    public static int kindOf(boolean gunInHand, boolean vehicle, boolean appliance, boolean repeatingNoSource) {
        if (gunInHand) return SoundEvent.GUNFIRE;
        if (vehicle) return SoundEvent.VEHICLE;
        if (appliance) return SoundEvent.APPLIANCE;
        if (repeatingNoSource) return SoundEvent.ALARM;
        return SoundEvent.OTHER;
    }

    /** Takes one sound the game just added (main thread). */
    void capture(WorldSoundManager.WorldSound w, long tick, Simulation sim) {
        if (w == null || !firstTime(w, tick)) return;
        Object src = w.source;
        // Re-sends of a repeating sound fold by source; with no source object, by its spot.
        long key = src != null ? System.identityHashCode(src) : (((long) w.x) << 32) ^ (w.y & 0xFFFFFFFFL) ^ 0x5EEDL;
        assign(w, key);
        sweep(tick);
        if (sim == null || w.sourceIsZombie || !w.stressZombies || w.radius <= 0) return;
        boolean gun = false;
        if (src instanceof IsoGameCharacter c) {
            InventoryItem item = c.getPrimaryHandItem();
            gun = item instanceof HandWeapon hw && hw.isRanged();
        }
        boolean vehicle = src instanceof BaseVehicle;
        boolean appliance = src instanceof IsoGenerator || src instanceof IsoRadio || src instanceof IsoTelevision
            || src instanceof IsoClothingWasher || src instanceof IsoClothingDryer || src instanceof IsoCombinationWasherDryer
            || src instanceof IsoJukebox || src instanceof IsoStove;
        zombie.iso.Helicopter heli = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.helicopter;
        boolean atHeli = src == null && heli != null && heli.isActive() && (int) heli.x == w.x && (int) heli.y == w.y;
        int kind = kindOf(during, atHeli, gun, vehicle, appliance, w.repeating && src == null);
        sim.offerSound(w.x + 0.5f, w.y + 0.5f, w.radius, w.repeating, key, kind);
    }
}
