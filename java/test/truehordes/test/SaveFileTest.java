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

    public static void testV3RoundTripKeepsHordesAndWalkFields() throws IOException {
        Path f = tempDir().resolve("state.bin");
        Store s = sample();
        ZombieRecord w = s.all().iterator().next();
        w.heading = 1.25f;
        w.pauseMinutes = 3.5f;
        w.straggler = true;
        w.strRolled = true;
        w.followId = 42;
        w.hordeId = 7;
        w.lingerUntilHours = 99.5;
        w.idle = true;
        truehordes.sim.Hordes hs = new truehordes.sim.Hordes();
        java.util.List<ZombieRecord> m = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            m.add(new ZombieRecord(500 + i, 10, 20, 0, (byte) 2, 300 + i, 4, 900 + i, ZombieRecord.NO_TARGET,
                ZombieRecord.NO_TARGET, new byte[] {(byte) i}, false));
        }
        truehordes.sim.Horde h = hs.create(1000.5f, 2000.25f, -0.5f, m);
        h.pauseMinutes = 4f;
        SaveFile.writeBytes(f, SaveFile.encode(s, hs, 12.0, true, new byte[] {9, 8}));
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.OK, r.status, "read back");
        ZombieRecord back = null;
        for (ZombieRecord x : r.records) if (x.id == w.id) back = x;
        Check.isTrue(back != null, "wanderer kept");
        Check.near(1.25, back.heading, 1e-6, "heading");
        Check.near(3.5, back.pauseMinutes, 1e-6, "pause");
        Check.isTrue(back.straggler && back.strRolled && back.idle, "flags");
        Check.equal(42L, back.followId, "follow");
        Check.equal(7L, back.hordeId, "horde id");
        Check.near(99.5, back.lingerUntilHours, 1e-9, "linger");
        Check.equal(1, r.hordes.size(), "one horde");
        LoadResult.SavedHorde sh = r.hordes.get(0);
        Check.equal(h.id, sh.id(), "horde id kept");
        Check.near(1000.5, sh.x(), 1e-4, "x");
        Check.near(2000.25, sh.y(), 1e-4, "y");
        Check.near(-0.5, sh.heading(), 1e-6, "heading");
        Check.near(4, sh.pauseMinutes(), 1e-6, "pause");
        Check.equal(12, sh.members().size(), "members");
        Check.equal(305, sh.members().get(5).outfitId, "member outfit");
        Check.equal(905, sh.members().get(5).persistentId, "member persistent id");
        Check.equal((byte) 5, sh.members().get(5).modData[0], "member mod data");
        Check.equal(h.id, sh.members().get(5).hordeId, "member knows its horde");
        Check.equal(hs.nextId(), r.hordeNextId, "horde id counter");
    }

    /** In-game test 2026-10-14: small hitches on every hot save. The in-memory encode must be quick. */
    public static void testEncodeIsQuick() throws IOException {
        Store s = new Store();
        for (int i = 0; i < 200_000; i++) {
            s.add(i % 5000, i / 5000, 0, (byte) 0, 1, 4, i, ZombieRecord.NO_TARGET, ZombieRecord.NO_TARGET,
                i % 10 == 0 ? new byte[40] : null, false);
        }
        for (int i = 0; i < 3; i++) SaveFile.encode(s, null, 1.0, true, new byte[1000]); // warm up
        long ms = Long.MAX_VALUE; // best of 5: other test threads and the collector share this JVM
        byte[] data = null;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            data = SaveFile.encode(s, null, 1.0, true, new byte[1000]);
            ms = Math.min(ms, (System.nanoTime() - t0) / 1_000_000);
        }
        Check.isTrue(data.length > 200_000 * 50, "all records encoded");
        // The old stream-based encode took about 87 ms here; one sized buffer is about half that or less.
        Check.isTrue(ms < 60, "200,000 records encode in under 60 ms (" + ms + " ms)");
    }

    /** A version 2 file exactly as sub-project 3 wrote it. */
    private static void writeV2(Path f, Store s) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeInt(SaveFile.MAGIC);
        out.writeInt(2);
        out.writeDouble(5.0);
        out.writeLong(0L);
        out.writeLong(s.nextId());
        out.writeBoolean(true);
        out.writeInt(0);
        out.writeInt(s.size());
        for (ZombieRecord r : s.all()) {
            out.writeLong(r.id);
            out.writeFloat(r.x());
            out.writeFloat(r.y());
            out.writeFloat(r.z);
            out.writeByte(r.dir);
            out.writeInt(r.outfitId);
            out.writeInt(r.stateFlags);
            out.writeInt(r.persistentId);
            out.writeInt(r.targetX);
            out.writeInt(r.targetY);
            out.writeBoolean(r.building);
            out.writeInt(-1);
        }
        out.flush();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes.toByteArray());
        out.writeLong(crc.getValue());
        out.flush();
        Files.write(f, bytes.toByteArray());
    }

    public static void testV2UpgradesToWanderers() throws IOException {
        Path f = tempDir().resolve("state.bin");
        Store s = sample();
        writeV2(f, s);
        LoadResult r = SaveFile.read(f);
        Check.equal(LoadResult.Status.OK, r.status, "version 2 reads");
        Check.equal(s.size(), r.records.size(), "every zombie kept");
        Check.equal(0, r.hordes.size(), "no hordes yet");
        for (ZombieRecord x : r.records) {
            Check.isTrue(!x.strRolled, "straggler rolled later");
            Check.equal(0L, x.hordeId, "a wanderer");
            Check.equal(0L, x.followId, "following nobody");
        }
        Check.isTrue(r.seeded, "seeded flag kept");
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
