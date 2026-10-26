package io.github.ldxm666.mapadkiller;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 「自动进入巡航」模块接管驱动器。
 *
 * <h3>为什么由模块接管</h3>
 * 高德 17.02 设置页「通用 → 出行设置 → 自动进入巡航」是服务端下发的 AJX 页面：
 * 开关能点、状态跨进程重启保持，但实测点击时 **不写任何本地存储**
 * （hook SharedPreferences.EditorImpl / MapSharePreference.put* / AjxModuleLocalStorage.setItem*
 * 全部零命中，2218 个文件 stat 快照零变化），说明高德自己的触发链没有落地。
 * 而云控配置里功能是放开的：
 * <pre>
 * cloudconfig_aocs_sp_key_value.xml → MainMapCruise =
 *   {"switch&amp;ABTest":1,"params":{"minSpeed":15,"maxSpeed":200,"driveTime":5,
 *    "operateTime":5,"quickBackTime":5,"quickBackCountThreshold":2,"foregroundContinue":300}}
 * navi_cloud.mainCruiseSwitch = 1
 * </pre>
 * 所以模块按这套云控参数自己驱动：读高德定位服务的实时速度，满足条件就发
 * {@code amapuri://edog/home}（实测可直接进入"已巡航"界面）。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>默认关闭（{@link Config#K_CRUISE_AUTO} 默认 false）⇒ 老用户行为不变；</li>
 *   <li>只在 AMap 前台、且不在导航/巡航中时判定；</li>
 *   <li>定位不可用 / 无权限 / 任何异常一律静默降级，绝不崩宿主。</li>
 * </ul>
 */
final class AmapCruise {
    private AmapCruise() {}

    /** 云控参数（来源见类注释，17.02.0.1541 实机 cloudconfig_aocs_sp_key_value.xml）。 */
    private static final float MIN_KMH = 15f;          // minSpeed
    private static final float MAX_KMH = 200f;         // maxSpeed
    private static final long DRIVE_MS = 5000L;        // driveTime = 5s
    private static final long QUICK_BACK_MS = 5000L;   // quickBackTime = 5s
    private static final int QUICK_BACK_MAX = 2;       // quickBackCountThreshold = 2
    /** 抑制窗口：本次行程（云控没有给，取 10 分钟，速度掉到阈值以下不会解除）。 */
    private static final long SUPPRESS_MS = 10 * 60 * 1000L;
    /** 采样周期：2s（云控没有给，取 2s；driveTime=5s 至少覆盖 2~3 个采样点）。 */
    private static final long TICK_MS = 2000L;

    private static final String URI_CRUISE = "amapuri://edog/home";
    private static final String CLS_SCHEME = "com.autonavi.map.activity.SchemeHandleActivity";

    private static Context app;
    private static ClassLoader hostLoader;
    private static Handler handler;
    private static volatile boolean started;

    private static long speedOkSince;
    private static long lastEnterAt;
    private static int quickBackCount;
    private static long suppressUntil;
    private static String lastLoggedState = "";

    static void install(final ClassLoader cl) {
        hostLoader = cl;
        try {
            handler = new Handler(Looper.getMainLooper());
            started = true;
            // onPackageReady 早于 Application.onCreate：Context 要重试拿，不能一次失败就放弃
            handler.postDelayed(BOOT, 300L);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_driver install_failed err=" + t);
        }
    }

    private static final Runnable BOOT = new Runnable() {
        private int tries;
        @Override public void run() {
            try {
                if (app == null) app = currentApplication();
                if (app != null) {
                    String diag = "";
                    try {
                        android.content.SharedPreferences p = Config.prefs();
                        diag = " prefs=" + (p == null ? "null" : p.getClass().getSimpleName())
                                + " n=" + (p == null ? -1 : p.getAll().size())
                                + " cruise=" + (p == null ? "-" : p.getBoolean(Config.K_CRUISE_AUTO, false))
                                + " force=" + (p == null ? "-" : p.getBoolean(Config.K_CFG_FORCE_LOCAL, false));
                    } catch (Throwable ignored) {}
                    H.log(Log.INFO, MainHook.TAG, "event=cruise_driver installed enabled=" + Config.cruiseAuto()
                            + " tries=" + tries + diag);
                    handler.postDelayed(TICK, TICK_MS);
                    return;
                }
            } catch (Throwable ignored) {}
            if (++tries < 40) {
                handler.postDelayed(this, 500L);
            } else {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_driver skip=no_app tries=" + tries);
            }
        }
    };

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            try { tick(); } catch (Throwable ignored) {}
            try { if (started) handler.postDelayed(this, TICK_MS); } catch (Throwable ignored) {}
        }
    };

    private static void tick() {
        if (!Config.cruiseAuto()) { speedOkSince = 0; return; }
        if (!isForeground()) { speedOkSince = 0; return; }
        if (inCruise() || inNavigation()) {
            // 用户自己退出巡航后 5s 内又被判到 → 记一次「快速返回」
            if (lastEnterAt > 0) {
                long dt = SystemClock.elapsedRealtime() - lastEnterAt;
                if (dt > 800 && dt <= QUICK_BACK_MS && !inCruise()) {
                    quickBackCount++;
                    lastEnterAt = 0;
                    H.log(Log.INFO, MainHook.TAG, "event=cruise_suppress count=" + quickBackCount);
                    if (quickBackCount >= QUICK_BACK_MAX) {
                        suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS;
                    }
                }
            }
            speedOkSince = 0;
            return;
        }
        if (suppressUntil > 0 && SystemClock.elapsedRealtime() < suppressUntil) { speedOkSince = 0; return; }
        if (suppressUntil > 0) { suppressUntil = 0; quickBackCount = 0; }

        float kmh = readSpeedKmh();
        if (kmh < 0f) { speedOkSince = 0; return; }
        long now = SystemClock.elapsedRealtime();
        if (kmh >= MIN_KMH && kmh <= MAX_KMH) {
            if (speedOkSince == 0) {
                speedOkSince = now;
                logSpeed(kmh, "acc");
            } else if (now - speedOkSince >= DRIVE_MS) {
                enterCruise(kmh);
                speedOkSince = 0;
            }
        } else {
            if (speedOkSince != 0) logSpeed(kmh, "reset");
            speedOkSince = 0;
        }
    }

    private static void enterCruise(float kmh) {
        try {
            lastEnterAt = SystemClock.elapsedRealtime();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(URI_CRUISE));
            intent.setClassName(app.getPackageName(), CLS_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
            H.log(Log.INFO, MainHook.TAG, "event=cruise_enter reason=auto speed=" + Math.round(kmh));
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter failed err=" + t);
        }
    }

    /** 读高德自己定位服务的最新点，返回 km/h；拿不到返回 -1。 */
    private static float readSpeedKmh() {
        try {
            Class<?> ai = hostLoader.loadClass("com.amap.AppInterfaces");
            Method get = ai.getDeclaredMethod("getLocationService");
            Object svc = get.invoke(null);
            if (svc == null) return -1f;
            Class<?> ils = hostLoader.loadClass("com.amap.location.api.ILocationService");
            Object loc = ils.getMethod("getLatestLocation").invoke(svc);
            if (loc == null) return -1f;
            Class<?> lc = hostLoader.loadClass("com.amap.location.type.location.Location");
            float speed = (Float) lc.getMethod("getSpeed").invoke(loc);
            boolean isKmh = (Boolean) lc.getMethod("isSpeedKmh").invoke(loc);
            long tickTime = (Long) lc.getMethod("getLocationTickTime").invoke(loc);
            long now = System.currentTimeMillis();
            // 过期点（>15s）视为无定位：tickTime 可能是 UTC 毫秒也可能是 elapsedRealtime，两种都比一次
            if (tickTime > 0) {
                long age = Math.abs(now - tickTime);
                long ageAlt = Math.abs(SystemClock.elapsedRealtime() - tickTime);
                if (Math.min(age, ageAlt) > 15000L) return -1f;
            }
            return isKmh ? speed : speed * 3.6f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    private static boolean isForeground() {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return false;
            String pkg = app.getPackageName();
            for (ActivityManager.RunningAppProcessInfo p : list) {
                if (pkg.equals(p.processName)) {
                    return p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 调高德自己的 IDriveNaviService 判定，拿不到时退化为 false（宁可多进一次也不误锁）。 */
    private static boolean inCruise() { return callDriveBool("isInCruise"); }
    private static boolean inNavigation() { return callDriveBool("isInNavigation"); }

    private static boolean callDriveBool(String method) {
        try {
            Class<?> loader = hostLoader.loadClass("com.autonavi.inter.impl.BundleInterfaceLoaderImpl");
            Object impl = loader.getDeclaredConstructor().newInstance();
            Class<?> api = hostLoader.loadClass("com.amap.bundle.drive.api.IDriveNaviService");
            Object svc = loader.getMethod("getBundle", Class.class).invoke(impl, api);
            if (svc == null) return false;
            Object r = api.getMethod(method).invoke(svc);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void logSpeed(float kmh, String state) {
        if (lastLoggedState.equals(state) && "reset".equals(state)) return;
        lastLoggedState = state;
        H.log(Log.INFO, MainHook.TAG, "event=cruise_probe speed=" + Math.round(kmh) + " state=" + state);
    }

    private static Context currentApplication() {
        // 复用 Config 的四级 Context 兜底（ActivityThread.currentApplication → AppGlobals →
        // mInitialApplication → 内置版 App.ctx），比单条反射稳。
        try {
            Context c = Config.hostCtx();
            if (c != null) return c;
        } catch (Throwable ignored) {}
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentApplication");
            cur.setAccessible(true);
            Object o = cur.invoke(null);
            return o instanceof Context ? (Context) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 诊断探针（只在 debug_log 打开时安装，用于复核"设置页开关到底写不写"）──────────
    static void installProbe(final ClassLoader cl) {
        if (!Config.debugLog()) return;
        try {
            Class<?> c = H.cls(cl, "com.amap.bundle.mapstorage.MapSharePreference");
            XposedInterface.Hooker hooker = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        H.log(Log.INFO, MainHook.TAG, "event=cruise_probe msp."
                                + chain.getExecutable().getName() + " key=" + chain.getArg(0)
                                + " val=" + chain.getArg(1));
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            };
            H.hookAll(c, "putBooleanValue", "amap_cruise_msp_b", hooker);
            H.hookAll(c, "putIntValue", "amap_cruise_msp_i", hooker);
        } catch (Throwable ignored) {}
    }
}
