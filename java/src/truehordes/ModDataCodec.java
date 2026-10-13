package truehordes;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoZombie;
import zombie.iso.IsoWorld;

/** A zombie's ModData to bytes and back, with the game's own Lua table serialiser (so any mod's state survives). */
final class ModDataCodec {
    private static final int MAX_BYTES = 4 << 20;

    private ModDataCodec() {}

    /** Null when the zombie has no (or empty) mod data. */
    static byte[] save(IsoZombie z) {
        if (!z.hasModData()) return null;
        KahluaTable t = z.getModData();
        if (!(t instanceof KahluaTableImpl) || t.isEmpty()) return null;
        for (int size = 4096; size <= MAX_BYTES; size *= 4) {
            ByteBuffer buf = ByteBuffer.allocate(size);
            try {
                ((KahluaTableImpl) t).save(buf);
                return Arrays.copyOf(buf.array(), buf.position());
            } catch (BufferOverflowException e) {
                // grow and retry
            }
        }
        throw new IllegalStateException("zombie mod data larger than " + MAX_BYTES + " bytes");
    }

    static void load(IsoZombie z, byte[] data) {
        if (data == null || data.length == 0) return;
        KahluaTable t = z.getModData();
        if (t instanceof KahluaTableImpl) ((KahluaTableImpl) t).load(ByteBuffer.wrap(data), IsoWorld.getWorldVersion());
    }
}
