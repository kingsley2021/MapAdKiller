package io.github.ldxm666.tmapclean;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * 腾讯地图 Hook 侧配置读取。
 *
 * <p>存储 = LSPosed RemotePreferences（group {@link #GROUP}，与模块设置页共用同一张表）。
 *
 * <p><b>失效安全</b>：任何异常 / 缺省一律返回 <b>true（可见）</b>。
 * 也就是说模块挂不上、配置读不到、腾讯改版导致规则全 miss 时，
 * 腾讯地图的界面保持原样，不会出现"被清空到没法用"。
 */
public final class Cfg {

    /** RemotePreferences group 名（App 侧设置页用同一个） */
    public static final String GROUP = "tmapclean_config";

    /** 诊断日志开关（唯一默认 false 的键） */
    public static final String K_DEBUG = "debug_log";

    /** 真机取证期临时强制开日志。交付版必须为 false。 */
    public static final boolean FORCE_DEBUG = false;

    private static volatile SharedPreferences cached;
    private static volatile java.util.Map<String, ?> snapshot = java.util.Collections.emptyMap();
    private static volatile long expires;
    private static volatile long dbgAt;
    private static volatile boolean dbgVal;

    private static final SharedPreferences.OnSharedPreferenceChangeListener listener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    invalidate();
                    MainHook.onConfigChanged(key);
                }
            };

    private Cfg() {}

    public static void invalidate() {
        expires = 0;
        dbgAt = 0;
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
                    if (p != null) {
                        try { p.registerOnSharedPreferenceChangeListener(listener); }
                        catch (Throwable ignored) {}
                    }
                }
            }
        }
        return p;
    }

    /** 缺省 true = 显示 */
    public static boolean visible(String key) {
        return visible(key, true);
    }

    public static boolean visible(String key, boolean def) {
        if (key == null) return def;
        try {
            if (prefs() == null) return def;
            Object value = values().get(key);
            return value instanceof Boolean ? ((Boolean) value).booleanValue() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 是否显示某个工具（工具 id = 服务端下发的 {@code Tool.name}）。
     *
     * <p>用户显式设置过的按用户的来；<b>没设置过的走 {@link Spec#defaultToolVisible}</b>
     * —— 首页宫格那 15 格默认显示，其余默认隐藏。
     *
     * <p>⚠ 这里以前写死 {@code return true}（"未设置一律显示"），与面板侧的缺省脱节：
     * 面板要表达"只有首页那几格是开的"就无从表达。两处缺省现在同源。
     *
     * <p>失效安全：{@code filterData()} 只对**认得出 id** 的格子问这个问题，
     * 取不到名字的格子直接保留；另外它还有"清空即原样放行"的兜底。
     */
    public static boolean toolVisible(String toolId) {
        if (toolId == null || toolId.length() == 0) return true;   // 认不出 → 保留
        Object value = values().get(Spec.K_TOOL + toolId);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return Spec.defaultToolVisible(toolId);
    }

    /**
     * 该键是否被用户<b>显式</b>设置过（区别于"按缺省值"）。
     *
     * <p>给"默认隐藏会不会把宫格清空"这道兜底用：只有用户真的逐格关过，
     * 才允许宫格被清空。
     */
    public static boolean explicit(String key) {
        if (key == null) return false;
        try {
            return values().containsKey(key);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否显示某个底部标签（页名，例如 homePage）；总开关由调用方另行判断 */
    public static boolean tabVisible(String pageName) {
        return visible(Spec.K_TAB + pageName, true);
    }

    /** 热点路径 2 秒 TTL 缓存，避免每帧都走一次 IPC */
    public static boolean debug() {
        if (FORCE_DEBUG) return true;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - dbgAt > 2000) {
            boolean v = false;
            try {
                v = Boolean.TRUE.equals(values().get(K_DEBUG));
            } catch (Throwable ignored) {}
            dbgVal = v;
            dbgAt = now;
        }
        return dbgVal;
    }

    /**
     * 设置页写入的防伪 token（App 侧可写、hook 侧只读 —— 见 {@link Report}）。
     * hook 侧回报时带上它，设置页据此过滤伪造广播。
     */
    public static String token() {
        try {
            Object value = values().get("app_token");
            return value instanceof String ? (String) value : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 说明：libxposed API 102 在 hook 侧返回的 RemotePreferences 是<b>只读</b>实现，
     * 真机实测 {@code edit().commit()} 抛 UnsupportedOperationException: Read only implementation。
     * 因此 hook 侧一律不回写配置；诊断与「学到的工具清单」走 {@link Report} 的广播通道。
     */
    public static void log(String s) {
        H.log(Log.INFO, MainHook.TAG, s);
    }

    private static java.util.Map<String, ?> values() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= expires) {
            synchronized (Cfg.class) {
                if (now >= expires) {
                    SharedPreferences p = prefs();
                    if (p != null) {
                        try {
                            snapshot = java.util.Collections.unmodifiableMap(
                                    new java.util.HashMap<String, Object>(p.getAll()));
                        } catch (Throwable ignored) {}
                    }
                    expires = now + 2000;
                }
            }
        }
        return snapshot;
    }
}
