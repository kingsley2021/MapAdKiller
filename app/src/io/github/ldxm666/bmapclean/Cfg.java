package io.github.ldxm666.bmapclean;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * Hook 侧配置读取。
 *
 * 存储 = LSPosed RemotePreferences（同名 group 与模块 App 侧 XposedService 共享）。
 *
 * 失效安全：任何异常/缺省一律返回 **true（可见）**。
 * 也就是说模块挂不上、配置读不到、目标改版导致规则全 miss 时，
 * 百度地图的界面保持原样，不会出现"被清空到没法用"。
 */
public final class Cfg {

    /** RemotePreferences group 名（App 侧 CategoryActivity 用同一个） */
    public static final String GROUP = "bmapclean_config";

    /** 诊断日志开关（唯一默认 false 的键） */
    public static final String K_DEBUG = "debug_log";
    /** hook 侧写回的最近一次诊断摘要 */
    public static final String K_DIAG = "diag_last";

    /**
     * 真机取证期临时强制开日志（视图树 dump 走这里）。
     * 交付版必须为 false；probe 构建才临时打开。
     */
    public static final boolean FORCE_DEBUG = false;

    private static volatile SharedPreferences cached;
    private static volatile java.util.Map<String, ?> snapshot = java.util.Collections.emptyMap();
    private static volatile long expires;
    private static final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (p, key) -> { expires = 0; refreshFlags(); };
    private static volatile long dbgAt;
    private static volatile boolean dbgVal;

    private Cfg() {}

    // ══════════════════════════════════════════════════════════════════════════
    // 省电开关（命名与高德 mapadkiller/Config 对齐）
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 语义统一为「true = 已拦截」。默认 true（读不到配置也按拦截处理）。
    // 全部走**安装期门控**（见 BaiduPower.install）：关掉 ⇒ 连 hook 都不装 ⇒ 零性能开销。
    // 代价：改这些开关要**强制停止并重开百度地图**才生效。

    /** 推送长连接安全档：不建连接 + 不处理推送消息。 */
    public static final String K_PUSH_OFF = "push_off";
    /** 推送长连接硬档：掐断连接/线程的真实创建链。 */
    public static final String K_PUSH_HARD_OFF = "push_hard_off";
    /** 推送服务激进档：通道服务起来即退出。 */
    public static final String K_PUSH_KILL_OFF = "push_kill_off";
    /** 后台常驻服务（定位/云控/统计等自启动 Service）。 */
    public static final String K_BG_SERVICE_OFF = "subproc_off";
    /** 定时任务（AlarmManager / JobScheduler / WorkManager）。 */
    public static final String K_TIMER_OFF = "subproc_job_off";
    /** 桌面小组件刷新。 */
    public static final String K_WIDGET_OFF = "subproc_widget_off";
    /** 第三方埋点/统计 SDK。 */
    public static final String K_TRACK_OFF = "track_off";

    /** 省电键全集（「一键省电」用）。 */
    public static final String[] POWER_KEYS = {
            K_PUSH_HARD_OFF, K_PUSH_OFF, K_PUSH_KILL_OFF,
            K_BG_SERVICE_OFF, K_TIMER_OFF, K_WIDGET_OFF, K_TRACK_OFF
    };

    // ── 零成本开关：常驻 hook 的拦截器里只读一个 volatile ──────────────────────
    private static final java.util.List<Flag> FLAGS =
            new java.util.concurrent.CopyOnWriteArrayList<Flag>();

    /** 一个可被 prefs 变更刷新的布尔开关。 */
    public abstract static class Flag {
        private volatile boolean value;
        private volatile boolean resolved;
        protected Flag() { FLAGS.add(this); }
        protected abstract boolean compute();
        public final boolean on() {
            if (!resolved) resolve();
            return value;
        }
        public final void resolve() {
            boolean v;
            try { v = compute(); } catch (Throwable t) { v = true; }
            value = v;
            resolved = true;
        }
    }

    public static Flag flag(final String key) {
        return new Flag() {
            @Override protected boolean compute() { return Cfg.visible(key); }
        };
    }

    private static void refreshFlags() {
        for (Flag f : FLAGS) f.resolve();
    }

    /**
     * 安装期门控：开关关掉时**根本不装**对应 hook（"关闭 = 不加载、不消耗性能"）。
     * 读不到配置按 true（已拦截）处理。
     */
    public static boolean installGate(String key) {
        try { return visible(key); } catch (Throwable t) { return true; }
    }

    /**
     * 「一键省电」：把 {@link #POWER_KEYS} 全部写成 true。
     *
     * @return 实际写入的键数；0 = 当前没有可写后端（面板会提示"只读"）
     */
    public static int enableAllPower() {
        SharedPreferences p = prefs();
        if (p == null) return 0;
        try {
            SharedPreferences.Editor e = p.edit();
            for (String k : POWER_KEYS) e.putBoolean(k, true);
            if (!e.commit()) return 0;
            expires = 0;
            refreshFlags();
            return POWER_KEYS.length;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static SharedPreferences prefs() {
        SharedPreferences p = cached;
        if (p == null) {
            synchronized (Cfg.class) {
                p = cached;
                if (p == null) {
                    try {
                        p = H.module == null ? null : H.module.getRemotePreferences(GROUP);
                    } catch (Throwable t) {
                        p = null;
                    }
                    cached = p;
                    if (p != null) try { p.registerOnSharedPreferenceChangeListener(listener); } catch (Throwable ignored) {}
                }
            }
        }
        return p;
    }

    /** 缺省 true = 显示 */
    public static boolean visible(String key) {
        return visible(key, true);
    }

    /** 缺省值由调用方指定（用于默认「关」的键，例如 mine_apply） */
    public static boolean visible(String key, boolean def) {
        try {
            SharedPreferences p = prefs();
            if (p == null) return def;
            Object value = values().get(key);
            return value instanceof Boolean ? (Boolean) value : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 热点路径 2 秒 TTL 缓存，避免每帧都走一次 IPC */
    public static boolean debug() {
        if (FORCE_DEBUG) return true;
        long now = System.currentTimeMillis();
        if (now - dbgAt > 2000) {
            boolean v = false;
            try {
                SharedPreferences p = prefs();
                v = p != null && p.getBoolean(K_DEBUG, false);
            } catch (Throwable ignored) {}
            dbgVal = v;
            dbgAt = now;
        }
        return dbgVal;
    }

    /**
     * 设置页写的防伪 token（App 侧可写、hook 侧只读 —— 见 {@link Report} 的说明）。
     * hook 侧回报时带上它，设置页据此过滤伪造广播。
     */
    public static String token() {
        try {
            SharedPreferences p = prefs();
            if (p == null) return "";
            Object value = values().get("app_token");
            String s = value instanceof String ? (String) value : "";
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 说明：libxposed API 102 在 hook 侧返回的 RemotePreferences 是**只读**实现，
     * 真机实测 `edit().commit()` 抛 UnsupportedOperationException: Read only implementation。
     * 因此 hook 侧一律不回写配置；诊断结果走 {@link Report} 的广播通道。
     */
    public static void log(String s) {
        H.log(Log.INFO, MainHook.TAG, s);
    }

    private static java.util.Map<String, ?> values() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= expires) synchronized (Cfg.class) {
            if (now >= expires) {
                SharedPreferences p = prefs();
                if (p != null) try {
                    snapshot = java.util.Collections.unmodifiableMap(new java.util.HashMap<String, Object>(p.getAll()));
                } catch (Throwable ignored) {}
                expires = now + 2000;
            }
        }
        return snapshot;
    }
}
