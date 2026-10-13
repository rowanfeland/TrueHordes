package truehordes.population;

import java.util.function.IntUnaryOperator;

/**
 * Zombies in one room, ported from VirtualZombieManager.getZombieCountForRoom and IsoMetaChunk.getLootZombieIntensity
 * (42.21), including their random rolls. The game's "zombies disabled", MP-client and Last Stand cases are the caller's.
 */
public final class RoomCount {
    private RoomCount() {}

    /**
     * @param zombiesSetting sandbox Zombies (1 insane, 2 very high, 3 high, 4 normal, 5 low)
     * @param chunkIntensity the room's chunk zombie intensity (0-255, Voronoi applied), or -1 for none
     * @param randBelow returns 0..bound-1 (the game's Rand.Next(bound))
     * @return zombies to place (never negative)
     */
    public static int count(int zombiesSetting, int chunkIntensity, int roomArea, int buildingRoomCount,
                            boolean emptyOutside, IntUnaryOperator randBelow) {
        if (emptyOutside) return 0;
        int ra = 7;
        if (zombiesSetting == 1) ra = 3;
        else if (zombiesSetting == 2) ra = 4;
        else if (zombiesSetting == 3) ra = 6;
        else if (zombiesSetting == 5) ra = 15;
        float density = 0f;
        if (chunkIntensity >= 0) {
            density = lootZombieIntensity(chunkIntensity, randBelow);
            if (density > 4f) ra = (int) (ra - (density / 2f - 2f));
        }
        if (roomArea > 100) ra -= 2;
        ra = Math.max(2, ra);
        if (buildingRoomCount > 100 && roomArea >= 20) {
            int number = Math.min(buildingRoomCount - 95, 20);
            if (zombiesSetting == 1) number += 10;
            else if (zombiesSetting == 2) number += 7;
            else if (zombiesSetting == 3) number += 5;
            else if (zombiesSetting == 5) number -= 10;
            if (roomArea < 30) number -= 6;
            if (roomArea < 50) number -= 10;
            if (roomArea < 70) number -= 13;
            return Math.max(0, next(number, number + 10, randBelow));
        }
        if (randBelow.applyAsInt(ra) != 0) return 0;
        int base = (int) (1f + (density / 2f - 2f));
        if (roomArea < 30) base -= 4;
        if (roomArea > 85) base += 2;
        if (buildingRoomCount < 7) base -= 2;
        if (zombiesSetting == 1) base += 3;
        else if (zombiesSetting == 2) base += 2;
        else if (zombiesSetting == 3) base += 1;
        else if (zombiesSetting == 5) base -= 2;
        base = Math.min(7, Math.max(0, base));
        return Math.max(0, next(base, base + 2, randBelow));
    }

    /** IsoMetaChunk.getLootZombieIntensity: 0.06 to 12 by intensity, with a small chance of a 120 spike. */
    static float lootZombieIntensity(int chunkIntensity, IntUnaryOperator randBelow) {
        float delta = (chunkIntensity & 255) / 255f;
        float v = 0.06f + 11.94f * delta;
        float chance = delta * 10f;
        if (randBelow.applyAsInt(300) <= chance) v = 120f;
        return v;
    }

    /** The game's Rand.Next(min, max): min inclusive, max exclusive, bounds swapped if reversed. */
    private static int next(int min, int max, IntUnaryOperator randBelow) {
        if (min == max) return min;
        if (min > max) {
            int t = min;
            min = max;
            max = t;
        }
        return min + randBelow.applyAsInt(max - min);
    }
}
