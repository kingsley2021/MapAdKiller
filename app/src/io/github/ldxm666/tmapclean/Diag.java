package io.github.ldxm666.tmapclean;

import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Coalesced diagnostics. No formatting or broadcast IPC on a layout/binding thread. */
final class Diag {
    private static final Object LOCK = new Object();
    private static final Map<String, int[]> COUNTS = new LinkedHashMap<String, int[]>();
    private static final Map<String, String> NOTES = new LinkedHashMap<String, String>();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "TMapClean-report");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static boolean dirty;
    private static boolean pending;
    private static long lastAt;

    private Diag() {}

    static void count(String key, int total, int hidden) {
        synchronized (LOCK) {
            int[] count = COUNTS.get(key);
            if (count != null && count[0] == total && count[1] == hidden) return;
            if (count == null) COUNTS.put(key, new int[]{total, hidden});
            else { count[0] = total; count[1] = hidden; }
            changedLocked();
        }
    }

    static void note(String key, String value) {
        if (value == null) return;
        synchronized (LOCK) {
            if (value.equals(NOTES.get(key))) return;
            NOTES.put(key, value);
            changedLocked();
        }
    }

    // A hit confirms a path is alive; per-call counters add no useful status information.
    static void hit(String key) {
        synchronized (LOCK) {
            if (COUNTS.containsKey(key)) return;
            COUNTS.put(key, new int[]{1, 0});
            changedLocked();
        }
    }

    private static void changedLocked() {
        dirty = true;
        if (pending) return;
        pending = true;
        long delay = Math.max(400L, 1600L - (SystemClock.elapsedRealtime() - lastAt));
        WORKER.schedule(new Runnable() {
            @Override public void run() { emit(); }
        }, delay, TimeUnit.MILLISECONDS);
    }

    static void flush() {
        synchronized (LOCK) {
            if (dirty) changedLocked();
        }
    }

    private static void emit() {
        String detail;
        synchronized (LOCK) {
            pending = false;
            if (!dirty) return;
            dirty = false;
            StringBuilder out = new StringBuilder(256);
            for (Map.Entry<String, int[]> entry : COUNTS.entrySet()) {
                if (out.length() > 0) out.append(" | ");
                out.append(entry.getKey()).append(' ').append(entry.getValue()[0])
                        .append('/').append(entry.getValue()[1]);
            }
            for (Map.Entry<String, String> entry : NOTES.entrySet()) {
                if (out.length() > 0) out.append(" | ");
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
            detail = out.toString();
            lastAt = SystemClock.elapsedRealtime();
        }
        if (detail.length() > 0) Report.send(null, "scan", detail, false);
    }
}
