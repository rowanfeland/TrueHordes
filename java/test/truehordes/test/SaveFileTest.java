package truehordes.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import truehordes.save.LoadResult;
import truehordes.save.SaveFile;
import truehordes.store.Entity;
import truehordes.store.Store;
import truehordes.util.Log;

public final class SaveFileTest {
    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("th-save-test");
    }

    private static Store sample() {
        Store s = new Store();
        s.create(Entity.KIND_PLACEHOLDER, 10.5f, 20.25f, (byte) 0);
        s.create((byte) 3, -5f, 7f, (byte) 1);
        s.remove(1);
        s.create(Entity.KIND_PLACEHOLDER, 1f, 2f, (byte) 0);
        return s;
    }

    public static void testRoundTripIsExact() throws IOException {
        Path f = tempDir().resolve("truehordes/state.bin");
        Store s = sample();
        SaveFile.write(f, s, 123.5);
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.OK, r.status, "status");
        Check.near(123.5, r.worldAgeHours, 0, "world age stored");
        Check.equal(s.nextId(), r.nextId, "next id stored");
        Check.equal(2, r.entities.size(), "entity count");
        Entity e = r.entities.get(0);
        Check.equal(2L, e.id, "id kept");
        Check.equal((byte) 3, e.kind, "kind kept");
        Check.near(-5f, e.x, 0, "x exact");
        Check.equal((byte) 1, e.z, "z kept");
        // Save the loaded data again: bytes must be identical apart from the savedAtMillis field (offset 16..23).
        Store again = new Store();
        for (Entity x : r.entities) again.restore(x);
        again.setNextId(r.nextId);
        Path g = f.resolveSibling("again.bin");
        SaveFile.write(g, again, 123.5);
        byte[] a = Files.readAllBytes(f);
        byte[] b = Files.readAllBytes(g);
        Check.equal(a.length, b.length, "same length");
        Check.isTrue(Arrays.equals(Arrays.copyOfRange(a, 0, 16), Arrays.copyOfRange(b, 0, 16)), "same header start");
        Check.isTrue(Arrays.equals(Arrays.copyOfRange(a, 24, a.length - 8), Arrays.copyOfRange(b, 24, b.length - 8)), "same body");
    }

    public static void testMissingFileIsEmptyNotError() throws IOException {
        LoadResult r = SaveFile.read(tempDir().resolve("state.bin"));
        Check.equal(LoadResult.Status.MISSING, r.status, "missing");
        Check.equal(0, r.entities.size(), "empty");
    }

    public static void testSecondWriteKeepsBackupOfFirst() throws IOException {
        Path f = tempDir().resolve("state.bin");
        Store s = sample();
        SaveFile.write(f, s, 1.0);
        s.create(Entity.KIND_PLACEHOLDER, 0, 0, (byte) 0);
        SaveFile.write(f, s, 2.0);
        Check.isTrue(Files.exists(SaveFile.backupOf(f)), "backup exists");
        Check.near(1.0, SaveFile.read(SaveFile.backupOf(f)).worldAgeHours, 0, "backup is the previous save");
        Check.near(2.0, SaveFile.read(f).worldAgeHours, 0, "main is the newest save");
        Check.isTrue(!Files.exists(f.resolveSibling("state.bin.tmp")), "no temp file left");
    }

    public static void testTruncatedFileRecoversFromBackup() throws IOException {
        Log.setSink(line -> {});
        Path f = tempDir().resolve("state.bin");
        Store s = sample();
        SaveFile.write(f, s, 1.0);
        SaveFile.write(f, s, 2.0);
        byte[] bytes = Files.readAllBytes(f);
        Files.write(f, Arrays.copyOf(bytes, bytes.length / 2));
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.RECOVERED, r.status, "recovered from backup");
        Check.near(1.0, r.worldAgeHours, 0, "backup data");
        Check.isTrue(!Files.exists(f), "bad file moved aside");
        try (var listing = Files.list(f.getParent())) {
            Check.isTrue(listing.anyMatch(p -> p.getFileName().toString().startsWith("state.bin.bad-")), "bad copy kept");
        }
        Log.setSink(null);
    }

    public static void testCorruptWithoutBackupStartsEmpty() throws IOException {
        Log.setSink(line -> {});
        Path f = tempDir().resolve("state.bin");
        Files.write(f, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.FAILED, r.status, "failed");
        Check.equal(0, r.entities.size(), "empty");
        Check.isTrue(r.message.length() > 0, "explains why");
        Log.setSink(null);
    }

    public static void testNewerVersionIsSetAside() throws IOException {
        Log.setSink(line -> {});
        Path f = tempDir().resolve("state.bin");
        SaveFile.write(f, sample(), 1.0);
        byte[] bytes = Files.readAllBytes(f);
        bytes[7] = 99; // version int low byte -> 99
        // Re-stamp the CRC so only the version is "wrong".
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        java.nio.ByteBuffer.wrap(bytes, bytes.length - 8, 8).putLong(crc.getValue());
        Files.write(f, bytes);
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.FAILED, r.status, "newer version not loaded");
        Check.isTrue(r.message.contains("newer"), "says it is newer: " + r.message);
        Check.isTrue(!Files.exists(f), "kept aside, not overwritten later");
        Log.setSink(null);
    }
}
