package truehordes.test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.IntUnaryOperator;
import truehordes.population.RoomCount;

/** Expected values worked out by hand from VirtualZombieManager.getZombieCountForRoom (42.21). */
public final class RoomCountTest {
    /** Returns the given rolls in order (each must be below its bound). */
    private static IntUnaryOperator rolls(int... values) {
        Deque<Integer> q = new ArrayDeque<>();
        for (int v : values) q.add(v);
        return bound -> {
            int v = q.isEmpty() ? 0 : q.poll();
            if (v >= bound) throw new AssertionError("roll " + v + " not below " + bound);
            return v;
        };
    }

    public static void testEmptyOutsideIsZero() {
        Check.equal(0, RoomCount.count(4, 255, 40, 10, true, rolls()), "empty outside");
    }

    public static void testQuietRoom() {
        // intensity 0 -> loot 0.06 (roll 299 > chance 0); ra 7; roll 0 -> base (int)(1 - 1.97) = 0 -> next(0, 2)
        Check.equal(1, RoomCount.count(4, 0, 40, 10, false, rolls(299, 0, 1)), "base 0, roll 1");
        Check.equal(0, RoomCount.count(4, 0, 40, 10, false, rolls(299, 3)), "ra roll not 0: no zombies");
    }

    public static void testDenseBigRoom() {
        // intensity 255 -> loot 12 (roll 50 > chance 10); ra (int)(7 - 4) = 3; roll 0 -> base (int)(1 + 4) = 5,
        // area 90 > 85 -> 7; clamp 7 -> next(7, 9) roll 1 -> 8
        Check.equal(8, RoomCount.count(4, 255, 90, 10, false, rolls(50, 0, 1)), "dense big room");
    }

    public static void testDensitySpikeTo120() {
        // roll 0 <= chance 0: loot jumps to 120 -> ra (int)(7 - 58) -> max 2; roll 0 -> base (int)(1 + 58) = 59 -> clamp 7
        Check.equal(7, RoomCount.count(4, 0, 40, 10, false, rolls(0, 0, 0)), "the game's 1-in-300 spike");
    }

    public static void testHugeBuilding() {
        // rooms 130, area 60: number min(35, 20) = 20, area < 70 -> 7 -> next(7, 17) roll 3 -> 10
        Check.equal(10, RoomCount.count(4, 0, 60, 130, false, rolls(299, 3)), "big building");
        // area 20: 20 - 6 - 10 - 13 = -9 -> next(-9, 1) roll 2 -> -7, reported as 0
        Check.equal(0, RoomCount.count(4, 0, 20, 130, false, rolls(299, 2)), "negative counts are 0");
    }

    public static void testZombiesSettingShiftsCounts() {
        // insane (1): ra 3; roll 0 -> base 0 + 3 = 3 -> next(3, 5) roll 0 -> 3
        Check.equal(3, RoomCount.count(1, 0, 40, 10, false, rolls(299, 0, 0)), "insane");
        // low (5): ra 15; roll 0 -> base 0 - 2 -> clamp 0 -> next(0, 2) roll 0 -> 0
        Check.equal(0, RoomCount.count(5, 0, 40, 10, false, rolls(299, 0, 0)), "low");
    }
}
