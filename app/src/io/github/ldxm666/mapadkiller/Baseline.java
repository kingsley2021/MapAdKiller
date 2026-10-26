package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 官方基线抓取（v2.2.5）。
 *
 * <p><b>为什么需要它</b>：本模块此前在没拿到官方原样数据的情况下猜 payload schema
 * （{@code split}/{@code margin}/{@code radius}/… 全猜错），把高德本来正常的
 * {@code home_and_company_data} 挡掉，导致「设置家」一行消失。规则改成：
 * <b>先有官方基线，再谈改动</b>。
 *
 * <p><b>行为</b>：模块刚安装 / 刚升级（没有当前 versionCode 的基线标记）后的
 * <b>第一次宿主启动</b>，{@link #maybeCapture()} 返回 {@code true}，{@code MainHook}
 * 据此在本进程内 <b>一个 hook 都不装</b>（等价于模块没装，高德跑纯官方）。后台线程
 * {@code mak-baseline} 等首页稳定后，把宿主 {@code shared_prefs/} 原样复制到
 * {@code <宿主 filesDir>/baseline/<模块 versionName>/shared_prefs/}，写 {@code meta.json}
 * 与标记 {@code baseline_captured_v<versionCode>=true}。<b>下一次启动起 hook 才生效</b>。
 *
 * <p><b>标记存哪</b>：LSPosed 的 RemotePreferences 在 hook 进程里是**只读实现**
 * （实测 {@code SharedPreferences.edit()} 抛 {@code UnsupportedOperationException:
 * Read only implementation}），所以标记落在 {@code <宿主 filesDir>/baseline/state.properties}
 * —— 模块自己管理的子树，**不是**宿主 shared_prefs。RemotePreferences 只用于面板
 * 「重新抓取」的单向信号（App 侧可写，hook 侧可读）。
 *
 * <p><b>硬约束</b>（实现上逐条对应）：
 * <ul>
 *   <li>宿主 {@code shared_prefs} <b>只读</b>：只 {@code FileInputStream}，从不
 *       {@code getSharedPreferences(...).edit()} 写宿主任何键；</li>
 *   <li>所有异常 {@code try/catch} 吞掉，只写 {@code baseline_failed_v<code>} 标记 +
 *       日志，<b>绝不抛出到宿主启动路径</b>；</li>
 *   <li>抓取全程在后台线程，主线程只做「读一个小 state 文件」这一件事；</li>
 *   <li>不依赖重启设备：LSPosed 下装完只需强停宿主再打开。</li>
 * </ul>
 *
 * <p><b>时机</b>：优先检测 {@code shared_prefs/main_page.xml} 的
 * {@code lastModified() >= 本次进程启动时刻}（说明这次启动真的把首页数据写盘了），
 * 命中后再等 {@link #SETTLE_MS} 让同批写入落完；兜底 {@link #FALLBACK_MS} 后无条件抓
 * （meta 里标 {@code "fallback": true}）。不能只看「文件存在」：老用户重装模块时该文件
 * 早就存在，会秒判成旧数据。
 */
public final class Baseline {

    /** 模块自己的 prefs group（LSPosed RemotePreferences）。面板侧用 XposedService 同 group 读写。 */
    public static final String PREF_GROUP = "mapadkiller_baseline";

    public static final String K_CAPTURED = "baseline_captured_v";
    public static final String K_FAILED = "baseline_failed_v";
    public static final String K_GAVE_UP = "baseline_gave_up_v";
    public static final String K_ATTEMPTS = "baseline_attempts_v";
    public static final String K_VERSION = "baseline_version";
    public static final String K_CODE = "baseline_code";
    public static final String K_CAPTURED_AT = "baseline_captured_at";
    public static final String K_PATH = "baseline_path";
    public static final String K_FILES = "baseline_files";
    public static final String K_FALLBACK = "baseline_fallback";
    public static final String K_FAIL_REASON = "baseline_failed_reason";
    /** 面板「重新抓取」信号（RemotePreferences，App 侧写入，hook 侧只读）。 */
    public static final String K_RECAPTURE_AT = "baseline_recapture_at";
    private static final String K_RECAPTURE_HANDLED = "recapture_handled_at";

    /** 基线根目录名（位于宿主 {@code getFilesDir()} 下）。 */
    public static final String DIR = "baseline";
    private static final String STATE_FILE = "state.properties";

    /** 首页稳定兜底：进程启动后最多等这么久就无条件抓。 */
    private static final long FALLBACK_MS = 25_000L;
    /** 检测到 main_page.xml 本次启动被写过之后，再等这么久让同批 prefs 落盘。 */
    private static final long SETTLE_MS = 5_000L;
    /** 等宿主 Context 的上限（onPackageReady 时 Application 可能还没创建）。 */
    private static final long CONTEXT_WAIT_MS = 20_000L;
    /** 失败重试次数上限：超过就放弃基线（保证模块不会永远 passthrough）。 */
    private static final int MAX_ATTEMPTS = 5;
    /** 面板「开始抓取」写入的采集模式标记（宿主机 files/baseline/ 下，与 CaptureGate 同名）。 */
    private static final String F_CAPTURE_MODE = "capture.mode";
    /** 抓到基线后落的空文件，供面板判断「已就绪」。 */
    private static final String F_CAPTURE_DONE = "captured.marker";

    /** 关键单文件：除了整目录复制，再单独存一份到 {@code <baseline>/<ver>/key/}。 */
    private static final String[] KEY_FILES = {
            "main_page.xml",
            "toolbox.xml",
            "sync_time_file.xml",
            "cloudconfig_aocs_sp_key_value.xml",
    };

    /** 本次进程是否处于「抓取模式」＝ 所有 hook 必须透传。 */
    private static volatile boolean sCapturing;
    /** 每个进程只判定一次（onPackageReady 可能被调用多次）。 */
    private static volatile boolean sDecided;

    private Baseline() {}

    /** 所有 hook 安装处的总闸门：true ⇒ 本进程不装任何 hook（纯官方）。 */
    public static boolean isCapturing() {
        return sCapturing;
    }

    /**
     * 在 {@code mapadkiller.MainHook.onPackageReady} 的**最早期**调用（同步，只读标记）。
     *
     * @return true = 本次启动是「基线抓取启动」，调用方必须直接 return、不装任何 hook。
     */
    public static synchronized boolean maybeCapture() {
        if (sDecided) return sCapturing;
        sDecided = true;
        try {
            final String version = moduleVersionName();
            final int code = moduleVersionCode();
            if (!baselineEnabled()) {
                H.log(Log.INFO, TAG, "event=baseline_skip reason=disabled");
                sCapturing = false;
                return false;
            }
            Properties state = readState();
            if (state == null) {
                H.log(Log.WARN, TAG, "event=baseline_skip reason=no_state_store version=" + version);
                sCapturing = false;
                return false;
            }
            final File stateFile = stateFile();
            H.log(Log.INFO, TAG, "event=baseline_state path=" + (stateFile == null ? "null" : stateFile.getAbsolutePath())
                    + " exists=" + (stateFile != null && stateFile.isFile()));
            final long recaptureAt = remoteRecaptureAt();
            final long handledAt = parseLong(state.getProperty(K_RECAPTURE_HANDLED), 0L);
            final boolean captured = "true".equals(state.getProperty(K_CAPTURED + code));

            if (captured && handledAt >= recaptureAt) {
                H.log(Log.INFO, TAG, "event=baseline_skip reason=captured v=" + version
                        + " code=" + code + " files=" + state.getProperty(K_FILES, "0"));
                sCapturing = false;
                return false;
            }
            // 面板「开始抓取官方数据」写入的显式采集模式（files/baseline/capture.mode）：
            // 用户主动点了就一直抓到成功为止，不受 attempt 次数限制。
            final File modeFile = captureModeFile();
            if (!captured && modeFile != null && modeFile.isFile()) {
                if (isMainProcess()) {
                    state.setProperty(K_ATTEMPTS + code, "0");
                    saveState(state);
                    startWorker(version, code, recaptureAt);
                }
                sCapturing = true;
                H.log(Log.INFO, TAG, "event=baseline_mode on reason=user_capture version=" + version
                        + " code=" + code + " process=" + processName());
                return true;
            }

            // 子进程一律跟随主进程：没基线就一起透传（否则主进程纯官方、子进程却挂了 hook）。
            // 子进程不写 .attempt、也不做 force_release 判定，避免把主进程「本次启动刚写」的标记
            // 误判成「上次已 passthrough」。
            if (!captured && !isMainProcess()) {
                sCapturing = true;
                H.log(Log.INFO, TAG, "event=baseline_passthrough process=" + processName() + " mode=subprocess");
                return true;
            }
            // v2.2.6：删掉「passthrough 只允许一次」的强制放行 —— 真机上第二次启动就带着空基线
            // 把 hook 全装上，工具宫格图标整片不显示。现在**没抓到基线就一直跑纯官方**，
            // 安全阀只剩 MAX_ATTEMPTS 次上限（抓不到才放行，绝不会把模块永久锁死）。
            final File attemptMark = attemptFile(code);
            if (!captured && attemptMark != null && attemptMark.isFile()) {
                H.log(Log.INFO, TAG, "event=baseline_passthrough reason=retry v=" + version
                        + " code=" + code);
            }
            if (!captured && "true".equals(state.getProperty(K_GAVE_UP + code))) {
                H.log(Log.INFO, TAG, "event=baseline_skip reason=give_up v=" + version + " code=" + code);
                sCapturing = false;
                return false;
            }
            final int attempts = (int) parseLong(state.getProperty(K_ATTEMPTS + code), 0L);
            if (attempts >= MAX_ATTEMPTS) {
                state.setProperty(K_GAVE_UP + code, "true");
                saveState(state);
                H.log(Log.WARN, TAG, "event=baseline_skip reason=max_attempts attempts=" + attempts);
                sCapturing = false;
                return false;
            }

            if (isMainProcess()) markAttempt(code);
            sCapturing = true;
            H.log(Log.INFO, TAG, "event=baseline_mode on version=" + version + " code=" + code
                    + " attempt=" + (attempts + 1) + " recapture=" + (recaptureAt > handledAt)
                    + " process=" + processName());
            // 只有主进程负责真正抓取；子进程（:locationservice 等）只需要跟着 passthrough。
            if (isMainProcess()) {
                startWorker(version, code, recaptureAt);
            } else {
                H.log(Log.INFO, TAG, "event=baseline_worker_skipped reason=subprocess process=" + processName());
            }
            return true;
        } catch (Throwable t) {
            // 任何异常都不能影响宿主启动
            sCapturing = false;
            H.log(Log.WARN, TAG, "event=baseline_mode_failed", t);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════ 后台抓取

    private static void startWorker(final String version, final int code, final long recaptureAt) {
        try {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    long t0 = System.currentTimeMillis();
                    try {
                        runCapture(version, code, recaptureAt, t0);
                    } catch (Throwable error) {
                        fail(version, code, error);
                    }
                }
            }, "mak-baseline");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            fail(version, code, t);
        }
    }

    private static void runCapture(String version, int code, long recaptureAt, long t0) throws Exception {
        H.log(Log.INFO, TAG, "event=baseline_capture_start version=" + version + " code=" + code
                + " process=" + processName());
        Context ctx = waitHostContext(CONTEXT_WAIT_MS);
        if (ctx == null) throw new IllegalStateException("no_host_context");

        File prefsDir = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
        File mainPage = new File(prefsDir, "main_page.xml");

        boolean homeSeen = false;
        while (System.currentTimeMillis() - t0 < FALLBACK_MS) {
            try {
                if (mainPage.isFile() && mainPage.lastModified() >= t0) {
                    homeSeen = true;
                    break;
                }
            } catch (Throwable ignored) { }
            sleep(1000L);
        }
        boolean fallback = false;
        if (homeSeen) {
            H.log(Log.INFO, TAG, "event=baseline_home_ready file=main_page.xml wait_ms="
                    + (System.currentTimeMillis() - t0));
            sleep(SETTLE_MS);
        } else {
            fallback = true;
            H.log(Log.INFO, TAG, "event=baseline_home_timeout fallback_ms=" + FALLBACK_MS);
        }

        int files = capture(ctx, version, code, prefsDir, fallback);
        markCaptured(version, code, files, fallback, recaptureAt);
        H.log(Log.INFO, TAG, "event=baseline_capture_done files=" + files
                + " version=" + version + " mode=" + (fallback ? "fallback" : "home")
                + " cost_ms=" + (System.currentTimeMillis() - t0));
    }

    /** 真正做复制；返回复制的 prefs 文件数。 */
    private static int capture(Context ctx, String version, int code, File srcPrefs, boolean fallback) throws Exception {
        File out = new File(new File(ctx.getFilesDir(), DIR), version);
        File outPrefs = new File(out, "shared_prefs");
        deleteRecursively(out);
        if (!outPrefs.isDirectory() && !outPrefs.mkdirs()) {
            throw new java.io.IOException("mkdirs_failed " + outPrefs);
        }

        int files = copyTree(srcPrefs, outPrefs);
        if (files <= 0) throw new java.io.IOException("no_files_copied src=" + srcPrefs);

        File keyDir = new File(out, "key");
        if (!keyDir.isDirectory()) keyDir.mkdirs();
        int keyFiles = 0;
        for (String name : KEY_FILES) {
            File s = new File(srcPrefs, name);
            if (!s.isFile()) continue;
            if (copyFile(s, new File(keyDir, name))) keyFiles++;
        }

        writeMeta(out, version, code, files, keyFiles, ctx, fallback);
        return files;
    }

    private static int copyTree(File srcDir, File dstDir) {
        int n = 0;
        try {
            File[] list = srcDir.listFiles();
            if (list == null) return 0;
            for (File s : list) {
                if (s == null) continue;
                if (s.isDirectory()) {
                    File sub = new File(dstDir, s.getName());
                    if (sub.isDirectory() || sub.mkdirs()) n += copyTree(s, sub);
                } else if (s.isFile()) {
                    if (copyFile(s, new File(dstDir, s.getName()))) n++;
                }
            }
        } catch (Throwable ignored) { }
        return n;
    }

    private static boolean copyFile(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void writeMeta(File out, String version, int code, int fileCount, int keyCount,
                                  Context ctx, boolean fallback) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("versionName", version);
            o.put("versionCode", code);
            o.put("capturedAt", System.currentTimeMillis());
            o.put("capturedAtText", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date()));
            o.put("fileCount", fileCount);
            o.put("fallback", fallback);
            o.put("keyFileCount", keyCount);
            try {
                o.put("hostPackage", ctx.getPackageName());
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                o.put("hostVersionName", pi.versionName);
                o.put("hostVersionCode", pi.versionCode);
            } catch (Throwable ignored) { }
            writeText(new File(out, "meta.json"), o.toString(2));
        } catch (Throwable t) {
            H.log(Log.WARN, TAG, "event=baseline_meta_failed", t);
        }
    }

    private static void markCaptured(String version, int code, int files, boolean fallback, long recaptureAt) {
        try {
            Properties p = readState();
            if (p == null) p = new Properties();
            p.setProperty(K_CAPTURED + code, "true");
            p.setProperty(K_VERSION, version);
            p.setProperty(K_CODE, String.valueOf(code));
            p.setProperty(K_CAPTURED_AT, String.valueOf(System.currentTimeMillis()));
            p.setProperty(K_PATH, "files/" + DIR + "/" + version);
            p.setProperty(K_FILES, String.valueOf(files));
            p.setProperty(K_FALLBACK, String.valueOf(fallback));
            p.setProperty(K_ATTEMPTS + code, "0");
            p.remove(K_FAILED + code);
            p.setProperty(K_RECAPTURE_HANDLED, String.valueOf(recaptureAt));
            saveState(p);
            // 面板靠这个空文件判断「已抓到」——写失败不影响抓取本身。
            // restart.pending 让冷启动这一次（hook 还没生效的会话）弹窗提示并自动重启，
            // 用户不用自己再手动杀一次进程。
            try {
                File done = captureDoneFile();
                if (done != null && (done.isFile() || done.createNewFile())) {
                    done.setLastModified(System.currentTimeMillis());
                }
                File dir = stateDir();
                if (dir != null) {
                    File pending = new File(dir, "restart.pending");
                    if (!pending.isFile()) pending.createNewFile();
                }
            } catch (Throwable ignored) { }
            // 尽力镜像到 RemotePreferences（只读实现会抛，忽略即可）
            try {
                SharedPreferences rp = remotePrefs();
                if (rp != null) {
                    rp.edit().putBoolean(K_CAPTURED + code, true)
                            .putString(K_VERSION, version)
                            .putInt(K_FILES, files)
                            .commit();
                }
            } catch (Throwable ignored) { }
        } catch (Throwable t) {
            File sf = stateFile();
            H.log(Log.WARN, TAG, "event=baseline_mark_failed path="
                    + (sf == null ? "null" : sf.getAbsolutePath()) + " err=" + t, t);
        }
    }

    private static void fail(String version, int code, Throwable error) {
        int attempts = 0;
        try {
            Properties p = readState();
            if (p != null) {
                attempts = (int) parseLong(p.getProperty(K_ATTEMPTS + code), 0L) + 1;
                p.setProperty(K_FAILED + code, "true");
                p.setProperty(K_ATTEMPTS + code, String.valueOf(attempts));
                p.setProperty(K_FAIL_REASON, error == null ? "unknown"
                        : (error.getClass().getSimpleName() + ": " + error.getMessage()));
                p.setProperty("baseline_failed_at", String.valueOf(System.currentTimeMillis()));
                if (attempts >= MAX_ATTEMPTS) p.setProperty(K_GAVE_UP + code, "true");
                saveState(p);
            }
        } catch (Throwable ignored) { }
        H.log(Log.WARN, TAG, "event=baseline_capture_failed version=" + version
                + " attempts=" + attempts + " reason=" + (error == null ? "unknown" : error), error);
    }

    // ══════════════════════════════════════════════════════════════════ 面板 API

    /**
     * 面板「重新抓取基线」：清掉当前模块版本的标记，下次宿主启动重抓。
     * 由模块 App 进程调用（{@link App#svc()} 已连上 LSPosed 服务时可用）。
     */
    public static boolean requestRecapture() {
        boolean ok = false;
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s != null) {
                SharedPreferences p = s.getRemotePreferences(PREF_GROUP);
                ok = p.edit().putLong(K_RECAPTURE_AT, System.currentTimeMillis()).commit();
            }
        } catch (Throwable ignored) { }
        // root 兜底：直接删掉宿主的 state 文件（LSPosed 用户必有 root）
        try {
            File st = new File(new File(new File("/data/data/com.autonavi.minimap"), "files"), DIR + "/" + STATE_FILE);
            File am = new File(st.getParentFile(), ".attempt_v" + moduleVersionCode());
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "rm -f '"
                    + st.getAbsolutePath() + "' '" + am.getAbsolutePath() + "'"});
            if (p.waitFor() == 0) ok = true;
        } catch (Throwable ignored) { }
        H.log(Log.INFO, TAG, "event=baseline_recapture_requested ok=" + ok);
        return ok;
    }

    /** 面板显示用：当前版本是否已抓到基线（hook 侧读 state 文件；App 侧走 root）。 */
    public static boolean isCaptured() {
        try {
            Properties p = readState();
            if (p != null) return "true".equals(p.getProperty(K_CAPTURED + moduleVersionCode()));
        } catch (Throwable ignored) { }
        try {
            File st = new File(new File(new File("/data/data/com.autonavi.minimap"), "files"), DIR + "/" + STATE_FILE);
            Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat '" + st.getAbsolutePath() + "'"});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()));
            String line;
            String key = K_CAPTURED + moduleVersionCode() + "=true";
            while ((line = r.readLine()) != null) if (line.trim().equals(key)) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 面板「导出基线」：把宿主 {@code files/baseline/} 打包到
     * {@code /sdcard/Download/MapAdKiller-baseline-<ver>-<ts>.zip}。
     *
     * <p>模块 App 与宿主不同 UID，正常读不到宿主 data 目录 ⇒ 走 {@code su}（LSPosed 用户必有 root）
     * 先把目录拷到 Download 临时目录，再在 App 进程内打 zip（Android 没有 zip 命令，tar 不能出 .zip）。
     * 内置版（同 UID）能直接读时走进程内直接打包。返回产物路径，失败返回 null。
     */
    public static String exportBaseline(Context appCtx) {
        String version = moduleVersionName();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        String name = "MapAdKiller-baseline-" + version + "-" + stamp + ".zip";
        File download = new File("/sdcard/Download");
        try {
            if (!download.isDirectory()) download = new File("/storage/emulated/0/Download");
            if (!download.isDirectory() && !download.mkdirs()) return null;
            File target = new File(download, name);
            // 1) 同 UID（内置版）能直接读宿主目录 ⇒ 进程内打 zip
            try {
                File base = new File(new File("/data/data/com.autonavi.minimap/files"), DIR);
                if (base.isDirectory() && base.canRead() && zipDir(base, target, DIR)) {
                    H.log(Log.INFO, TAG, "event=baseline_export_done mode=direct path=" + target.getAbsolutePath());
                    return target.getAbsolutePath();
                }
            } catch (Throwable ignored) { }
            // 2) root 兜底：先拷到 Download 临时目录，再打 zip
            File tmp = new File(download, ".mak-baseline-tmp-" + stamp);
            deleteRecursively(tmp);
            String cmd = "rm -rf '" + tmp.getAbsolutePath() + "' && cp -r /data/data/com.autonavi.minimap/files/"
                    + DIR + " '" + tmp.getAbsolutePath() + "'";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (p.waitFor() != 0 || !tmp.isDirectory()) return null;
            boolean ok = zipDir(tmp, target, DIR);
            deleteRecursively(tmp);
            if (ok) {
                H.log(Log.INFO, TAG, "event=baseline_export_done mode=root path=" + target.getAbsolutePath());
                return target.getAbsolutePath();
            }
            return null;
        } catch (Throwable t) {
            H.log(Log.WARN, TAG, "event=baseline_export_failed", t);
            return null;
        }
    }

    private static boolean zipDir(File srcDir, File zipFile, String rootName) {
        java.util.zip.ZipOutputStream zos = null;
        try {
            zos = new java.util.zip.ZipOutputStream(
                    new java.io.BufferedOutputStream(new FileOutputStream(zipFile)));
            boolean ok = zipInto(zos, srcDir, rootName);
            zos.finish();
            return ok;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(zos);
        }
    }

    private static boolean zipInto(java.util.zip.ZipOutputStream zos, File dir, String entryPrefix) {
        try {
            File[] list = dir.listFiles();
            if (list == null) return true;
            byte[] buf = new byte[8192];
            for (File f : list) {
                String entry = entryPrefix + "/" + f.getName();
                if (f.isDirectory()) {
                    if (!zipInto(zos, f, entry)) return false;
                } else if (f.isFile()) {
                    InputStream in = null;
                    try {
                        zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                        in = new FileInputStream(f);
                        int r;
                        while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                        zos.closeEntry();
                    } finally {
                        closeQuietly(in);
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════ 内部工具

    private static final String TAG = MainHook.TAG;

    /** RemotePreferences（hook 侧只读；面板侧可写）。拿不到返回 null。 */
    private static SharedPreferences remotePrefs() {
        try {
            io.github.libxposed.api.XposedModule m = H.module;
            if (m == null) return null;
            return m.getRemotePreferences(PREF_GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 面板请求的「重新抓取」时间戳；0 = 没请求过。 */
    private static long remoteRecaptureAt() {
        try {
            SharedPreferences p = remotePrefs();
            if (p == null) return 0L;
            return p.getLong(K_RECAPTURE_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 状态目录：{@code /data/data/<当前进程包>/files/baseline}。
     *
     * <p>onPackageReady 极早期 {@code ActivityThread.currentPackageName()} 可能拿不到，
     * 所以解析失败时回退到宿主包名（本闸门只对高德生效）。两条路径都试一遍取存在的那个。
     */
    private static File stateDir() {
        File a = stateDirFor(processPackage());
        if (a != null && a.exists()) return a;
        File b = stateDirFor(MainHook.PKG_AMAP);
        return a != null ? a : b;
    }

    private static File stateDirFor(String pkg) {
        if (pkg == null || pkg.length() == 0) return null;
        return new File("/data/data/" + pkg + "/files/" + DIR);
    }

    private static File stateFile() {
        File dir = stateDir();
        return dir == null ? null : new File(dir, STATE_FILE);
    }

    private static File attemptFile(int code) {
        File dir = stateDir();
        return dir == null ? null : new File(dir, ".attempt_v" + code);
    }

    /** 采集模式标记文件：{@code files/baseline/capture.mode}（面板写，这里只读）。 */
    private static File captureModeFile() {
        File dir = stateDir();
        return dir == null ? null : new File(dir, F_CAPTURE_MODE);
    }

    /** 基线已就绪标记：{@code files/baseline/captured.marker}（这里写，面板只读）。 */
    private static File captureDoneFile() {
        File dir = stateDir();
        return dir == null ? null : new File(dir, F_CAPTURE_DONE);
    }

    /** 同步写一个「已 passthrough 过一次」的小标记（失败也无所谓，内存标志仍在）。 */
    private static void markAttempt(int code) {
        try {
            File dir = stateDir();
            File f = attemptFile(code);
            if (dir == null || f == null) return;
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            FileOutputStream out = new FileOutputStream(f);
            out.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8"));
            out.flush();
            closeQuietly(out);
        } catch (Throwable t) {
            H.log(Log.WARN, TAG, "event=baseline_attempt_mark_failed err=" + t);
        }
    }

    /** 总开关（RemotePreferences，面板可写）：false ⇒ 完全不抓基线、hook 正常生效。 */
    private static boolean baselineEnabled() {
        try {
            SharedPreferences p = remotePrefs();
            return p == null || p.getBoolean("baseline_enabled", true);
        } catch (Throwable t) {
            return true;
        }
    }

    private static Properties readState() {
        File f = stateFile();
        if (f == null) return null;
        Properties p = new Properties();
        if (!f.isFile()) return p;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
            return p;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void saveState(Properties p) {
        File f = stateFile();
        if (f == null) return;
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            p.store(out, "MapAdKiller baseline state");
            out.flush();
            closeQuietly(out);
            out = null;
            if (!tmp.renameTo(f)) {
                // rename 失败就退回直接写
                out = new FileOutputStream(f);
                p.store(out, "MapAdKiller baseline state");
                out.flush();
            }
        } catch (Throwable t) {
            H.log(Log.WARN, TAG, "event=baseline_state_write_failed path=" + f.getAbsolutePath()
                    + " err=" + t);
        } finally {
            closeQuietly(out);
            if (tmp.exists()) tmp.delete();
        }
    }

    private static String moduleVersionName() {
        try {
            Class<?> v = Class.forName("io.github.ldxm666.mapclean.Version");
            Object n = v.getField("NAME").get(null);
            if (n instanceof String && ((String) n).length() > 0) return (String) n;
        } catch (Throwable ignored) { }
        return "unknown";
    }

    private static int moduleVersionCode() {
        try {
            Class<?> v = Class.forName("io.github.ldxm666.mapclean.Version");
            return v.getField("CODE").getInt(null);
        } catch (Throwable ignored) { }
        return 0;
    }

    static String processName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object v = at.getMethod("currentProcessName").invoke(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) return android.app.Application.getProcessName();
        } catch (Throwable ignored) { }
        return null;
    }

    private static String processPackage() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object v = at.getMethod("currentPackageName").invoke(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) { }
        String name = processName();
        if (name != null) {
            int i = name.indexOf(':');
            return i > 0 ? name.substring(0, i) : name;
        }
        return null;
    }

    /** 本进程启动时刻（wall clock，毫秒）。 */
    private static long processStartWallTime() {
        try {
            return System.currentTimeMillis()
                    - (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean isMainProcess() {
        String name = processName();
        return name == null || name.indexOf(':') < 0;
    }

    private static Context waitHostContext(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                Context c = Config.hostCtx();
                if (c != null) return c;
            } catch (Throwable ignored) { }
            sleep(500L);
        }
        try {
            return Config.hostCtx();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteRecursively(File f) {
        try {
            if (f == null || !f.exists()) return;
            File[] list = f.listFiles();
            if (list != null) for (File c : list) deleteRecursively(c);
            f.delete();
        } catch (Throwable ignored) { }
    }

    private static void writeText(File f, String text) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(Object o) {
        try {
            if (o instanceof InputStream) ((InputStream) o).close();
            else if (o instanceof OutputStream) ((OutputStream) o).close();
        } catch (Throwable ignored) { }
    }

    /** 调试用：列出当前基线目录。 */
    static List<String> listBaselineDirs(Context ctx) {
        List<String> out = new ArrayList<String>();
        try {
            File base = new File(ctx.getFilesDir(), DIR);
            File[] list = base.listFiles();
            if (list != null) for (File f : list) if (f.isDirectory()) out.add(f.getName());
        } catch (Throwable ignored) { }
        return out;
    }
}