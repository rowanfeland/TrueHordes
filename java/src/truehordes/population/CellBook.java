package truehordes.population;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Population bookkeeping per 256x256 cell (vanilla's per-cell target and respawn timer, whether a player has ever
 * loaded it) and, sparsely, per 8x8 chunk (when a player last saw it, when respawn last refilled it). Saved in the mod's
 * file. Main thread only.
 */
public final class CellBook {
    private static final int VERSION = 1;

    private int cellX0, cellY0, width, height;
    private double startHours;
    private int[] baseTarget = new int[0];
    private double[] lastRespawn = new double[0];
    private boolean[] everLoaded = new boolean[0];
    private final Map<Long, Double> lastSeen = new HashMap<>();
    private final Map<Long, Double> lastRefill = new HashMap<>();

    /** Per cell, the sum of vanilla's random chunk targets (T); randBelow(k) returns 0..k-1. */
    public void init(MapSource map, PopulationSettings s, IntUnaryOperator randBelow, double startHours) {
        cellX0 = Math.floorDiv(map.minChunkX(), 32);
        cellY0 = Math.floorDiv(map.minChunkY(), 32);
        width = Math.floorDiv(map.maxChunkX(), 32) - cellX0 + 1;
        height = Math.floorDiv(map.maxChunkY(), 32) - cellY0 + 1;
        this.startHours = startHours;
        baseTarget = new int[width * height];
        lastRespawn = new double[width * height];
        everLoaded = new boolean[width * height];
        Arrays.fill(lastRespawn, startHours);
        lastSeen.clear();
        lastRefill.clear();
        for (int cy = 0; cy < height; cy++) {
            for (int cx = 0; cx < width; cx++) {
                int t = 0;
                for (int i = 0; i < 1024; i++) {
                    t += Formula.chunkTarget(map.intensity((cellX0 + cx) * 32 + i % 32, (cellY0 + cy) * 32 + i / 32), s,
                        () -> randBelow.applyAsInt(100));
                }
                baseTarget[cy * width + cx] = t;
            }
        }
    }

    public boolean initialised() { return width > 0; }

    public int cellX0() { return cellX0; }

    public int cellY0() { return cellY0; }

    public int widthInCells() { return width; }

    public int heightInCells() { return height; }

    public double startHours() { return startHours; }

    private int index(int cellX, int cellY) {
        int ix = cellX - cellX0, iy = cellY - cellY0;
        return ix < 0 || iy < 0 || ix >= width || iy >= height ? -1 : iy * width + ix;
    }

    static long key(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    public int baseTarget(int cellX, int cellY) {
        int i = index(cellX, cellY);
        return i < 0 ? 0 : baseTarget[i];
    }

    public boolean everLoaded(int cellX, int cellY) {
        int i = index(cellX, cellY);
        return i >= 0 && everLoaded[i];
    }

    public double lastRespawn(int cellX, int cellY) {
        int i = index(cellX, cellY);
        return i < 0 ? startHours : lastRespawn[i];
    }

    void setLastRespawn(int cellX, int cellY, double hours) {
        int i = index(cellX, cellY);
        if (i >= 0) lastRespawn[i] = hours;
    }

    /** A player has this chunk loaded now: its cell counts as visited from now on. */
    public void seen(int chunkX, int chunkY, double hours) {
        lastSeen.put(key(chunkX, chunkY), hours);
        int i = index(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkY, 32));
        if (i >= 0) everLoaded[i] = true;
    }

    /** When a player last saw this chunk; negative infinity if never. */
    public double lastSeen(int chunkX, int chunkY) {
        Double h = lastSeen.get(key(chunkX, chunkY));
        return h == null ? Double.NEGATIVE_INFINITY : h;
    }

    double lastRefill(int chunkX, int chunkY) {
        Double h = lastRefill.get(key(chunkX, chunkY));
        return h == null ? Double.NEGATIVE_INFINITY : h;
    }

    void refilled(int chunkX, int chunkY, double hours) { lastRefill.put(key(chunkX, chunkY), hours); }

    public byte[] save() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + width * height * 13 + (lastSeen.size() + lastRefill.size()) * 16);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeInt(cellX0);
            out.writeInt(cellY0);
            out.writeInt(width);
            out.writeInt(height);
            out.writeDouble(startHours);
            for (int i = 0; i < width * height; i++) {
                out.writeInt(baseTarget[i]);
                out.writeDouble(lastRespawn[i]);
                out.writeBoolean(everLoaded[i]);
            }
            writeMap(out, lastSeen);
            writeMap(out, lastRefill);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeMap(DataOutputStream out, Map<Long, Double> m) throws IOException {
        out.writeInt(m.size());
        for (Map.Entry<Long, Double> e : m.entrySet()) {
            out.writeLong(e.getKey());
            out.writeDouble(e.getValue());
        }
    }

    /** An empty book for an empty blob; throws IllegalArgumentException for an unreadable one. */
    public static CellBook load(byte[] data) {
        CellBook b = new CellBook();
        if (data == null || data.length == 0) return b;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int version = in.readInt();
            if (version != VERSION) throw new IllegalArgumentException("unknown population data version " + version);
            b.cellX0 = in.readInt();
            b.cellY0 = in.readInt();
            b.width = in.readInt();
            b.height = in.readInt();
            if (b.width < 0 || b.height < 0 || (long) b.width * b.height > 1_000_000) throw new IllegalArgumentException("bad size");
            b.startHours = in.readDouble();
            int n = b.width * b.height;
            b.baseTarget = new int[n];
            b.lastRespawn = new double[n];
            b.everLoaded = new boolean[n];
            for (int i = 0; i < n; i++) {
                b.baseTarget[i] = in.readInt();
                b.lastRespawn[i] = in.readDouble();
                b.everLoaded[i] = in.readBoolean();
            }
            readMap(in, b.lastSeen);
            readMap(in, b.lastRefill);
            return b;
        } catch (IOException e) {
            throw new IllegalArgumentException("population data truncated", e);
        }
    }

    private static void readMap(DataInputStream in, Map<Long, Double> m) throws IOException {
        int n = in.readInt();
        if (n < 0) throw new IOException("negative size");
        for (int i = 0; i < n; i++) m.put(in.readLong(), in.readDouble());
    }
}
