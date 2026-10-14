package truehordes.core;

import java.nio.file.Path;
import truehordes.save.SaveFile;
import truehordes.util.Log;

/**
 * Writes encoded saves on its own daemon thread, so the game's main thread only pays for the in-memory encode. A newer
 * save replaces one not yet written. {@link #flush} waits until everything submitted is on disk (full saves, quitting).
 */
final class SaveWriter {
    private final Object mon = new Object();
    private Path pendingFile;
    private byte[] pending;
    private boolean busy;
    private boolean stopped;
    private Thread thread;
    /** The thread that wrote the last file (tests check it is not the caller's). */
    static volatile String lastWriterThread = "";

    void submit(Path file, byte[] data) {
        synchronized (mon) {
            pendingFile = file;
            pending = data;
            if (thread == null || !thread.isAlive()) {
                stopped = false;
                thread = new Thread(this::loop, "TrueHordes-save");
                thread.setDaemon(true);
                thread.start();
            }
            mon.notifyAll();
        }
    }

    /** Waits up to {@code millis} for submitted saves to be written. Returns true when nothing is left to write. */
    boolean flush(long millis) {
        long end = System.currentTimeMillis() + millis;
        synchronized (mon) {
            while (pending != null || busy) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    mon.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Writes what is pending, then ends the thread. */
    void stop(long millis) {
        flush(millis);
        synchronized (mon) {
            stopped = true;
            mon.notifyAll();
        }
    }

    private void loop() {
        while (true) {
            Path file;
            byte[] data;
            synchronized (mon) {
                while (pending == null && !stopped) {
                    try {
                        mon.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (pending == null) return;
                file = pendingFile;
                data = pending;
                pending = null;
                busy = true;
            }
            try {
                SaveFile.writeBytes(file, data);
                lastWriterThread = Thread.currentThread().getName();
            } catch (Throwable t) {
                Log.error("could not write the mod's save file " + file, t);
            } finally {
                synchronized (mon) {
                    busy = false;
                    mon.notifyAll();
                }
            }
        }
    }
}
