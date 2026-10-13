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
    public static final int VERSION = 2;
    /** The smallest header of any version (version 1). */
    private static final int HEADER_BYTES = 4 + 4 + 8 + 8 + 8 + 4;
    private static final int RECORD_BYTES = 8 + 4 * 3 + 1 + 4 * 5 + 1 + 4;

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

    /** Version 2: header, seeded flag, population bookkeeping blob, then every record. */
    public static void write(Path file, Store store, double worldAgeHours, boolean seeded, byte[] population)
            throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + population.length + store.size() * RECORD_BYTES);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeDouble(worldAgeHours);
        out.writeLong(System.currentTimeMillis());
        out.writeLong(store.nextId());
        out.writeBoolean(seeded);
        out.writeInt(population.length);
        out.write(population);
        out.writeInt(store.size());
        for (ZombieRecord r : store.all()) {
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
            if (r.modData == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(r.modData.length);
                out.write(r.modData);
            }
        }
        out.flush();
        CRC32 crc = new CRC32();
        crc.update(bytes.toByteArray());
        out.writeLong(crc.getValue());
        out.flush();

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes.toByteArray());
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
                return decodeV2(in, okStatus);
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

    private static LoadResult decodeV2(DataInputStream in, LoadResult.Status okStatus) throws IOException {
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
        for (int i = 0; i < count; i++) {
            long id = in.readLong();
            float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
            byte dir = in.readByte();
            int outfit = in.readInt(), flags = in.readInt(), pid = in.readInt(), tx = in.readInt(), ty = in.readInt();
            boolean building = in.readBoolean();
            int mdLength = in.readInt();
            if (mdLength < -1 || mdLength > in.available()) throw new FormatException("bad mod data length " + mdLength);
            byte[] modData = mdLength < 0 ? null : in.readNBytes(mdLength);
            records.add(new ZombieRecord(id, x, y, z, dir, outfit, flags, pid, tx, ty, modData, building));
        }
        if (in.available() != 0) throw new FormatException("unexpected trailing data");
        return new LoadResult(okStatus, worldAge, nextId, records, seeded, population,
            okStatus == LoadResult.Status.OK ? "loaded" : "loaded backup");
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
