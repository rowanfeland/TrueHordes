package truehordes.save;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import truehordes.sim.Horde;
import truehordes.sim.Hordes;
import truehordes.store.Store;
import truehordes.store.ZombieRecord;
import truehordes.util.Log;

/**
 * The mod's own save file, written in the same save cycle as the world.
 * Writes go to "<file>.tmp" (flushed to disk), the previous file is copied to "<file>.bak", then the temp file
 * replaces the main one atomically, so a half-written file never replaces a good one.
 */
public final class SaveFile {
    public static final int MAGIC = 0x54485244; // "THRD"
    public static final int VERSION = 3;
    /** The smallest header of any version (version 1). */
    private static final int HEADER_BYTES = 4 + 4 + 8 + 8 + 8 + 4;
    private static final int RECORD_BYTES = 8 + 4 * 3 + 1 + 4 * 5 + 1 + 4 + 4 * 2 + 2 + 8 * 3 + 1;

    private SaveFile() {}

    /** The file was read but is not a valid save (checksum, magic, version, length). Only these are set aside. */
    static final class FormatException extends IOException {
        FormatException(String message) {
            super(message);
        }
    }

    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    public static void write(Path file, Store store, double worldAgeHours) throws IOException {
        write(file, store, worldAgeHours, false, new byte[0]);
    }

    public static void write(Path file, Store store, double worldAgeHours, boolean seeded, byte[] population)
            throws IOException {
        writeBytes(file, encode(store, null, worldAgeHours, seeded, population));
    }

    /**
     * Version 3, in memory (quick; done under the core's lock): header, seeded flag, population bookkeeping blob,
     * every wanderer record, then every horde with its member records; a CRC32 at the end.
     */
    public static byte[] encode(Store store, Hordes hordes, double worldAgeHours, boolean seeded, byte[] population)
            throws IOException {
        // Exact size first, then one pass into one buffer: no per-byte stream calls, no copies (this runs on the
        // game's main thread under the core's lock).
        long size = 4 + 4 + 8 + 8 + 8 + 1 + 4 + population.length + 4 + 8 + 4 + 8;
        for (ZombieRecord r : store.all()) size += recordSize(r);
        if (hordes != null) {
            for (Horde h : hordes.all()) {
                size += 8 + 4 * 4 + 4;
                for (ZombieRecord r : h.members) size += recordSize(r);
            }
        }
        if (size > Integer.MAX_VALUE - 16) throw new IOException("save too large: " + size + " bytes");
        ByteBuffer out = ByteBuffer.allocate((int) size);
        out.putInt(MAGIC);
        out.putInt(VERSION);
        out.putDouble(worldAgeHours);
        out.putLong(System.currentTimeMillis());
        out.putLong(store.nextId());
        out.put((byte) (seeded ? 1 : 0));
        out.putInt(population.length);
        out.put(population);
        out.putInt(store.size());
        for (ZombieRecord r : store.all()) putRecord(out, r);
        out.putLong(hordes == null ? 1 : hordes.nextId());
        out.putInt(hordes == null ? 0 : hordes.size());
        if (hordes != null) {
            for (Horde h : hordes.all()) {
                out.putLong(h.id);
                out.putFloat(h.x);
                out.putFloat(h.y);
                out.putFloat(h.heading);
                out.putFloat(h.pauseMinutes);
                out.putInt(h.size());
                for (ZombieRecord r : h.members) putRecord(out, r);
            }
        }
        CRC32 crc = new CRC32();
        crc.update(out.array(), 0, out.position());
        out.putLong(crc.getValue());
        if (out.hasRemaining()) throw new IOException("save size miscounted");
        return out.array();
    }

    private static int recordSize(ZombieRecord r) {
        return RECORD_BYTES + (r.modData == null ? 0 : r.modData.length);
    }

    private static void putRecord(ByteBuffer out, ZombieRecord r) {
        out.putLong(r.id);
        out.putFloat(r.x());
        out.putFloat(r.y());
        out.putFloat(r.z);
        out.put(r.dir);
        out.putInt(r.outfitId);
        out.putInt(r.stateFlags);
        out.putInt(r.persistentId);
        out.putInt(r.targetX);
        out.putInt(r.targetY);
        out.put((byte) (r.building ? 1 : 0));
        if (r.modData == null) {
            out.putInt(-1);
        } else {
            out.putInt(r.modData.length);
            out.put(r.modData);
        }
        // Walk state (version 3).
        out.putFloat(r.heading);
        out.putFloat(r.pauseMinutes);
        out.put((byte) (r.straggler ? 1 : 0));
        out.put((byte) (r.strRolled ? 1 : 0));
        out.putLong(r.followId);
        out.putLong(r.hordeId);
        out.putDouble(r.lingerUntilHours);
        out.put((byte) (r.idle ? 1 : 0));
    }

    /** Writes encoded bytes: a flushed temp file, the old file kept as .bak, then an atomic replace. */
    public static void writeBytes(Path file, byte[] data) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
        if (Files.exists(file)) Files.copy(file, backupOf(file), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static LoadResult read(Path file) {
        Path bak = backupOf(file);
        if (!Files.exists(file) && !Files.exists(bak)) return LoadResult.empty(LoadResult.Status.MISSING, "no save file yet");
        String problem;
        if (Files.exists(file)) {
            try {
                return decode(file, LoadResult.Status.OK);
            } catch (FormatException | RuntimeException e) {
                problem = file.getFileName() + " unreadable (" + e.getMessage() + ")";
                setAside(file);
            } catch (IOException e) {
                // Locked by another program, access denied, etc.: the file may be fine, so leave it in place.
                problem = file.getFileName() + " could not be read right now (" + e + ")";
            }
        } else {
            problem = file.getFileName() + " missing";
        }
        if (Files.exists(bak)) {
            try {
                LoadResult r = decode(bak, LoadResult.Status.RECOVERED);
                Log.warn(problem + "; loaded the backup instead");
                return r.withStatus(r.status, problem + "; loaded backup");
            } catch (IOException | RuntimeException e) {
                problem += "; backup unreadable too (" + e.getMessage() + ")";
            }
        }
        Log.warn(problem + "; starting empty");
        return LoadResult.empty(LoadResult.Status.FAILED, problem);
    }

    private static LoadResult decode(Path file, LoadResult.Status okStatus) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length < HEADER_BYTES + 8) throw new FormatException("file too short");
        CRC32 crc = new CRC32();
        crc.update(all, 0, all.length - 8);
        long stored = ByteBuffer.wrap(all, all.length - 8, 8).getLong();
        if (stored != crc.getValue()) throw new FormatException("checksum mismatch");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(all, 0, all.length - 8));
        if (in.readInt() != MAGIC) throw new FormatException("not a True Hordes save");
        int version = in.readInt();
        if (version > VERSION) throw new FormatException("made by a newer version of True Hordes (" + version + ")");
        switch (version) {
            case 1:
                return decodeV1(in, okStatus);
            case 2:
            case 3:
                return decodeV2(in, okStatus, version);
            default:
                throw new FormatException("unsupported save version " + version);
        }
    }

    /** Version 1 (sub-project 1) held only test placeholders: they are discarded. */
    private static LoadResult decodeV1(DataInputStream in, LoadResult.Status okStatus) throws IOException {
        double worldAge = in.readDouble();
        in.readLong(); // savedAtMillis: informational
        long nextId = in.readLong();
        int count = in.readInt();
        if (count < 0) throw new FormatException("negative entity count");
        in.skipNBytes((long) count * (8 + 1 + 4 + 4 + 1));
        if (in.available() != 0) throw new FormatException("unexpected trailing data");
        Log.info("save file version 1: " + count + " test placeholders discarded");
        return new LoadResult(okStatus, worldAge, nextId, List.of(), false, new byte[0],
            "version 1 placeholders discarded");
    }

    /** Versions 2 and 3 (3 adds walk state per record and the hordes; version 2 records load as wanderers). */
    private static LoadResult decodeV2(DataInputStream in, LoadResult.Status okStatus, int version) throws IOException {
        double worldAge = in.readDouble();
        in.readLong(); // savedAtMillis: informational
        long nextId = in.readLong();
        boolean seeded = in.readBoolean();
        int popLength = in.readInt();
        if (popLength < 0 || popLength > in.available()) throw new FormatException("bad population length " + popLength);
        byte[] population = in.readNBytes(popLength);
        int count = in.readInt();
        if (count < 0) throw new FormatException("negative record count");
        List<ZombieRecord> records = new ArrayList<>(Math.min(count, 1 << 20));
        for (int i = 0; i < count; i++) records.add(readRecord(in, version));
        List<LoadResult.SavedHorde> hordes = new ArrayList<>();
        long hordeNextId = 1;
        if (version >= 3) {
            hordeNextId = in.readLong();
            int hordeCount = in.readInt();
            if (hordeCount < 0) throw new FormatException("negative horde count");
            for (int i = 0; i < hordeCount; i++) {
                long id = in.readLong();
                float x = in.readFloat(), y = in.readFloat(), heading = in.readFloat(), pause = in.readFloat();
                int n = in.readInt();
                if (n < 0) throw new FormatException("negative horde size");
                List<ZombieRecord> members = new ArrayList<>(Math.min(n, 1 << 16));
                for (int k = 0; k < n; k++) members.add(readRecord(in, version));
                hordes.add(new LoadResult.SavedHorde(id, x, y, heading, pause, members));
            }
        }
        if (in.available() != 0) throw new FormatException("unexpected trailing data");
        return new LoadResult(okStatus, worldAge, nextId, records, seeded, population,
            okStatus == LoadResult.Status.OK ? "loaded" : "loaded backup", hordes, hordeNextId);
    }

    private static ZombieRecord readRecord(DataInputStream in, int version) throws IOException {
        long id = in.readLong();
        float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
        byte dir = in.readByte();
        int outfit = in.readInt(), flags = in.readInt(), pid = in.readInt(), tx = in.readInt(), ty = in.readInt();
        boolean building = in.readBoolean();
        int mdLength = in.readInt();
        if (mdLength < -1 || mdLength > in.available()) throw new FormatException("bad mod data length " + mdLength);
        byte[] modData = mdLength < 0 ? null : in.readNBytes(mdLength);
        ZombieRecord r = new ZombieRecord(id, x, y, z, dir, outfit, flags, pid, tx, ty, modData, building);
        if (version >= 3) {
            r.heading = in.readFloat();
            r.pauseMinutes = in.readFloat();
            r.straggler = in.readBoolean();
            r.strRolled = in.readBoolean();
            r.followId = in.readLong();
            r.hordeId = in.readLong();
            r.lingerUntilHours = in.readDouble();
            r.idle = in.readBoolean();
        }
        return r;
    }

    private static void setAside(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
            Log.warn("kept the unreadable save as " + bad.getFileName());
        } catch (IOException e) {
            Log.error("could not move the unreadable save aside", e);
        }
    }
}
