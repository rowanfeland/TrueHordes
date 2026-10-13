package truehordes.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import truehordes.save.LoadResult;
import truehordes.save.SaveFile;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;
import truehordes.util.Log;

public final class SaveFileTest {
    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("th-save-test");
    }

    private static Store sample() {
        Store s = new Store();
        StoreTest.add(s, 10.5f, 20.25f);
        s.add(-5f, 7f, 1f, (byte) 3, 4242, 0x15, 987, 120, -40, new byte[] {9, 8, 7}, true);
        s.remove(1);
        StoreTest.add(s, 1f, 2f);
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
        Check.equal(2, r.records.size(), "record count");
        ZombieRecord e = r.records.get(0);
        Check.equal(2L, e.id, "id kept");
        Check.near(-5f, e.x(), 0, "x exact");
        Check.near(7f, e.y(), 0, "y exact");
        Check.near(1f, e.z, 0, "z kept");
        Check.equal((byte) 3, e.dir, "direction kept");
        Check.equal(4242, e.outfitId, "outfit kept");
        Check.equal(0x15, e.stateFlags, "state flags kept");
        Check.equal(987, e.persistentId, "persistent ID kept");
        Check.equal(120, e.targetX, "target x kept");
        Check.equal(-40, e.targetY, "target y kept");
        Check.isTrue(Arrays.equals(new byte[] {9, 8, 7}, e.modData), "mod data kept");
        Check.isTrue(e.building, "building flag kept");
        Check.equal(null, r.records.get(1).modData, "no mod data stays none");
        // Save the loaded data again: bytes must be identical apart from the savedAtMillis field (offset 16..23).
        Store again = new Store();
        for (ZombieRecord x : r.records) again.restore(x);
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
        Check.equal(0, r.records.size(), "empty");
    }

    public static void testSecondWriteKeepsBackupOfFirst() throws IOException {
        Path f = tempDir().resolve("state.bin");
        Store s = sample();
        SaveFile.write(f, s, 1.0);
        StoreTest.add(s, 0, 0);
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
        Check.equal(0, r.records.size(), "empty");
        Check.isTrue(r.message.length() > 0, "explains why");
        Log.setSink(null);
    }

    /** Review (re-graded Important): a read error that is not a format problem must not move the good save aside. */
    public static void testUnreadableButNotCorruptFileIsNotSetAside() throws IOException {
        Log.setSink(line -> {});
        Path f = tempDir().resolve("state.bin");
        Store s = sample();
        SaveFile.write(f, s, 1.0);
        SaveFile.write(f, s, 2.0);           // .bak now holds hour 1.0
        Files.delete(f);
        Files.createDirectory(f);            // reading it now fails with an I/O error, not a format error
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.RECOVERED, r.status, "falls back to the backup");
        Check.isTrue(Files.isDirectory(f), "the unreadable path is left where it is");
        try (var listing = Files.list(f.getParent())) {
            Check.isTrue(listing.noneMatch(p -> p.getFileName().toString().startsWith("state.bin.bad-")), "nothing set aside");
        }
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

    public static void testModDataRoundTrip() throws IOException {
        Path f = tempDir().resolve("state.bin");
        Store s = new Store();
        byte[] big = new byte[5000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        s.add(1, 1, 0f, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, big, false);
        s.add(2, 2, 0f, (byte) 0, 0, 0, 0, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET, new byte[0], false);
        SaveFile.write(f, s, 1.0);
        LoadResult r = SaveFile.read(f);
        Check.isTrue(Arrays.equals(big, r.records.get(0).modData), "mod data byte for byte");
        Check.isTrue(r.records.get(1).modData != null && r.records.get(1).modData.length == 0, "empty mod data is not none");
    }

    public static void testSeededFlagAndPopulationBlobRoundTrip() throws IOException {
        Path f = tempDir().resolve("state.bin");
        SaveFile.write(f, sample(), 3.0, true, new byte[] {1, 2, 3, 4});
        LoadResult r = SaveFile.read(f);
        Check.isTrue(r.seeded, "seeded flag");
        Check.isTrue(Arrays.equals(new byte[] {1, 2, 3, 4}, r.population), "population blob");
        SaveFile.write(f, sample(), 3.0);
        LoadResult plain = SaveFile.read(f);
        Check.isTrue(!plain.seeded, "default not seeded");
        Check.equal(0, plain.population.length, "default empty blob");
    }

    public static void testVersion1Upgrades() throws IOException {
        Path f = tempDir().resolve("state.bin");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeInt(SaveFile.MAGIC);
        out.writeInt(1);
        out.writeDouble(55.0);
        out.writeLong(0L);
        out.writeLong(7L);
        out.writeInt(1);
        out.writeLong(6L);      // id
        out.writeByte(0);       // kind (placeholder)
        out.writeFloat(1f);
        out.writeFloat(2f);
        out.writeByte(0);
        out.flush();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes.toByteArray());
        out.writeLong(crc.getValue());
        out.flush();
        Files.write(f, bytes.toByteArray());
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.OK, r.status, "version 1 still loads");
        Check.equal(0, r.records.size(), "placeholders discarded");
        Check.near(55.0, r.worldAgeHours, 0, "world age kept");
        Check.isTrue(r.message.contains("version 1"), "says so: " + r.message);
        Check.isTrue(!r.seeded, "not seeded");
    }
}
