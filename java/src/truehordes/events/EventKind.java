package truehordes.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import truehordes.sim.SoundEvent;

/**
 * One kind of meta event as data (sub-project 5b spec goal 1): the game sounds it plays (one picked at random), the
 * world radius zombies hear it by ({@link #GUN} = a gunshot, sized by Louder Gunshots), how many world sounds it makes
 * (a burst is that many shots a few tenths of a second apart, each its own roll), where it happens, how often it is
 * picked, and the sound viewer's label. Custom events later are more entries in {@link #FAR}.
 */
public record EventKind(String name, String[] sounds, int radius, int shotsMin, int shotsMax, Placement.Where where,
                        double weight, int viewKind) {
    /** Radius marker: the event is a gunshot (rifle radius, Louder Gunshots aware). */
    public static final int GUN = -1;

    /**
     * Vanilla's far meta events. The six vanilla gun names are three FMOD events (Meta/AssaultRifle, Meta/Pistol twice
     * over, Meta/Shotgun), each a random clip, so shot counts are ranges (Claude's call: a rifle burst 3-8, pistol 1-4,
     * shotgun 1-2). Gunshots are half of all far events as in vanilla's gun schedule, screams and dogs a quarter each.
     */
    public static final List<EventKind> FAR;

    static {
        List<EventKind> k = new ArrayList<>();
        k.add(new EventKind("rifle", new String[] {"MetaAssaultRifle1"}, GUN, 3, 8, Placement.Where.TOWN, 1.5 / 6,
            SoundEvent.META));
        k.add(new EventKind("pistol", new String[] {"MetaPistol1", "MetaPistol2", "MetaPistol3"}, GUN, 1, 4,
            Placement.Where.TOWN, 2.0 / 6, SoundEvent.META));
        k.add(new EventKind("shotgun", new String[] {"MetaShotgun1"}, GUN, 1, 2, Placement.Where.TOWN, 1.5 / 6,
            SoundEvent.META));
        k.add(new EventKind("lone gunshot", new String[] {"MetaPistol1", "MetaShotgun1"}, GUN, 1, 2,
            Placement.Where.ANYWHERE, 1.0 / 6, SoundEvent.META));
        k.add(new EventKind("scream", new String[] {"MetaScream"}, 60, 1, 1, Placement.Where.TOWN, 0.5,
            SoundEvent.SCREAM));
        k.add(new EventKind("dog", new String[] {"MetaDogBark"}, 80, 1, 1, Placement.Where.RURAL, 0.5,
            SoundEvent.DOG));
        FAR = Collections.unmodifiableList(k);
    }

    /** A kind picked by weight (gunshot weights sum to 1, screams and dogs 0.5 each: half are gunshots). */
    public static EventKind pick(List<EventKind> kinds, double u) {
        double total = 0;
        for (EventKind k : kinds) total += k.weight;
        double x = u * total;
        for (EventKind k : kinds) {
            x -= k.weight;
            if (x < 0) return k;
        }
        return kinds.get(kinds.size() - 1);
    }

    public boolean gun() { return radius == GUN; }

    /** Shots for this event, from a roll {@code u} in [0, 1). */
    public int shots(double u) { return shotsMin + (int) Math.floor(u * (shotsMax - shotsMin + 1)); }

    public String sound(double u) { return sounds[Math.min(sounds.length - 1, (int) Math.floor(u * sounds.length))]; }
}
