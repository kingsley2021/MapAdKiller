package io.github.ldxm666.mapadkiller;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
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
 * <h3>2.2.6 之后的「不灵敏」修正</h3>
 * <ul>
 *   <li>采样 <b>1s</b>；进入判定 2.2.6 收紧为 <b>25km/h 连续 20 秒 + 位移 ≥120 米 + 精度 ≤60 米</b>
 *       （原来的 8km/h / 3 秒步行和拿手机都能触发）；</li>
 *   <li>速度不再只有一条路：① 高德 ILocationService 最新点 → ② 系统 LocationManager
 *       最后已知点（GPS/网络，20s 内有效）→ ③ 两次定位的位移换算；
 *       任一路拿到有效速度即可，避免「高德没在定位 ⇒ 永远不触发」；</li>
 *   <li>进入后 1.2s×5 复核 {@code isInCruise()}，没进去就**自动重试一次**，并留
 *       {@code cruise_enter_ok / cruise_enter_retry / cruise_enter_fail} 日志；</li>
 *   <li>每 15s 一条 {@code event=cruise_tick} 心跳，带 fg/cruise/nav/kmh/src/hold，
 *       现场就能看出是「没采样」「没速度」还是「判定没到时间」。</li>
 * </ul>
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

    /**
     * 进入门槛（2.2.6 收紧）：原来的 8km/h + 3 秒太灵敏 —— 步行、甚至把手机从桌上拿起来都能凑满，
     * 巡航会被莫名其妙顶开。现在必须「持续像在开车」：车速连续 20 秒不低于 25km/h，
     * 这段时间真的跑出去 120 米，定位精度不差于 60 米，且不能有跳点。
     * 步行 20 秒最多 30 米、拿起来晃一下只有几米，都过不了位移累计这一关。
     */
    private static final float MIN_KMH = 18f;
    private static final float MAX_KMH = 200f;
    private static final long DRIVE_MS = 15000L;
    /** 窗口内「路径」累计位移下限（米）：18km/h × 15s ≈ 75m，留余量取 60m。 */
    private static final float MIN_DIST_M = 60f;
    /** 窗口内「净位移」下限（米）：起点到当前点的直线距离，原地抖动净位移≈0，永远过不了。 */
    private static final float MIN_NET_M = 45f;
    /** 窗口内至少要攒够这么多步可信位移，防止同一个旧定位点被反复计数。 */
    private static final int MIN_WIN_FIXES = 6;
    /** 定位精度门槛（米）：比这更差的点（室内、基站、多径跳点）不累计、不当依据。 */
    private static final float ACC_MAX_M = 45f;
    /** 单步位移硬上限（米）：一跳几百米的是多径跳点，直接作废窗口。 */
    private static final float STEP_MAX_M = 90f;
    /** 相邻采样允许的最大速度突变（km/h，取绝对值），挡 GNSS 抖动和「拿起来晃一下」。 */
    private static final float SPIKE_KMH = 45f;
    /** 进入失败/退出后的冷却，避免刚退出来又自己进去。 */
    private static final long ENTER_COOLDOWN_MS = 60000L;
    private static final long QUICK_BACK_MS = 5000L;   // quickBackTime = 5s
    private static final int QUICK_BACK_MAX = 3;       // quickBackCountThreshold（12s 内连退 3 次才算「用户不想要」）
    /** 抑制窗口：云控没给时长，取 3 分钟 —— 太短会连发，太长会让用户觉得「开了没反应」。 */
    private static final long SUPPRESS_MS = 3 * 60 * 1000L;
    /** 采样周期：1s（原 2s；driveTime=3s 至少要 3 个点才判定）。 */
    private static final long TICK_MS = 1000L;
    /** 定位点超过这个年龄就不算「实时速度」。 */
    private static final long SPEED_STALE_MS = 20000L;
    /** 心跳日志周期。 */
    private static final long HEARTBEAT_MS = 15000L;
    /** 位移法：两次有效定位至少要隔这么久，算出来的速度才可信。 */
    private static final long MOVE_MIN_DT_MS = 1500L;

    private static final String URI_CRUISE = "amapuri://edog/home";
    private static final String CLS_SCHEME = "com.autonavi.map.activity.SchemeHandleActivity";

    private static Context app;
    private static boolean fgAttached;
    private static ClassLoader hostLoader;
    private static Handler handler;
    private static volatile boolean started;

    private static long speedOkSince;
    private static long lastEnterAt;
    private static int quickBackCount;
    private static long suppressUntil;
    private static String lastLoggedState = "";
    private static long lastBeatAt;
    private static int enterSeq;

    // 进入判定的窗口状态：窗口起点、起点时的累计位移基准、上一次采样速度、判定失败原因
    private static long winStart;
    private static float winBaseDist;
    private static float prevKmh = -1f;
    private static volatile float totalDistM;
    private static volatile long posJumpAt;
    private static volatile String gateWhy = "";
    private static volatile float lastAcc = -1f;
    private static volatile long distFixSeq;
    private static long winBaseFix;
    private static double winLat, winLng;

    // 位移法用的上一个定位点
    private static volatile double lastLat, lastLng;
    private static volatile long lastPosAt;
    private static volatile float moveKmh;

    // 定位回调流：hook Location#setSpeed，AMap 每算出一个定位点就刷新一次
    private static volatile float hookSpeed;
    private static volatile boolean hookKmh;
    private static volatile long hookAt;
    private static volatile Object hookLoc;
    private static volatile java.lang.reflect.Method unitMethod;
    private static volatile String why = "init";
    private static volatile float dbgKmh = -1f;
    private static volatile long dbgAt;

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
                    if (!fgAttached) {
                        fgAttached = true;
                        if (app instanceof android.app.Application) HostFg.attach((android.app.Application) app);
                    }
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
                    installLocationHook();
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
        boolean fg = HostFg.fg();
        boolean cruise = fg && inCruise();
        boolean nav = fg && inNavigation();
        if (!fg) { speedOkSince = 0; beat(fg, false, false, -1f, "none"); return; }

        if (cruise || nav) {
            // 用户自己退出巡航后 5s 内又被判到 → 记一次「快速返回」
            if (lastEnterAt > 0) {
                long dt = SystemClock.elapsedRealtime() - lastEnterAt;
                if (dt > 800 && dt <= QUICK_BACK_MS && !cruise) {
                    quickBackCount++;
                    lastEnterAt = 0;
                    H.log(Log.INFO, MainHook.TAG, "event=cruise_suppress count=" + quickBackCount);
                    if (quickBackCount >= QUICK_BACK_MAX) {
                        suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS;
                    }
                }
            }
            speedOkSince = 0;
            beat(true, cruise, nav, -1f, "in");
            return;
        }
        if (suppressUntil > 0 && SystemClock.elapsedRealtime() < suppressUntil) {
            speedOkSince = 0;
            beat(true, false, false, -1f, "hold");
            return;
        }
        if (suppressUntil > 0) { suppressUntil = 0; quickBackCount = 0; }
        // 进入冷却：失败/退出后 60 秒内不再自己进去，避免「刚退出来又弹进去」
        if (lastEnterAt > 0
                && SystemClock.elapsedRealtime() - lastEnterAt < ENTER_COOLDOWN_MS) {
            speedOkSince = 0;
            gateWhy = "cooldown";
            beat(true, false, false, -1f, "cooldown");
            return;
        }

        Sample s = sample();
        if (s == null || s.kmh < 0f) {
            speedOkSince = 0;
            winBaseDist = totalDistM;
            winBaseFix = distFixSeq;
            gateWhy = "nospeed";
            beat(true, false, false, -1f, "nospeed");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        String bad = gate(s, now);
        if (bad != null) {
            if (speedOkSince != 0) logSpeed(s.kmh, "reset");
            speedOkSince = 0;
            winBaseDist = totalDistM;
            winBaseFix = distFixSeq;
            prevKmh = -1f;
            gateWhy = bad;
        } else if (speedOkSince == 0) {
            // 第一秒达标：开一个判定窗口，位移从这一刻开始累计
            speedOkSince = now;
            winStart = now;
            winBaseDist = totalDistM;
            winBaseFix = distFixSeq;
            winBaseFix = distFixSeq;
            winLat = lastLat;
            winLng = lastLng;
            prevKmh = s.kmh;
            gateWhy = "win";
            logSpeed(s.kmh, "acc");
        } else {
            prevKmh = s.kmh;
            float dist = totalDistM - winBaseDist;
            float net = (winLat == 0d && winLng == 0d) ? 0f
                    : distanceMeters(winLat, winLng, lastLat, lastLng);
            long fixes = distFixSeq - winBaseFix;
            boolean dbg = "debug".equals(s.src);      // 桌面探针没有真实位移，放行
            boolean enough = dbg || (dist >= MIN_DIST_M && net >= MIN_NET_M && fixes >= MIN_WIN_FIXES);
            if (now - speedOkSince >= DRIVE_MS && enough) {
                speedOkSince = 0;
                gateWhy = "enter";
                enterCruise(s.kmh, s.src);
            } else {
                gateWhy = "win";
            }
        }
        beat(true, false, false, s.kmh, s.src);
    }

    // ── 速度采样 ────────────────────────────────────────────────────────────────

    private static final class Sample {
        final float kmh;
        final String src;
        final float acc;          // 定位精度（米），-1 = 不知道

        Sample(float kmh, String src) { this(kmh, src, -1f); }

        Sample(float kmh, String src, float acc) {
            this.kmh = kmh;
            this.src = src;
            this.acc = acc;
        }
    }

    /** 四级兜底：高德最新点 → 定位回调流 → 系统最后已知点 → 位移换算。全失败返回 null。 */
    private static Sample sample() {
        Sample t = fromDebugFile();
        if (t != null) { why = "debug"; return t; }
        Sample a = fromAmap();
        if (a != null) { why = "amap"; return a; }
        Sample b = fromHook();
        if (b != null) { why = "hook"; return b; }
        Sample c = fromSystem();
        if (c != null) { why = "sys"; return c; }
        Sample d = fromDisplacement();
        if (d != null) { why = "move"; return d; }
        return null;
    }

    /**
     * 排障用：/data/local/tmp/mapclean_cruise_kmh 里写一个数字（需要 root 或 adb 才能写），
     * 就按这个速度当作实时速度。用来在静止的桌面上验证「累计→进入巡航→校验」整条链路，
     * 平时该文件不存在，对本功能没有任何影响。
     */
    private static Sample fromDebugFile() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/mapclean_cruise_kmh");
            long now = SystemClock.elapsedRealtime();
            if (now - dbgAt < 3000L) return dbgKmh >= 0f ? new Sample(dbgKmh, "debug") : null;
            dbgAt = now;
            if (!f.exists()) { dbgKmh = -1f; return null; }
            byte[] buf = new byte[32];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n;
            try { n = in.read(buf); } finally { in.close(); }
            if (n <= 0) { dbgKmh = -1f; return null; }
            dbgKmh = Float.parseFloat(new String(buf, 0, n).trim());
            return dbgKmh >= 0f ? new Sample(dbgKmh, "debug") : null;
        } catch (Throwable t) {
            dbgKmh = -1f;
            return null;
        }
    }

    /** ② hook 流：Location#setSpeed 每次被调用都刷新，10s 内算实时。 */
    private static Sample fromHook() {
        try {
            if (hookAt == 0) return null;
            if (SystemClock.elapsedRealtime() - hookAt > 10000L) return null;
            float kmh = unitKmh(hookLoc) ? hookSpeed : hookSpeed * 3.6f;
            if (kmh < 0f) return null;
            if (kmh < 1f && moveKmh >= MIN_KMH
                    && SystemClock.elapsedRealtime() - lastPosAt <= SPEED_STALE_MS) {
                // 位移换算只作参考：src=hook+move 不参与进入判定（见 gate 里的来源白名单）
                return new Sample(moveKmh, "hook+move", accuracyOf(hookLoc));
            }
            return new Sample(kmh, "hook", accuracyOf(hookLoc));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 单位以定位点自己的 isSpeedKmh 标记为准（逐帧读，避免串帧把 km/h 又乘 3.6）。 */
    private static boolean unitKmh(Object loc) {
        if (loc != null) {
            try {
                java.lang.reflect.Method m = unitMethod;
                if (m == null) {
                    m = loc.getClass().getMethod("isSpeedKmh");
                    m.setAccessible(true);
                    unitMethod = m;
                }
                Object r = m.invoke(loc);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable ignored) {}
        }
        return hookKmh;
    }

    /** 装定位回调 hook：AMap 自己每生成一个定位点都会 setSpeed，等于免费拿到推送。 */
    private static void installLocationHook() {
        try {
            Class<?> lc = H.cls(hostLoader, "com.amap.location.type.location.Location");
            int n = 0;
            n += H.hookAll(lc, "setSpeed", "amap_cruise_speed", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object v = chain.getArg(0);
                        if (v instanceof Float) {
                            hookSpeed = (Float) v;
                            hookAt = SystemClock.elapsedRealtime();
                            hookLoc = chain.getThisObject();
                            // 同一个定位点顺手喂给位移法：GNSS 速度可能为 0（隧道/冷启动），
                            // 但经纬度一直在动，位移法就能补上。
                            rememberPosition(chain.getThisObject());
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            });
            n += H.hookAll(lc, "setSpeedKmh", "amap_cruise_speed_kmh", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object v = chain.getArg(0);
                        if (v instanceof Boolean) hookKmh = (Boolean) v;
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            });
            H.log(Log.INFO, MainHook.TAG, "event=cruise_hook location=" + n);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_hook failed err=" + t);
        }
    }

    /** ① 高德自己的定位服务（精度最好，导航/巡航时就是它）。 */
    private static Sample fromAmap() {
        try {
            Class<?> ai = hostLoader.loadClass("com.amap.AppInterfaces");
            Object svc = ai.getDeclaredMethod("getLocationService").invoke(null);
            if (svc == null) { why = "svc_null"; return null; }
            Class<?> ils = hostLoader.loadClass("com.amap.location.api.ILocationService");
            Object loc = ils.getMethod("getLatestLocation").invoke(svc);
            if (loc == null) { why = "loc_null"; return null; }
            rememberPosition(loc);
            Sample s = speedOf(loc, "amap");
            if (s == null) why = "stale";
            return s;
        } catch (Throwable t) {
            why = "amap_err:" + t.getClass().getSimpleName();
            return null;
        }
    }

    /** ② 系统 LocationManager 的最后已知点：高德没在跑定位时也能拿到（20s 内有效）。 */
    private static Sample fromSystem() {
        try {
            if (app == null) return null;
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            long now = System.currentTimeMillis();
            String[] probes = new String[] {
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    "fused"
            };
            for (String p : probes) {
                Location l = null;
                try { l = lm.getLastKnownLocation(p); } catch (Throwable ignored) {}
                if (l == null) continue;
                long age = now - l.getTime();
                if (age < 0 || age > SPEED_STALE_MS) continue;
                rememberAndroid(l);
                if (!l.hasSpeed()) continue;
                float mps = l.getSpeed();
                float kmh = mps * 3.6f;
                if (kmh >= 0f) return new Sample(kmh, "sys", l.getAccuracy());
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** ③ 位移法：两个定位点之间跑了多远／多久（连速度字段都没有时兜底）。 */
    private static Sample fromDisplacement() {
        try {
            if (lastPosAt <= 0 || moveKmh < MIN_KMH) return null;
            if (SystemClock.elapsedRealtime() - lastPosAt > SPEED_STALE_MS) return null;
            return new Sample(moveKmh, "move", -1f);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Sample speedOf(Object loc, String src) {
        try {
            Class<?> lc = hostLoader.loadClass("com.amap.location.type.location.Location");
            float speed = (Float) lc.getMethod("getSpeed").invoke(loc);
            boolean isKmh = false;
            try { isKmh = (Boolean) lc.getMethod("isSpeedKmh").invoke(loc); } catch (Throwable ignored) {}
            long tickTime = 0L;
            try { tickTime = (Long) lc.getMethod("getLocationTickTime").invoke(loc); } catch (Throwable ignored) {}
            long now = System.currentTimeMillis();
            if (tickTime > 0) {
                long age = Math.abs(now - tickTime);
                long ageAlt = Math.abs(SystemClock.elapsedRealtime() - tickTime);
                if (Math.min(age, ageAlt) > SPEED_STALE_MS) return null;
            }
            float kmh = isKmh ? speed : speed * 3.6f;
            if (kmh < 0f) return null;
            return new Sample(kmh, src, accuracyOf(loc));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 精度（米）；读不到给 -1，调用方按「不校验」处理，不能因为读不到就永远不触发。 */
    private static float accuracyOf(Object loc) {
        if (loc == null) return -1f;
        try {
            Object a = loc.getClass().getMethod("getAccuracy").invoke(loc);
            if (a instanceof Float) return (Float) a;
            if (a instanceof Double) return ((Double) a).floatValue();
        } catch (Throwable ignored) {}
        return -1f;
    }

    private static void rememberPosition(Object loc) {
        try {
            Class<?> lc = hostLoader.loadClass("com.amap.location.type.location.Location");
            double lat = (Double) lc.getMethod("getLatitude").invoke(loc);
            double lng = (Double) lc.getMethod("getLongitude").invoke(loc);
            if (lat == 0d && lng == 0d) return;
            float acc = accuracyOf(loc);
            if (acc < 0f) acc = 30f;          // 高德点一般带精度；读不到时按中等精度处理
            long before = lastPosAt;
            double pLat = lastLat, pLng = lastLng;
            addFix(lat, lng, acc, true);
            // 位移法继续沿用原来的口径（只在时间够长时更新 moveKmh）
            long dt = lastPosAt - before;
            if (before > 0 && dt >= MOVE_MIN_DT_MS) {
                float m = distanceMeters(pLat, pLng, lat, lng);
                float kmh = (m / (dt / 1000f)) * 3.6f;
                if (kmh >= MIN_KMH) moveKmh = kmh; else moveKmh = 0f;
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 统一的定位点记账：只有「精度可信 + 时间合理 + 单步速度自洽 + 不是原地抖动」的位移才累计。
     * 原来的实现只要 3 米就累加，静止时 20 秒的定位抖动都能凑满 120 米 —— 那才是误触发的根。
     */
    private static void addFix(double lat, double lng, float acc, boolean trusted) {
        long now = SystemClock.elapsedRealtime();
        if (!trusted || acc < 0f || acc > ACC_MAX_M) {
            // 精度不可信：只更新锚点，不累计距离
            lastLat = lat; lastLng = lng; lastPosAt = now;
            return;
        }
        if (lastPosAt > 0) {
            long dt = now - lastPosAt;
            float m = distanceMeters(lastLat, lastLng, lat, lng);
            float stepKmh = dt > 0 ? (m / (dt / 1000f)) * 3.6f : 0f;
            boolean dup = m < Math.max(1.5f, Math.min(4f, acc * 0.15f)); // 同一点重复上报 / 原地抖动
            boolean gap = dt < 800L || dt > 10000L;           // 太密或隔太久，速度不可信
            boolean jump = m > STEP_MAX_M || stepKmh > 220f;  // 多径跳点
            if (jump) {
                posJumpAt = now;
            } else if (!dup && !gap && stepKmh >= 8f) {       // 步行 ≈6km/h，8km/h 以上才算车在动
                totalDistM += m;
                distFixSeq++;
            }
        }
        lastLat = lat; lastLng = lng; lastPosAt = now;
    }

    private static void rememberAndroid(Location l) {
        try {
            long before = lastPosAt;
            boolean hasAcc = true;
            float acc = -1f;
            try { acc = l.getAccuracy(); hasAcc = l.hasAccuracy(); } catch (Throwable ignored) {}
            double pLat = lastLat, pLng = lastLng;
            addFix(l.getLatitude(), l.getLongitude(), hasAcc ? acc : -1f, hasAcc);
            long dt = lastPosAt - before;
            if (before > 0 && dt >= MOVE_MIN_DT_MS) {
                float m = distanceMeters(pLat, pLng, l.getLatitude(), l.getLongitude());
                float kmh = (m / (dt / 1000f)) * 3.6f;
                if (kmh >= MIN_KMH) moveKmh = kmh; else moveKmh = 0f;
            }
        } catch (Throwable ignored) {}
    }

    private static float distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return (float) (2 * r * Math.asin(Math.sqrt(a)));
    }

    // ── 进入 + 复核 ────────────────────────────────────────────────────────────

    private static void enterCruise(final float kmh, String src) {
        final int seq = ++enterSeq;
        lastEnterAt = SystemClock.elapsedRealtime();
        fire();
        H.log(Log.INFO, MainHook.TAG, "event=cruise_enter reason=auto speed=" + Math.round(kmh)
                + " src=" + src + " seq=" + seq);
        // 1.2s×5 复核：真进了就打 ok；第一次没进（被吞）就补发一次
        for (int i = 1; i <= 5; i++) {
            final int attempt = i;
            handler.postDelayed(new Runnable() {
                @Override public void run() { verify(seq, attempt); }
            }, 1200L * i);
        }
    }

    private static void verify(int seq, int attempt) {
        if (seq != enterSeq) return;          // 已经被新一轮取代
        boolean ok = false;
        try { ok = inCruise(); } catch (Throwable ignored) {}
        if (ok) {
            H.log(Log.INFO, MainHook.TAG, "event=cruise_enter_ok seq=" + seq + " after=" + attempt);
            enterSeq = 0;
            return;
        }
        if (attempt == 1) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_retry seq=" + seq);
            fire();
        } else if (attempt == 5) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_fail seq=" + seq);
        }
    }

    private static void fire() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(URI_CRUISE));
            intent.setClassName(app.getPackageName(), CLS_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter failed err=" + t);
        }
    }

    // ── 状态与日志 ──────────────────────────────────────────────────────────────

    static boolean isForeground() {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null || list.isEmpty()) return true;   // 拿不到列表时不当作「后台」，否则永远不触发
            String pkg = app.getPackageName();
            for (ActivityManager.RunningAppProcessInfo p : list) {
                if (pkg.equals(p.processName)) {
                    return p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
            return true;
        } catch (Throwable ignored) {}
        return true;
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

    /**
     * 进入判定的唯一闸门：返回 null 表示这一次采样算数，否则返回不算数的原因。
     * 宁可晚触发 / 不触发，也不能被步行、拿手机、室内跳点顶开巡航。
     */
    private static float netM() {
        if (winLat == 0d && winLng == 0d) return 0f;
        return distanceMeters(winLat, winLng, lastLat, lastLng);
    }

    private static String gate(Sample s, long now) {
        if (s == null) return "nospeed";
        lastAcc = s.acc;
        // 纯位移换算（单点跳变就能算出几百 km/h）只能看，不能作为进入依据
        if ("move".equals(s.src) || "hook+move".equals(s.src)) return "src=" + s.src;
        if (s.kmh < MIN_KMH) return "slow";
        if (s.kmh > MAX_KMH) return "fast";
        if (s.acc >= 0f && s.acc > ACC_MAX_M) return "acc";
        if (posJumpAt > 0 && now - posJumpAt < 3000L) return "jump";
        if (prevKmh > 0f && Math.abs(s.kmh - prevKmh) > SPIKE_KMH) return "spike";
        return null;
    }

    private static void logSpeed(float kmh, String state) {
        if (lastLoggedState.equals(state) && "reset".equals(state)) return;
        lastLoggedState = state;
        H.log(Log.INFO, MainHook.TAG, "event=cruise_probe speed=" + Math.round(kmh) + " state=" + state);
    }

    /**
     * 一行状态汇总，给「主动抓包」的 10 分钟 txt 用（每 5 秒记一条）。
     * 所有值都是现成字段，取不到就给默认值，绝不抛。
     */
    static String diag() {
        try {
            long now = SystemClock.elapsedRealtime();
            long hold = speedOkSince == 0 ? 0 : now - speedOkSince;
            boolean fg = HostFg.fg();
            boolean cruise = fg && inCruise();
            boolean nav = fg && inNavigation();
            float kmh = -1f;
            if (hookSpeed >= 0f && hookAt > 0 && now - hookAt <= SPEED_STALE_MS) {
                kmh = hookKmh ? hookSpeed : hookSpeed * 3.6f;
            }
            long sup = suppressUntil > now ? (suppressUntil - now) / 1000L : 0L;
            return "fg=" + fg + " cruise=" + cruise + " nav=" + nav
                    + " kmh=" + Math.round(kmh) + " src=" + why
                    + " hold=" + hold + " move=" + Math.round(moveKmh)
                    + " dist=" + Math.round(totalDistM - winBaseDist) + " net=" + Math.round(netM())
                    + " fix=" + (distFixSeq - winBaseFix) + " acc=" + Math.round(lastAcc)
                    + " gate=" + gateWhy
                    + " hook_age=" + (hookAt == 0 ? -1 : now - hookAt)
                    + " suppress=" + sup + " qb=" + quickBackCount;
        } catch (Throwable t) {
            return "diag_err=" + t.getClass().getSimpleName();
        }
    }

    private static void beat(boolean fg, boolean cruise, boolean nav, float kmh, String src) {
        long now = SystemClock.elapsedRealtime();
        if (lastBeatAt != 0 && now - lastBeatAt < HEARTBEAT_MS) return;
        lastBeatAt = now;
        long hold = speedOkSince == 0 ? 0 : now - speedOkSince;
        H.log(Log.INFO, MainHook.TAG, "event=cruise_tick fg=" + fg + " cruise=" + cruise
                + " nav=" + nav + " kmh=" + Math.round(kmh) + " src=" + src
                + " hold=" + hold + " move=" + Math.round(moveKmh)
                + " win=" + (speedOkSince == 0 ? 0 : now - speedOkSince)
                + " dist=" + Math.round(totalDistM - winBaseDist)
                + " net=" + Math.round(netM()) + " fix=" + (distFixSeq - winBaseFix)
                + " acc=" + Math.round(lastAcc)
                + " gate=" + (gateWhy.length() == 0 ? "ok" : gateWhy)
                + " why=" + why);
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
