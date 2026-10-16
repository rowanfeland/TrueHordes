package truehordes;

import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import truehordes.sim.SimSettings;
import truehordes.sim.SoundModel;
import truehordes.sound.RealHearing;
import zombie.WorldSoundManager;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * Real zombies near players hear by energy falloff (sub-project 5a spec section 5). Replaces the scores vanilla's
 * {@code getSoundAttract} and {@code getBiggestSoundZomb} return, keeping vanilla's filters; everything around them
 * (reaction delay, scatter, pathing) stays vanilla. Main thread only. Each zombie rolls once per sound and remembers.
 */
final class RealZombieHearing {
    private final WeakHashMap<IsoZombie, RealHearing.Memory> memory = new WeakHashMap<>();
    private SimSettings settings;
    private RealHearing hearing;

    private RealHearing hearing(SimSettings s) {
        if (s != settings) {
            settings = s;
            hearing = new RealHearing(new SoundModel(s));
        }
        return hearing;
    }

    /** Our score for one sound at one real zombie, or 0 if it does not hear it (vanilla filters kept). */
    float score(WorldSoundManager.WorldSound sound, IsoZombie zom, float zx, float zy, float zz, boolean ignoreBySameType,
                SimSettings s, SoundCapture ids) {
        if (sound == null || !sound.stressZombies || sound.radius == 0) return 0;
        if (ignoreBySameType && sound.sourceIsZombie) return 0;
        float distSq = IsoUtils.DistanceToSquared(zx, zy, zz * 3, sound.x, sound.y, sound.z * 3);
        if (distSq < sound.zombieIgnoreDist * sound.zombieIgnoreDist && (int) zz == sound.z) return 0;
        float radius = sound.radius * WorldSoundManager.instance.getHearingMultiplier(zom);
        if (distSq > radius * radius) return 0;
        IsoCell cell = IsoWorld.instance.currentCell;
        IsoGridSquare sq = cell.getGridSquare(sound.x, sound.y, sound.z);
        IsoGridSquare sq2 = cell.getGridSquare((double) zx, (double) zy, (double) zz);
        boolean otherRoom = sq != null && sq2 != null && sq.getRoom() != sq2.getRoom();
        boolean oneOutside = otherRoom && (sq.getRoom() == null || sq2.getRoom() == null);
        float level = SoundModel.levelAt(SoundModel.dbFromRadius(radius), (float) Math.sqrt(distSq))
            - RealHearing.roomLossDb(otherRoom, oneOutside);
        if (level < SoundModel.THRESHOLD_DB) return 0;
        long id = ids.idOf(sound);
        RealHearing.Memory m = memory.computeIfAbsent(zom, z -> new RealHearing.Memory());
        Boolean heard = m.get(id);
        if (heard == null) {
            heard = hearing(s).rollHeard(level, ThreadLocalRandom.current().nextDouble());
            m.put(id, heard);
        }
        return heard ? RealHearing.score(level) : 0;
    }

    /** getSoundAttract: the zombie's current sound's strength now (vanilla also returns 0 for zombie-made sounds). */
    float attract(WorldSoundManager.WorldSound sound, IsoZombie zom, SimSettings s, SoundCapture ids) {
        if (sound == null || sound.sourceIsZombie) return 0;
        return score(sound, zom, zom.getX(), zom.getY(), zom.getZ(), false, s, ids);
    }

    /** getBiggestSoundZomb: the loudest heard sound where the zombie stands, written into vanilla's result object. */
    void biggest(int x, int y, int z, boolean ignoreBySameType, IsoZombie zom, WorldSoundManager.ResultBiggestSound out,
                 SimSettings s, SoundCapture ids) {
        List<WorldSoundManager.WorldSound> list;
        if (zom != null) {
            if (zom.getCurrentSquare() == null) return; // vanilla already returned an empty result
            list = zom.getCurrentSquare().chunk != null && !GameServer.server ? zom.getCurrentSquare().chunk.soundList
                : WorldSoundManager.instance.soundList;
        } else {
            list = WorldSoundManager.instance.soundList;
        }
        WorldSoundManager.WorldSound best = null;
        float bestScore = 0;
        for (int i = 0; i < list.size(); i++) {
            WorldSoundManager.WorldSound w = list.get(i);
            float sc = score(w, zom, x, y, z, ignoreBySameType, s, ids);
            if (sc > bestScore) {
                bestScore = sc;
                best = w;
            }
        }
        out.init(best, best == null ? -1000000f : bestScore);
    }
}
