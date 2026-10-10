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
import truehordes.store.Entity;
import truehordes.store.Store;
import truehordes.util.Log;

/**
 * The mod's own save file, written in the same save cycle as the world.
 * Writes go to "<file>.tmp" (flushed to disk), the previous file is copied to "<file>.bak", then the temp file
 * replaces the main one atomically, so a half-written file never replaces a good one.
 */
public final class SaveFile {
    public static final int MAGIC = 0x54485244; // "THRD"
    public static final int VERSION = 1;
    private static final int HEADER_BYTES = 4 + 4 + 8 + 8 + 8 + 4;
    private static final int ENTITY_BYTES = 8 + 1 + 4 + 4 + 1;

    private SaveFile() {}

    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    public static void write(Path file, Store store, double worldAgeHours) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_BYTES + store.size() * ENTITY_BYTES + 8);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeDouble(worldAgeHours);
        out.writeLong(System.currentTimeMillis());
        out.writeLong(store.nextId());
        out.writeInt(store.size());
        for (Entity e : store.all()) {
            out.writeLong(e.id);
            out.writeByte(e.kind);
            out.writeFloat(e.x);
            out.writeFloat(e.y);
            out.writeByte(e.z);
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
            } catch (IOException | RuntimeException e) {
                problem = file.getFileName() + " unreadable (" + e.getMessage() + ")";
                setAside(file);
            }
        } else {
            problem = file.getFileName() + " missing";
        }
        if (Files.exists(bak)) {
            try {
                LoadResult r = decode(bak, LoadResult.Status.RECOVERED);
                Log.warn(problem + "; loaded the backup instead");
                return new LoadResult(r.status, r.worldAgeHours, r.nextId, r.entities, problem + "; loaded backup");
            } catch (IOException | RuntimeException e) {
                problem += "; backup unreadable too (" + e.getMessage() + ")";
            }
        }
        Log.warn(problem + "; starting empty");
        return LoadResult.empty(LoadResult.Status.FAILED, problem);
    }

    private static LoadResult decode(Path file, LoadResult.Status okStatus) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length < HEADER_BYTES + 8) throw new IOException("file too short");
        CRC32 crc = new CRC32();
        crc.update(all, 0, all.length - 8);
        long stored = ByteBuffer.wrap(all, all.length - 8, 8).getLong();
        if (stored != crc.getValue()) throw new IOException("checksum mismatch");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(all, 0, all.length - 8));
        if (in.readInt() != MAGIC) throw new IOException("not a True Hordes save");
        int version = in.readInt();
        if (version > VERSION) throw new IOException("made by a newer version of True Hordes (" + version + ")");
        switch (version) {
            case 1:
                return decodeV1(in, okStatus);
            default:
                throw new IOException("unsupported save version " + version);
        }
    }

    private static LoadResult decodeV1(DataInputStream in, LoadResult.Status okStatus) throws IOException {
        double worldAge = in.readDouble();
        in.readLong(); // savedAtMillis: informational
        long nextId = in.readLong();
        int count = in.readInt();
        if (count < 0) throw new IOException("negative entity count");
        List<Entity> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entities.add(new Entity(in.readLong(), in.readByte(), in.readFloat(), in.readFloat(), in.readByte()));
        }
        if (in.available() != 0) throw new IOException("unexpected trailing data");
        return new LoadResult(okStatus, worldAge, nextId, entities, okStatus == LoadResult.Status.OK ? "loaded" : "loaded backup");
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
