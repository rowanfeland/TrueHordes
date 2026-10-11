package truehordes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import truehordes.world.CollisionBits;
import zombie.MapCollisionData;
import zombie.iso.IsoGridSquare;

/**
 * Reads the game's own collision bits so the mod never disagrees with it: after addChunkToWorld the chunk's 64 bits
 * are in MapCollisionData's private squares array; for one changed square the game's private rule methods are called.
 */
final class LiveBits {
    private static Field squares;
    private static Method solid, blockedN, blockedW, water, room;
    private static volatile boolean available;

    private LiveBits() {}

    /** Resolves the reflection targets once. Returns null when ready, otherwise why live updates are off. */
    static String init() {
        try {
            squares = MapCollisionData.class.getDeclaredField("squares");
            squares.setAccessible(true);
            solid = rule(HookTargets.RULES[0]);
            blockedN = rule(HookTargets.RULES[1]);
            blockedW = rule(HookTargets.RULES[2]);
            water = rule(HookTargets.RULES[3]);
            room = rule(HookTargets.RULES[4]);
            available = true;
            return null;
        } catch (Throwable t) {
            available = false;
            return t.toString();
        }
    }

    private static Method rule(String name) throws NoSuchMethodException {
        Method m = MapCollisionData.class.getDeclaredMethod(name, IsoGridSquare.class);
        m.setAccessible(true);
        return m;
    }

    static boolean available() { return available; }

    static byte[] chunkBits() throws IllegalAccessException {
        return ((byte[]) squares.get(MapCollisionData.instance)).clone();
    }

    static int squareBits(IsoGridSquare sq) throws ReflectiveOperationException {
        MapCollisionData m = MapCollisionData.instance;
        int bits = 0;
        if ((Boolean) solid.invoke(m, sq)) bits |= CollisionBits.SOLID;
        if ((Boolean) blockedN.invoke(m, sq)) bits |= CollisionBits.WALL_N;
        if ((Boolean) blockedW.invoke(m, sq)) bits |= CollisionBits.WALL_W;
        if ((Boolean) water.invoke(m, sq)) bits |= CollisionBits.WATER;
        if ((Boolean) room.invoke(m, sq)) bits |= CollisionBits.ROOM;
        return bits;
    }
}
