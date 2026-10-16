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

    /** True the first time this sound object is seen on tick {@code tick}. */
    public boolean firstTime(Object sound, long tick) {
        if (sound == last && tick == lastTick) return false;
        last = sound;
        lastTick = tick;
        return true;
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
        if (w == null || sim == null || !firstTime(w, tick)) return;
        if (w.sourceIsZombie || !w.stressZombies || w.radius <= 0) return;
        Object src = w.source;
        boolean gun = false;
        if (src instanceof IsoGameCharacter c) {
            InventoryItem item = c.getPrimaryHandItem();
            gun = item instanceof HandWeapon hw && hw.isRanged();
        }
        boolean vehicle = src instanceof BaseVehicle;
        boolean appliance = src instanceof IsoGenerator || src instanceof IsoRadio || src instanceof IsoTelevision
            || src instanceof IsoClothingWasher || src instanceof IsoClothingDryer || src instanceof IsoCombinationWasherDryer
            || src instanceof IsoJukebox || src instanceof IsoStove;
        int kind = kindOf(gun, vehicle, appliance, w.repeating && src == null);
        // Re-sends of a repeating sound fold by source; with no source object, by its spot.
        long key = src != null ? System.identityHashCode(src) : (((long) w.x) << 32) ^ (w.y & 0xFFFFFFFFL) ^ 0x5EEDL;
        sim.offerSound(w.x + 0.5f, w.y + 0.5f, w.radius, w.repeating, key, kind);
    }
}
