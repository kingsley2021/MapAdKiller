package io.github.ldxm666.mapclean;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 首次使用的「采集模式」标记（v2.2.6）。
 *
 * <p><b>为什么需要</b>：高德的首页卡片、金刚位、工具宫格全是**服务端下发**的。内置版装完后
 * 如果模块的 hook 一上来就生效，宿主手里还没有这批数据，界面上就是一排空壳
 * （真机反馈：工具宫格图标整片不显示）。所以第一次必须先让高德跑一遍纯官方，
 * 由 {@code mapadkiller.Baseline} 把官方 {@code shared_prefs} 原样抓下来，再启用模块。
 *
 * <p>标记就是宿主 {@code filesDir/baseline/} 下的两个空文件，面板与 hook 两侧都只认文件，
 * 不经过 SharedPreferences（宿主 prefs 只读，绝不写）：
 * <ul>
 *   <li>{@link #F_MODE} —— 采集模式开关（面板写，Baseline 读）；</li>
 *   <li>{@link #F_DONE} —— 基线已抓到（Baseline 写，面板读）。</li>
 * </ul>
 *
 * <p>内置版面板与宿主同进程同 UID，直接读写即可；模块版（LSPosed）没有面板，走不到这里。
 */
public final class CaptureGate {

    private static final String TAG = "MapCleanCapture";

    /** 与 {@code mapadkiller.Baseline.DIR} 保持一致。 */
    public static final String DIR = "baseline";
    public static final String F_MODE = "capture.mode";
    public static final String F_DONE = "captured.marker";

    private CaptureGate() {}

    private static File dir(Context c) {
        try {
            File d = new File(c.getFilesDir(), DIR);
            if (!d.isDirectory() && !d.mkdirs()) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 采集模式是否开着。 */
    public static boolean captureMode(Context c) {
        try {
            File d = dir(c);
            return d != null && new File(d, F_MODE).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 官方基线是否已经抓到。 */
    public static boolean captured(Context c) {
        try {
            File d = dir(c);
            return d != null && new File(d, F_DONE).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 抓取是否正在进行（Baseline 写的 files/baseline/capture.running 标记）。 */
    public static boolean capturing(Context c) {
        try {
            File f = file(c, "capture.running");
            return f != null && f.isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 上次抓到的配置文件数（state.properties 里的 baseline_files），读不到返回 0。 */
    public static int capturedFiles(Context c) {
        File d = dir(c);
        if (d == null) return 0;
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(new File(d, "state.properties"));
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            return Integer.parseInt(p.getProperty("baseline_files", "0").trim());
        } catch (Throwable t) {
            return 0;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 开/关采集模式。true=写入标记，false=删除标记。 */
    public static boolean setCaptureMode(Context c, boolean on) {
        try {
            File d = dir(c);
            if (d == null) return false;
            File f = new File(d, F_MODE);
            if (!on) return !f.exists() || f.delete();
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            // ★ 「重新抓取」必须先把上一轮的「已抓到 / 请重启」标记清掉：
            // 否则面板读到 mode&&done 就以为已经抓完，直接弹「启用模块功能？」并重启，
            // 真机表现就是「数据都没抓到就被重启了」（2026-10-27 复查确认）。
            File stale = new File(d, F_DONE);
            if (stale.isFile()) stale.delete();
            stale = new File(d, F_RESTART);
            if (stale.isFile()) stale.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 抓到基线后由 Baseline 落的「该重启一次」标记。 */
    public static final String F_RESTART = "restart.pending";

    private static boolean promptShown;
    private static boolean toastShown;
    private static int ticks;

    private static File file(Context c, String name) {
        File d = dir(c);
        return d == null ? null : new File(d, name);
    }

    /** 是否需要提示「抓到了，重启一下」。 */
    public static boolean restartPending(Context c) {
        try {
            File f = file(c, F_RESTART);
            return f != null && f.isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearRestartPending(Context c) {
        try {
            File f = file(c, F_RESTART);
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable ignored) { }
    }

    /**
     * 宿主每次 resume 时调一次：抓到基线就弹窗提示，并自动重启一次让 hook 生效。
     *
     * <p>抓取是在 {@code Baseline} 的后台线程里做的，通常比首个 Activity 的 resume 晚几秒，
     * 所以没看到标记时每 2 秒复查一次，最多 60 秒，之后不再打扰。
     */
    public static void tick(final android.app.Activity a) {
        if (a == null || promptShown || a.isFinishing()) return;
        // 采集模式下先给一次明确提示：用户刚装完打开高德，界面上看不到任何「模块」痕迹，
        // 不给提示就不知道「正在抓官方数据、可以正常用」。
        if (!toastShown) {
            try {
                if (captureMode(a) && !captured(a)) {
                    toastShown = true;
                    android.widget.Toast.makeText(a.getApplicationContext(),
                            "正在抓取官方数据，请正常用一次地图（首页 / 搜索 / 我的）",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            } catch (Throwable ignored) { }
        }
        if (!restartPending(a)) {
            if (ticks++ > 30) return;
            try {
                a.getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override public void run() { tick(a); }
                }, 2000L);
            } catch (Throwable ignored) { }
            return;
        }
        promptShown = true;
        // 只提示、不自动杀进程：抓取刚写完配置时用户可能还在浏览，自动重启会被当成「没抓到就重启」。
        // 提示走高德同款的底部小气泡（Toast），不弹大白框。
        final int files = capturedFiles(a);
        toast(a, "✓ 官方数据已抓取完成（" + files + " 项），点悬浮球 → 重启并启用模块");
        android.util.Log.i(TAG, "event=capture_done_toast files=" + files);
    }

    private static boolean recaptureChecked;

    /**
     * 模块版（LSPosed）专用：设置页点了「抓取云数据」之后，宿主进程下次 resume 时自己重启一次
     * 进入采集模式。
     *
     * <p>为什么必须有这一步：{@code Baseline.maybeCapture()} 只在**进程启动**时判定一次，
     * 而高德被拉起来时进程多半还活着，光把界面切到前台根本不会触发抓取 —— 用户看到的就是
     * 「点了抓取，什么都没发生」。这里读面板写下的请求（RemotePreferences 或 root 兜底文件），
     * 满足条件就写 {@code capture.mode} 再重启，下一次启动就是干净的一次采集。
     *
     * <p>安全阀：每个进程只判定一次；只认 10 分钟内的请求（避免很早以前的遗留请求在用户
     * 开车导航时突然把高德重启）。
     */
    public static void applyRecaptureRequest(android.app.Activity activity) {
        if (recaptureChecked || activity == null) return;
        recaptureChecked = true;
        try {
            final long at = io.github.ldxm666.mapadkiller.Baseline.pendingRecaptureAt();
            if (at <= 0L) return;
            if (System.currentTimeMillis() - at > 10L * 60L * 1000L) {
                android.util.Log.i(TAG, "event=recapture_request_stale at=" + at);
                return;
            }
            if (!io.github.ldxm666.mapadkiller.Baseline.hasPendingRecapture()) return;
            if (!setCaptureMode(activity, true)) return;
            android.util.Log.i(TAG, "event=recapture_request_applied at=" + at);
            // 这里**不**自杀重启：HyperOS/MIUI 的 Aurogon（应用冻结）对「短时间内被连续杀掉
            // 两次」的应用会拉黑一段时间，闹钟拉起会被静默丢掉 —— 真机上表现就是抓完数据
            // 高德再也没回来。改成让用户自己关掉再打开：一次流程只在高德进程内自杀一次
            // （抓取完成后的 Baseline.maybeAutoRestart），实测闹钟能把高德拉回来。
            toast(activity, "已请求抓取 · 请关掉高德再打开一次，即开始抓取");
            android.util.Log.i(TAG, "event=recapture_user_restart_hint at=" + at);
        } catch (Throwable ignored) { }
    }

    /** 底部小气泡提示（浅色系统 Toast，和高德自己的「再按一次退出」同款）。 */
    public static void toast(Context c, String text) {
        try {
            android.widget.Toast.makeText(c.getApplicationContext(), text,
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
    }

    /**
     * 重启宿主：先排一个 1.5 秒后的系统闹钟拉起启动页，再杀掉自己。
     * 没有 root 不能 {@code am force-stop}，进程死了闹钟还在系统里，起来就是干净的一次启动。
     * 采集模式标记由调用方自己清除（该标记只在抓取期间有意义）。
     */
    public static void restartHost(Context c) {
        try {
            final Context app = c.getApplicationContext();
            // 注意：这里不动 capture.mode —— 「开始抓取」那条路要带着标记重启，
            // 需要退出采集模式的调用方自己先 setCaptureMode(false)。
            clearRestartPending(app);
            android.content.Intent i = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int flags = android.app.PendingIntent.FLAG_CANCEL_CURRENT;
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                }
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(app, 0x4D43, i, flags);
                android.app.AlarmManager am =
                        (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                // 闹钟必须排在自杀**之后**：kill 在 +800ms，这里排 +2500ms。
                // 之前是 +1500ms，闹钟把启动页拉起来的却是还活着的旧进程，接着 kill 一到
                // 就把这个刚起来的窗口一起带走了 —— 真机上表现就是「高德直接没了」。
                if (am != null) {
                    long at = System.currentTimeMillis() + 2500L;
                    // setAlarmClock 在真机上抛 SecurityException（高德没有 SCHEDULE_EXACT_ALARM
                    // 权限），所以只用 setExactAndAllowWhileIdle，失败再退回 inexact。
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 23) {
                            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                        } else {
                            am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                        }
                        android.util.Log.i(TAG, "event=restart_alarm kind=exact at=" + at);
                    } catch (Throwable t) {
                        am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                        android.util.Log.i(TAG, "event=restart_alarm kind=inexact at=" + at);
                    }
                }
            }
        } catch (Throwable ignored) { }
        try {
            final Context app2 = c.getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                private int waited;
                @Override public void run() {
                    // 抓取线程还没落完盘就不杀进程（否则会抓到一半被杀，重启后又是半套基线）。
                    try {
                        if (capturing(app2) && waited < 20) {
                            waited++;
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(this, 1000L);
                            return;
                        }
                    } catch (Throwable ignored) { }
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    } catch (Throwable ignored) { }
                }
            }, 800L);
        } catch (Throwable ignored) { }
    }
}
