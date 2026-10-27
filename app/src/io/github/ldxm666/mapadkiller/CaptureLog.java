package io.github.ldxm666.mapadkiller;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 主动抓包（诊断用）。两种取法，都落一个 txt 到「内部存储/Download/MapClean/」——
 * 文件管理器点开「下载」就能看到，不用找 Android/data。
 *
 * <ul>
 *   <li><b>前 10 分钟</b>：立刻把内存里已经攒着的最近 10 分钟事件导出来，秒出文件；</li>
 *   <li><b>后 10 分钟</b>：从现在开始录 10 分钟（每 5 秒一条状态 + 全部模块事件），
 *       结束自动落盘，中途再点一次可提前结束。</li>
 * </ul>
 *
 * <h3>为什么在模块进程里做</h3>
 * <ul>
 *   <li>普通 App 读不到 logcat（READ_LOGS 是系统权限），但模块自己的每一条日志都经过
 *       {@link H#log(int, String, String)}，在这里顺手抄一份内存环形缓冲即可；</li>
 *   <li>高德没有存储权限、Android 11+ 又不让直接写 /sdcard，所以走 MediaStore 的
 *       「下载」集合：不需要任何权限，写出来的文件在文件管理器里可见；</li>
 *   <li>全程 try/catch，任何异常都只丢一条日志，绝不影响宿主。</li>
 * </ul>
 *
 * <h3>输出内容</h3>
 * <ol>
 *   <li>头信息：机型 / 系统 / 包名 / 模块版本 / 抓包窗口；</li>
 *   <li>配置快照（开始 + 结束各一份，diff 出这段时间里被改过的开关）；</li>
 *   <li>宿主 {@code shared_prefs} 文件名+大小+时间戳指纹（开始/结束对比，能看出宿主写了哪些 xml）；</li>
 *   <li>窗口内事件；「前 10 分钟」就是环形缓冲里的历史。</li>
 * </ol>
 */
public final class CaptureLog {

    private CaptureLog() {}

    /** 一次抓包的时长：10 分钟。 */
    static final long DURATION_MS = 10 * 60 * 1000L;
    /** 后台环形缓冲：无论有没有在抓包都留着最近 10 分钟。 */
    private static final long RING_MS = 10 * 60 * 1000L;
    private static final int RING_MAX = 6000;
    private static final long SAMPLE_MS = 5000L;

    private static final Object LOCK = new Object();
    /** 每条都带 epoch 毫秒前缀：「1700000000123|11:33:20.123|I|tag|msg」。 */
    private static final ArrayDeque<String> RING = new ArrayDeque<>();

    private static volatile boolean live;
    private static Context appCtx;
    private static long startAt;
    private static long endAt;
    private static long lastFlush;
    private static File workFile;
    private static String liveName = "";
    private static final StringBuilder BODY = new StringBuilder(64 * 1024);
    private static Map<String, String> cfgStart;
    private static Map<String, String> prefsStart;
    private static int lines;

    // ── 对外接口 ────────────────────────────────────────────────────────────────

    /** 「后 10 分钟」是否正在录。 */
    public static boolean running() { return live; }

    /** 剩余秒数（没在录时为 0）。 */
    public static int remainSeconds() {
        if (!live) return 0;
        long left = endAt - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000L);
    }

    /** 当前正在写的 txt 名（没在录时为空）。 */
    public static String currentName() { return liveName; }

    /**
     * 「后 10 分钟」按钮：没在录就开始，已在录就结束（3 秒内的重复点算误触）。
     * @return true = 现在正在录，false = 已结束
     */
    public static boolean toggleLive(Context ctx) {
        if (live) {
            if (System.currentTimeMillis() - startAt < 3000L) return true;
            stopLive(ctx, "manual");
            return false;
        }
        startLive(ctx);
        return true;
    }

    /** 「前 10 分钟」按钮：立刻把环形缓冲导成 txt，返回展示路径（失败返回 null）。 */
    public static String dumpRecent(Context ctx) {
        try {
            appCtx = ctx == null ? null : ctx.getApplicationContext();
            long now = System.currentTimeMillis();
            String name = "mapclean_last10m_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now)) + ".txt";
            StringBuilder b = new StringBuilder(64 * 1024);
            b.append("===== MapAdKiller 抓包：最近 10 分钟 =====\n");
            b.append("file=").append(name).append('\n');
            b.append("kind=前10分钟（点按钮之前的环形缓冲）\n");
            b.append("end=").append(ts(now)).append('\n');
            b.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" android=").append(Build.VERSION.RELEASE)
                    .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
            b.append("pkg=").append(ctx == null ? "?" : ctx.getPackageName())
                    .append(" module=").append(moduleVersion()).append('\n');
            b.append("cfg_now:\n");
            for (Map.Entry<String, String> e : cfgSnapshot().entrySet()) {
                b.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            b.append("shared_prefs_now: ").append(prefsSnapshot(ctx).size()).append(" 个 xml\n\n");
            b.append("===== 事件 =====\n");
            int n = 0;
            for (String rec : ringCopy()) { b.append(pretty(rec)).append('\n'); n++; }
            b.append("\n===== 结束 =====\n");
            b.append("source=ring events=").append(n).append('\n');
            String path = publish(ctx, name, b.toString());
            H.log(Log.INFO, MainHook.TAG, "event=capture_dump kind=last10m events=" + n
                    + " path=" + path);
            return path;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_dump_fail err=" + t);
            return null;
        }
    }

    /** 首页「运行日志」面板用：内存环形缓冲里最近 max 条事件（时间升序，已排版）。 */
    public static String recentText(int max) {
        StringBuilder b = new StringBuilder(32 * 1024);
        ArrayDeque<String> copy = ringCopy();
        int skip = Math.max(0, copy.size() - Math.max(1, max));
        int i = 0;
        for (String rec : copy) {
            if (i++ < skip) continue;
            b.append(pretty(rec)).append('\n');
        }
        return b.toString();
    }

    /** 首页「运行日志」面板用：环形缓冲当前条数（0 = 本进程还没有模块事件）。 */
    public static int recentCount() {
        synchronized (RING) { return RING.size(); }
    }

    /** 由 {@link H#log(int, String, String)} 每条日志调用；不在录时只维护环形缓冲。 */
    public static void note(int priority, String tag, String msg) {
        try {
            long now = System.currentTimeMillis();
            // 协议体/URL 可能是几千字的巨行，txt 里留个头就够了，否则一份日志全是乱码长串
            if (msg != null && msg.length() > 400) {
                msg = msg.substring(0, 400) + "…(len=" + msg.length() + ")";
            }
            String rec = now + "|" + ts(now) + "|" + prio(priority) + "|"
                    + (tag == null ? "" : tag) + "|" + msg;
            synchronized (RING) {
                RING.addLast(rec);
                while (RING.size() > RING_MAX) RING.removeFirst();
                long cut = now - RING_MS;
                while (!RING.isEmpty()) {
                    String h = RING.peekFirst();
                    int bar = h == null ? -1 : h.indexOf('|');
                    long t = -1;
                    try { t = Long.parseLong(h.substring(0, bar)); } catch (Throwable ignored) {}
                    if (t > 0 && t < cut) RING.removeFirst(); else break;
                }
            }
            if (!live) return;
            synchronized (LOCK) { append(pretty(rec)); }
            if (now - lastFlush >= 2000L) flush();
        } catch (Throwable ignored) {}
    }

    // ── 后 10 分钟 ──────────────────────────────────────────────────────────────

    private static void startLive(Context ctx) {
        try {
            appCtx = ctx == null ? null : ctx.getApplicationContext();
            synchronized (LOCK) {
                BODY.setLength(0);
                lines = 0;
                startAt = System.currentTimeMillis();
                endAt = startAt + DURATION_MS;
                cfgStart = cfgSnapshot();
                prefsStart = prefsSnapshot(ctx);
                liveName = "mapclean_capture_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startAt))
                        + ".txt";
                workFile = workFile(ctx, liveName);
                append("===== MapAdKiller 抓包：后 10 分钟 =====");
                append("file=" + liveName);
                append("window=从现在开始 10 分钟 start=" + ts(startAt) + " end=" + ts(endAt));
                append("device=" + Build.MANUFACTURER + " " + Build.MODEL
                        + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT);
                append("pkg=" + (ctx == null ? "?" : ctx.getPackageName())
                        + " module=" + moduleVersion());
                append("cfg_at_start:");
                for (Map.Entry<String, String> e : cfgStart.entrySet()) {
                    append("  " + e.getKey() + "=" + e.getValue());
                }
                append("shared_prefs_at_start: " + prefsStart.size() + " 个 xml（变化在结尾 diff）");
                append("");
                append("===== 事件 =====");
                live = true;
                flush();
            }
            H.log(Log.INFO, MainHook.TAG, "event=capture_start file=" + liveName
                    + " dur_ms=" + DURATION_MS);
            HandlerHolder.post(new Runnable() {
                @Override public void run() { sample(); }
            }, 0L);
        } catch (Throwable t) {
            live = false;
            H.log(Log.WARN, MainHook.TAG, "event=capture_start_fail err=" + t);
        }
    }

    private static void stopLive(Context ctx, String reason) {
        try {
            boolean was;
            synchronized (LOCK) {
                was = live;
                live = false;
            }
            if (!was) return;
            String cfgDiff = diff(cfgStart, cfgSnapshot());
            String prefsDiff = diff(prefsStart, prefsSnapshot(ctx));
            String text;
            synchronized (LOCK) {
                append("");
                append("===== 结束 =====");
                append("reason=" + reason + " end=" + ts(System.currentTimeMillis()));
                append("cfg_changed:" + (cfgDiff.isEmpty() ? " (无)" : ""));
                if (!cfgDiff.isEmpty()) for (String s : cfgDiff.split("\n")) append("  " + s);
                append("shared_prefs_changed:" + (prefsDiff.isEmpty() ? " (无)" : ""));
                if (!prefsDiff.isEmpty()) for (String s : prefsDiff.split("\n")) append("  " + s);
                append("lines=" + lines);
                text = BODY.toString();
            }
            String path = publish(ctx, liveName, text);
            H.log(Log.INFO, MainHook.TAG, "event=capture_done reason=" + reason
                    + " file=" + liveName + " path=" + path + " lines=" + lines);
            toast("抓包完成：" + path);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_done_fail err=" + t);
        }
    }

    // ── 采样 ────────────────────────────────────────────────────────────────────

    private static void sample() {
        try {
            if (!live) return;
            H.log(Log.INFO, MainHook.TAG, "event=capture_sample " + AmapCruise.diag());
            flush();
            if (System.currentTimeMillis() >= endAt) { stopLive(appCtx, "timeout"); return; }
            HandlerHolder.post(new Runnable() {
                @Override public void run() { sample(); }
            }, SAMPLE_MS);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_sample_fail err=" + t);
        }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────────

    /**
     * 把内容发布成用户找得到的文件。顺序：
     * 1) MediaStore「下载」集合 → 内部存储/Download/MapClean/（API 29+，免权限，文件管理器可见）
     * 2) 直接写 /sdcard/Download/MapClean/（有全盘权限时）
     * 3) App 自己的外部目录 Android/data/<pkg>/files/MapClean/（免权限）
     * 4) filesDir/capture（兜底）
     * @return 给用户看的路径（成功）或 null（全失败）
     */
    static String publish(Context ctx, String name, String text) {
        byte[] data = text.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        // 1) MediaStore Downloads
        try {
            if (Build.VERSION.SDK_INT >= 29 && ctx != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MapClean");
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(data);
                        os.close();
                        return "内部存储/Download/MapClean/" + name;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(MainHook.TAG, "event=capture_publish_mediastore_fail err=" + t);
        }
        // 2) 直接路径
        try {
            File f = new File("/sdcard/Download/MapClean", name);
            if (write(f, data)) return "内部存储/Download/MapClean/" + name;
        } catch (Throwable ignored) {}
        // 3) App 外部目录
        try {
            File ext = ctx == null ? null : ctx.getExternalFilesDir(null);
            if (ext != null) {
                File f = new File(new File(ext, "MapClean"), name);
                if (write(f, data)) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {}
        // 4) 兜底
        try {
            File f = new File(new File(ctx.getFilesDir(), "capture"), name);
            if (write(f, data)) return f.getAbsolutePath();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 录制过程中反复写的临时文件（App 自己的外部目录，免权限、可反复覆盖）。 */
    private static File workFile(Context ctx, String name) {
        try {
            File ext = ctx == null ? null : ctx.getExternalFilesDir(null);
            if (ext != null) {
                File d = new File(ext, "MapClean");
                if (d.isDirectory() || d.mkdirs()) return new File(d, name);
            }
        } catch (Throwable ignored) {}
        try {
            File d = new File(ctx.getFilesDir(), "capture");
            if (d.isDirectory() || d.mkdirs()) return new File(d, name);
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean write(File f, byte[] data) {
        FileOutputStream o = null;
        try {
            File p = f.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs()) return false;
            o = new FileOutputStream(f, false);
            o.write(data);
            o.flush();
            return true;
        } catch (Throwable t) {
            Log.w(MainHook.TAG, "event=capture_write_fail err=" + t);
            return false;
        } finally {
            if (o != null) try { o.close(); } catch (Throwable ignored) {}
        }
    }

    private static void append(String s) {
        BODY.append(s).append('\n');
        lines++;
    }

    private static void flush() {
        File f = workFile;
        if (f == null) return;
        lastFlush = System.currentTimeMillis();
        String text;
        synchronized (LOCK) { text = BODY.toString(); }
        // 这里不能走 H.log（会把这条再喂回 note，写盘失败时无限递归）
        write(f, text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    // ── 杂项 ────────────────────────────────────────────────────────────────────

    private static ArrayDeque<String> ringCopy() {
        synchronized (RING) { return new ArrayDeque<>(RING); }
    }

    private static String moduleVersion() {
        try { return io.github.ldxm666.mapclean.Version.NAME; } catch (Throwable ignored) {}
        return "2.2.6";
    }

    private static void toast(String text) {
        try {
            Context c = appCtx;
            if (c == null) { Log.i(MainHook.TAG, "event=capture_toast text=" + text); return; }
            android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private static Map<String, String> cfgSnapshot() {
        Map<String, String> m = new TreeMap<>();
        try {
            for (Map.Entry<String, ?> e : Config.snapshot().entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                if (k.startsWith("tool_id_") || k.startsWith(Config.K_TOOL_PREFIX)) continue;
                m.put(k, String.valueOf(e.getValue()));
            }
        } catch (Throwable ignored) {}
        return m;
    }

    private static Map<String, String> prefsSnapshot(Context ctx) {
        Map<String, String> m = new TreeMap<>();
        try {
            File dir = ctx == null ? null
                    : new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) return m;
            for (File f : fs) {
                if (f == null || !f.getName().endsWith(".xml")) continue;
                m.put(f.getName(), f.length() + "B@" + f.lastModified());
            }
        } catch (Throwable ignored) {}
        return m;
    }

    private static String diff(Map<String, String> a, Map<String, String> b) {
        StringBuilder s = new StringBuilder();
        try {
            if (a == null) a = new TreeMap<>();
            if (b == null) b = new TreeMap<>();
            TreeMap<String, String> keys = new TreeMap<>(a);
            for (String k : b.keySet()) keys.put(k, "");
            for (String k : keys.keySet()) {
                String old = a.get(k);
                String now = b.get(k);
                if (old == null && now != null) s.append(k).append(": (新增) ").append(now).append('\n');
                else if (old != null && now == null) s.append(k).append(": (删除) 原 ").append(old).append('\n');
                else if (old != null && !old.equals(now)) s.append(k).append(": ").append(old)
                        .append(" -> ").append(now).append('\n');
            }
        } catch (Throwable ignored) {}
        return s.toString().trim();
    }

    private static String pretty(String rec) {
        try {
            int a = rec.indexOf('|');
            int b = rec.indexOf('|', a + 1);
            int c = rec.indexOf('|', b + 1);
            int d = rec.indexOf('|', c + 1);
            return "[" + rec.substring(a + 1, b) + "] " + rec.substring(b + 1, c)
                    + " " + rec.substring(c + 1, d) + ": " + rec.substring(d + 1);
        } catch (Throwable t) {
            return rec;
        }
    }

    private static String prio(int p) {
        switch (p) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG: return "D";
            case Log.INFO: return "I";
            case Log.WARN: return "W";
            case Log.ERROR: return "E";
            default: return "A";
        }
    }

    private static String ts(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    /** 延时执行：优先主线程 Handler，拿不到时退回后台线程 sleep。 */
    private static final class HandlerHolder {
        private static android.os.Handler H;

        static void post(final Runnable r, final long delayMs) {
            try {
                if (H == null) H = new android.os.Handler(android.os.Looper.getMainLooper());
                H.postDelayed(r, delayMs);
                return;
            } catch (Throwable ignored) {}
            try {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(Math.max(0L, delayMs)); } catch (Throwable ignored) {}
                        r.run();
                    }
                }, "mapclean-capture").start();
            } catch (Throwable ignored) {}
        }
    }
}
