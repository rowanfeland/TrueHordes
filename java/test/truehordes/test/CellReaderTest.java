package truehordes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import truehordes.world.CellReader;
import truehordes.world.CollisionBits;

public final class CellReaderTest {
    private static int at(byte[] tiles, int x, int y) { return tiles[y * 256 + x] & 0xFF; }

    public static void testUniformChunkTypes() {
        byte[] data = FakeCells.cell()
            .chunk(1, 0, CollisionBits.CHUNK_SOLID)
            .chunk(2, 0, CollisionBits.CHUNK_WATER)
            .chunk(3, 0, CollisionBits.CHUNK_ROOM)
            .chunk(4, 0, CollisionBits.CHUNK_VOID)
            .bytes();
        CellReader.Result r = CellReader.parse(data);
        Check.isTrue(r.clean(), "clean read: " + r.error);
        Check.equal(0, at(r.tiles, 3, 3), "empty chunk tile");
        Check.equal(CollisionBits.SOLID, at(r.tiles, 8 + 7, 7), "solid chunk tile");
        Check.equal(CollisionBits.WATER, at(r.tiles, 16, 0), "water chunk tile");
        Check.equal(CollisionBits.ROOM, at(r.tiles, 24 + 4, 4), "room chunk tile");
        Check.equal(CollisionBits.NO_DATA, at(r.tiles, 32, 0), "void chunk is no data");
    }

    public static void testRegularChunkTilesLandInPlace() {
        byte[] t = new byte[64];
        t[0] = (byte) CollisionBits.WALL_N;               // tile (0,0) of the chunk
        t[7 * 8 + 7] = (byte) (CollisionBits.WALL_W | CollisionBits.SOLID); // tile (7,7)
        CellReader.Result r = CellReader.parse(FakeCells.cell().tiles(31, 31, t).bytes());
        Check.isTrue(r.clean(), "clean read: " + r.error);
        Check.equal(CollisionBits.WALL_N, at(r.tiles, 248, 248), "first tile of last chunk");
        Check.equal(CollisionBits.WALL_W | CollisionBits.SOLID, at(r.tiles, 255, 255), "last tile of last chunk");
    }

    public static void testTruncatedFileKeepsWhatWasRead() {
        byte[] full = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_WATER).bytes();
        CellReader.Result r = CellReader.parse(Arrays.copyOf(full, 100));
        Check.isTrue(r.tiles != null && r.error != null, "tiles plus an error");
        Check.isTrue(r.error.contains("truncated"), "says truncated: " + r.error);
        Check.equal(CollisionBits.WATER, at(r.tiles, 0, 0), "chunk 0 kept");
        Check.equal(CollisionBits.NO_DATA, at(r.tiles, 255, 255), "unread chunk is no data");
    }

    public static void testWrongVersionIsNoData() {
        byte[] data = FakeCells.cell().bytes();
        data[1] = 7;
        CellReader.Result r = CellReader.parse(data);
        Check.isTrue(r.error != null && r.error.contains("version"), "version error: " + r.error);
        Check.equal(CollisionBits.NO_DATA, at(r.tiles, 0, 0), "all no data");
    }

    public static void testUnknownChunkTypeStops() {
        byte[] data = FakeCells.cell().chunk(5, 0, 9).bytes();
        CellReader.Result r = CellReader.parse(data);
        Check.isTrue(r.error != null && r.error.contains("chunk type 9"), "type error: " + r.error);
        Check.equal(0, at(r.tiles, 0, 0), "chunks before it kept");
        Check.equal(CollisionBits.NO_DATA, at(r.tiles, 40, 0), "chunk with the unknown type is no data");
    }

    public static void testMissingFile() throws Exception {
        Path dir = Files.createTempDirectory("th-cells");
        CellReader.Result r = CellReader.read(dir.resolve("chunkdata_1_1.bin"));
        Check.isTrue(r.missing(), "missing");
        Check.isTrue(CellReader.read(null).missing(), "null path is missing");
    }

    public static void testReadFromDisk() throws Exception {
        Path dir = Files.createTempDirectory("th-cells");
        Path p = FakeCells.cell().chunk(0, 0, CollisionBits.CHUNK_SOLID).write(dir, "chunkdata_0_0.bin");
        CellReader.Result r = CellReader.read(p);
        Check.isTrue(r.clean(), "clean");
        Check.equal(CollisionBits.SOLID, at(r.tiles, 0, 0), "solid");
    }

    public static void testWalkable() {
        Check.isTrue(CollisionBits.walkable(0), "empty");
        Check.isTrue(CollisionBits.walkable(CollisionBits.WALL_N | CollisionBits.WALL_W), "walls on edges still walkable");
        Check.isTrue(!CollisionBits.walkable(CollisionBits.SOLID), "solid");
        Check.isTrue(!CollisionBits.walkable(CollisionBits.WATER), "water (D16)");
        Check.isTrue(!CollisionBits.walkable(CollisionBits.ROOM), "room (sealed buildings)");
        Check.isTrue(!CollisionBits.walkable(CollisionBits.NO_DATA), "no data");
    }
}
