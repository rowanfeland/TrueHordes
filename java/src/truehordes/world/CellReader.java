package truehordes.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reads one cell's collision file into 256x256 tile bytes (index = y * 256 + x, cell-local). Format (42.21): big-endian
 * short version 1, then for each of the 32x32 chunks (y outer) one type byte; only a regular chunk (2) is followed by
 * its 64 tile bytes. Never throws: problems come back in {@link Result#error}, unread parts are NO_DATA.
 */
public final class CellReader {
    public static final int VERSION = 1;
    private static final int TILES = CollisionBits.CELL_TILES * CollisionBits.CELL_TILES;

    /** Tiles of one cell (null when the file is missing) and an error (null when the whole file read cleanly). */
    public static final class Result {
        public final byte[] tiles;
        public final String error;

        Result(byte[] tiles, String error) {
            this.tiles = tiles;
            this.error = error;
        }

        public boolean missing() { return tiles == null; }

        public boolean clean() { return tiles != null && error == null; }
    }

    private CellReader() {}

    public static byte[] noData() {
        byte[] t = new byte[TILES];
        Arrays.fill(t, (byte) CollisionBits.NO_DATA);
        return t;
    }

    public static Result read(Path file) {
        if (file == null) return new Result(null, "no file");
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new Result(null, "missing");
        } catch (IOException | RuntimeException e) {
            return new Result(null, "unreadable: " + e);
        }
        return parse(data);
    }

    public static Result parse(byte[] data) {
        byte[] tiles = noData();
        if (data.length < 2) return new Result(tiles, "truncated header");
        int version = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (version != VERSION) return new Result(tiles, "unknown version " + version);
        int pos = 2;
        for (int c = 0; c < 1024; c++) {
            if (pos >= data.length) return new Result(tiles, "truncated at chunk " + c);
            int type = data[pos++] & 0xFF;
            int ox = (c % 32) * 8;
            int oy = (c / 32) * 8;
            if (type == CollisionBits.CHUNK_REGULAR) {
                if (pos + 64 > data.length) return new Result(tiles, "truncated in chunk " + c);
                for (int ty = 0; ty < 8; ty++) System.arraycopy(data, pos + ty * 8, tiles, (oy + ty) * 256 + ox, 8);
                pos += 64;
            } else {
                int bits = uniformBits(type);
                if (bits < 0) return new Result(tiles, "unknown chunk type " + type + " at chunk " + c);
                for (int ty = 0; ty < 8; ty++) {
                    int row = (oy + ty) * 256 + ox;
                    Arrays.fill(tiles, row, row + 8, (byte) bits);
                }
            }
        }
        if (pos != data.length) return new Result(tiles, (data.length - pos) + " bytes after the last chunk");
        return new Result(tiles, null);
    }

    private static int uniformBits(int type) {
        switch (type) {
            case CollisionBits.CHUNK_EMPTY: return 0;
            case CollisionBits.CHUNK_SOLID: return CollisionBits.SOLID;
            case CollisionBits.CHUNK_WATER: return CollisionBits.WATER;
            case CollisionBits.CHUNK_ROOM: return CollisionBits.ROOM;
            case CollisionBits.CHUNK_VOID: return CollisionBits.NO_DATA;
            default: return -1;
        }
    }
}
