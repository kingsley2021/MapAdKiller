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
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 抓到基线后由 Baseline 落的「该重启一次」标记。 */
    public static final String F_RESTART = "restart.pending";

    private static boolean promptShown;
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
        try {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("✓ 已获取官方数据")
                    .setMessage("官方下发的数据已经抓到，模块功能可以启用了。\n\n"
                            + "3 秒后自动关闭高德并退出采集模式，重新打开高德就按你的设置生效。"
                            + "（个别系统会拦截后台自启，没有自动打开就点一下桌面图标。）")
                    .setCancelable(false)
                    .setPositiveButton("现在重启", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            setCaptureMode(a, false);
                            restartHost(a);
                        }
                    })
                    .show();
        } catch (Throwable ignored) { }
        try {
            a.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() {
                    setCaptureMode(a, false);
                    restartHost(a);
                }
            }, 5000L);
        } catch (Throwable ignored) { }
    }

    /**
     * 重启宿主：先排一个 1.5 秒后的系统闹钟拉起启动页，再杀掉自己。
     * 没有 root 不能 {@code am force-stop}，进程死了闹钟还在系统里，起来就是干净的一次启动。
     * 顺便关掉采集模式（该标记只在抓取期间有意义）。
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
                if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500L, pi);
            }
        } catch (Throwable ignored) { }
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    } catch (Throwable ignored) { }
                }
            }, 800L);
        } catch (Throwable ignored) { }
    }
}
