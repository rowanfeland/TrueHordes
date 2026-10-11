package truehordes.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes collision files in the game's chunkdata_X_Y.bin format for tests. */
public final class FakeCells {
    private final int[] types = new int[1024];
    private final byte[][] regular = new byte[1024][];

    private FakeCells() {}

    /** A cell whose 32x32 chunks are all empty (type 0). */
    public static FakeCells cell() { return new FakeCells(); }

    public FakeCells chunk(int wx, int wy, int type) {
        types[wy * 32 + wx] = type;
        return this;
    }

    /** A regular chunk (type 2) with these 64 tile bytes (index ty * 8 + tx). */
    public FakeCells tiles(int wx, int wy, byte[] tiles64) {
        types[wy * 32 + wx] = 2;
        regular[wy * 32 + wx] = tiles64.clone();
        return this;
    }

    public byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(1);
        for (int i = 0; i < 1024; i++) {
            out.write(types[i]);
            if (types[i] == 2) out.write(regular[i], 0, 64);
        }
        return out.toByteArray();
    }

    public Path write(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, bytes());
        return p;
    }

    /** 64 tile bytes all set to bits. */
    public static byte[] fill(int bits) {
        byte[] t = new byte[64];
        java.util.Arrays.fill(t, (byte) bits);
        return t;
    }
}
