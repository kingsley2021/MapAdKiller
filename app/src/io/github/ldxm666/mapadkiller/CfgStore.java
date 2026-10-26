package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置持久化层（内置版 LSPatch 的救命通道）。
 *
 * <h3>为什么需要它</h3>
 * 15.21 免 root 内置版里，LSPatch 的 {@code getRemotePreferences} 走
 * {@code /data/data/<host>/databases/lspatch-xposed-remote.db}。实测在真机上会出现
 * {@code SQLiteCantOpenDatabaseException}（DB 被旧 uid 占用 / 文件锁 / 权限变化），
 * 此时：
 * <pre>
 *   event=cfg_write_fail key=tab_探索 svc=ok stage=svc reason=io err=SQLiteCantOpenDatabaseException
 *   event=cfg_write_fail key=tab_探索 svc=ok stage=prefs reason=commit_false
 *   event=cfg_write_ok   key=tab_探索 via=module_prefs   ← 只是内存态
 * </pre>
 * 结果就是「面板里关掉 → 当场生效 → 重进恢复原样」。
 *
 * <h3>策略</h3>
 * <ul>
 *   <li>内置版：**本地 {@code mapclean_cfg} 为准**（宿主自己的 SharedPreferences，永远可写），
 *       远程只做 best-effort 镜像；读取时 local 打底、remote 覆盖（remote 可用时以它为准，
 *       保持与 LSPosed 模块版一致）。</li>
 *   <li>模块版（LSPosed）：保持原来的 remote 优先逻辑，不受影响。</li>
 *   <li>每次写入都做 write→read 自校验，不一致打 {@code event=cfg_roundtrip_fail}。</li>
 * </ul>
 */
final class CfgStore {
    private CfgStore() {}

    static final String LOCAL_PREFS = "mapclean_cfg";

    static SharedPreferences local() {
        try {
            Context c = Config.hostCtx();
            if (c == null) return null;
            return c.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    static SharedPreferences remote() {
        try {
            if (H.module == null) return null;
            return H.module.getRemotePreferences(Config.PREF_GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 内置版（LSPatch）判定：mapclean.App.embedded()，反射调用避免编译期依赖。 */
    static boolean embedded() {
        // ① 宿主 APK 的 assets/lspatch/config.json —— 最早可用、不依赖 App 是否已 attach
        try {
            if (HostInfo.isEmbedded()) return true;
        } catch (Throwable ignored) {}
        // ② App.embedded()（attachEmbedded 之后才为 true）
        try {
            Class<?> c = Class.forName("io.github.ldxm666.mapclean.App");
            Object v = c.getMethod("embedded").invoke(null);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 合并读：local 打底，remote 覆盖（remote 读不到就只用 local）。 */
    static Map<String, Object> readAll() {
        Map<String, Object> out = new HashMap<String, Object>();
        SharedPreferences lp = local();
        if (lp != null) {
            try { out.putAll(lp.getAll()); } catch (Throwable ignored) {}
        }
        SharedPreferences rp = remote();
        if (rp != null) {
            try { out.putAll(rp.getAll()); } catch (Throwable ignored) {}
        }
        return out;
    }

    static boolean read(String key, boolean fallback) {
        try {
            Map<String, Object> all = readAll();
            Object v = all.get(key);
            return v instanceof Boolean ? (Boolean) v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 写布尔键：内置版以 local 为准 + remote 镜像；模块版 remote 优先 + local 镜像。
     * 返回「至少一个后端 commit 成功」。
     */
    static boolean write(String key, boolean value) {
        boolean emb = embedded();
        boolean okLocal = commit(local(), key, value);
        boolean okRemote = commit(remote(), key, value);
        boolean ok = emb ? okLocal : (okRemote || okLocal);
        // write→read 自校验：用与读取完全相同的合并路径再读一次
        try {
            boolean back = read(key, !value);
            if (back != value) {
                H.log(Log.WARN, MainHook.TAG, "event=cfg_roundtrip_fail key=" + key
                        + " want=" + value + " got=" + back + " local=" + okLocal + " remote=" + okRemote);
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=cfg_write key=" + key + " value=" + value
                        + " backend=" + (emb ? "local" : "remote") + " local=" + okLocal
                        + " remote=" + okRemote + " commit=" + ok);
            }
        } catch (Throwable ignored) {}
        return ok;
    }

    private static boolean commit(SharedPreferences p, String key, boolean value) {
        if (p == null) return false;
        try {
            return p.edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cfg_write_fail key=" + key
                    + " backend=" + (p == local() ? "local" : "remote")
                    + " err=" + t.getClass().getSimpleName());
            return false;
        }
    }
}
