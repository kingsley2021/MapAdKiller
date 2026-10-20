package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本检查：打开设置页时自动去 GitHub Releases 拉最新版本号，比本机新就提示更新。
 *
 * 设计要点（都是真机/网络环境下必须的）：
 *   - **多源回退**：国内直连 `api.github.com` 常失败，按 {@link Version#UPDATE_SOURCES}
 *     依次尝试（官方 → kkgithub 镜像 → 两个反代），任一成功即止；
 *   - **6 小时节流**：自动检查最多 6 小时一次，手动点版本行可强制刷新；
 *   - **失败不打扰**：全部源都拿不到时只在界面上显示「未获取（点此重试）」，
 *     绝不弹错误弹窗（模块离线也必须完全可用）；
 *   - 结果缓存在本地 SharedPreferences，冷启动先显示上次结果再后台刷新。
 */
public final class UpdateChecker {

    public interface Callback {
        /**
         * @param latest   线上最新版本号（如 "1.1.0"）；null = 这次没查到
         * @param outdated true = 线上比本机新
         * @param source   命中的源（或最后一次的错误），便于诊断
         */
        void onResult(String latest, boolean outdated, String source);
    }

    private static final String TAG = "MapCleanUpdate";
    private static final String PREF = io.github.ldxm666.mapadkiller.App.UI_PREFS;
    private static final String K_AT = "ver_check_at";
    private static final String K_VER = "ver_latest";
    private static final String K_SRC = "ver_source";
    /** 已经用 Toast 提示过的新版本号，用于「每个新版本只提示一次」。 */
    private static final String K_TOASTED = "ver_toast_shown";
    private static final long THROTTLE_MS = 6L * 60 * 60 * 1000L;
    /** 自动检查的间隔：一天一次就够，失败也照样计时（避免每次启动都重试）。 */
    private static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000L;
    /**
     * 整轮检查的总时长上限。
     *
     * <p>8 个源是**串行**试的，单源 4s 连接 + 5s 读取，最坏 8×9s ≈ 72s。用户真机就卡在
     * 「检查中…」不出来。这道闸保证**任何情况下 12 秒内必出终态**。
     */
    private static final long DEADLINE_MS = 12L * 1000L;
    /**
     * 隐藏的「模拟新版」开关（默认不存在 = 关闭，不出现在任何可见界面）。
     *
     * <p>存在的唯一理由是可测试性：面板首次打开会真的后台查一次，立刻把手写进
     * {@code ver_latest} 的值覆盖回真实版本，于是「红色胶囊 + 新版本小标」这条分支
     * 在真机上**无法**靠改配置复现。置 true 时读取端直接返回 {@link #SIMULATED_VERSION}
     * 并跳过一切网络请求，让验证者纯靠配置就能测四样东西：红胶囊、小标、一次性 Toast、
     * 镜像下载按钮。
     */
    private static final String K_SIMULATE = "ver_simulate_new";
    /** 远高于任何真实版本，保证 {@link #compare} 一定判「有新版」。 */
    private static final String SIMULATED_VERSION = "9.9.9";
    /** 每进程只打一行 {@code event=update_simulated}。 */
    private static volatile boolean simLogged;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sRunning;
    /** 本次进程是否已经查过（节流只对同一次运行生效；冷启动一律重新查） */
    private static volatile boolean sThisProcess;

    private UpdateChecker() {}

    /** 上次查到的线上版本（可能为 null） */
    public static String latest(Context c) {
        String simulated = simulatedLatest(c);
        if (simulated != null) return simulated;
        try {
            return prefs(c).getString(K_VER, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 隐藏开关是否打开；打开时返回 {@link #SIMULATED_VERSION}，否则 null。
     *
     * <p>两个库都看：嵌入版里 {@code ver_*} 落在 {@code mapclean_embedded_ui}（宿主数据目录），
     * 独立模块进程里落在 {@code mak_ui}。测试员把同一个键写进任意一个都能复现，
     * 不必先判断当前是哪一种安装形态。
     */
    private static String simulatedLatest(Context c) {
        if (c == null) return null;
        boolean on = false;
        try {
            on = prefs(c).getBoolean(K_SIMULATE, false);
        } catch (Throwable ignored) {}
        if (!on) {
            try {
                on = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .getBoolean(K_SIMULATE, false);
            } catch (Throwable ignored) {}
        }
        if (!on) return null;
        if (!simLogged) {
            simLogged = true;
            Log.i(TAG, "event=update_simulated latest=" + SIMULATED_VERSION
                    + " local=" + Version.NAME);
        }
        return SIMULATED_VERSION;
    }

    public static String source(Context c) {
        if (simulatedLatest(c) != null) return "simulate";
        try {
            return prefs(c).getString(K_SRC, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean isOutdated(Context c) {
        String v = latest(c);
        return v != null && compare(v, Version.NAME) > 0;
    }

    /** Same question as {@link #isOutdated}, named for the call sites that ask it. */
    public static boolean hasNewVersion(Context c) {
        return isOutdated(c);
    }

    /**
     * 上次检查的时间（毫秒，0 = 从未查过）。
     *
     * <p>成功和失败都会写这个值（见 {@link #check}），所以「失败后每次启动都重试」不会发生。
     */
    public static long lastCheckAt(Context c) {
        try {
            return prefs(c).getLong(K_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 每天最多一次的后台自动检查。
     *
     * <p>节流：{@code now - lastCheckAt < 24h} 直接返回，不建线程、不发请求。
     * 计时用的是 {@code check()} 自己在结束时写入的 {@code K_AT}（成功/失败都写），
     * 所以「今天查过且失败」⇒ 明天之前不再重试，不会每次开 App 都白等一轮超时。
     *
     * <p>线程：复用 {@link #check} 自己的独立线程（{@code mapclean-update}），
     * 不占主线程、不用 Handler 轮询；重复触发由 {@code check()} 的 {@code sRunning} 兜住。
     *
     * <p>失败静默：只在日志里留一行，不 Toast、不改界面。
     */
    public static void maybeAutoCheck(final Context c) {
        if (c == null) return;
        if (simulatedLatest(c) != null) return;      // 模拟态：不发任何请求
        final Context app;
        try {
            app = c.getApplicationContext();
        } catch (Throwable t) {
            return;
        }
        if (app == null) return;
        long last = lastCheckAt(app);
        long now = System.currentTimeMillis();
        if (last > 0L && now - last < AUTO_INTERVAL_MS) return;
        Log.i(TAG, "event=update_auto_start last=" + last);
        check(app, true, new Callback() {
            @Override public void onResult(String latest, boolean outdated, String source) {
                if ("cache".equals(source)) return;      // 缓存回放，不是这次的结果
                if (latest == null) {
                    Log.w(TAG, "event=update_auto_failed source=" + source);
                } else if (outdated) {
                    Log.i(TAG, "event=update_auto_found latest=" + latest + " source=" + source);
                } else {
                    Log.i(TAG, "event=update_auto_latest latest=" + latest + " source=" + source);
                }
            }
        });
    }

    /**
     * 每个新版本只提示一次：返回 true 表示这次该提示（并已把版本号记下）。
     *
     * <p>去重键 {@code ver_toast_shown} 与 {@link #latest} 同库，所以清除数据后会重新提示一次，
     * 这正是期望行为。
     */
    public static boolean shouldNotify(Context c) {
        try {
            String v = latest(c);
            if (v == null || compare(v, Version.NAME) <= 0) return false;
            SharedPreferences p = prefs(c);
            if (v.equals(p.getString(K_TOASTED, null))) return false;
            p.edit().putString(K_TOASTED, v).commit();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已有一轮检查在跑（{@code check()} 会直接 return，回调也不会来）。 */
    public static boolean isChecking() {
        return sRunning;
    }

    /** 后台检查；force=false 时受 6 小时节流约束。回调在主线程。 */
    public static void check(final Context c, final boolean force, final Callback cb) {
        final Context app = c.getApplicationContext();
        // 模拟态：绝不发网络请求，直接在主线程把模拟结果交回去。
        // （不静默 return：那会让界面永远停在「检查中…」，验证者以为卡死了。）
        final String sim = simulatedLatest(app);
        if (sim != null) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    if (cb != null) cb.onResult(sim, true, "simulate");
                }
            });
            return;
        }
        String cached = latest(app);
        if (cached != null && cb != null) {
            // 先把缓存结果吐出去，界面立刻有内容
            final String cv = cached;
            MAIN.post(new Runnable() {
                @Override public void run() { cb.onResult(cv, compare(cv, Version.NAME) > 0, "cache"); }
            });
        }
        try {
            long at = prefs(app).getLong(K_AT, 0L);
            // ⚠ 只在同一次运行内节流：v2.0.0 实测「发了新版但用户开着 App 一直不提示」，
            //   因为 6 小时节流把冷启动的检查也挡掉了。冷启动必须查一次。
            if (!force && sThisProcess && System.currentTimeMillis() - at < THROTTLE_MS) return;
            sThisProcess = true;
        } catch (Throwable ignored) {}
        if (sRunning) return;
        sRunning = true;

        new Thread(new Runnable() {
            @Override public void run() {
                String found = null;
                String src = "";
                String err = "";
                final long started = System.currentTimeMillis();
                try {
                    for (int i = 0; i < Version.UPDATE_SOURCES.length; i++) {
                        // 总时长上限：8 个源串行，最坏情况必须也能在 DEADLINE_MS 内出终态。
                        // 少了这道闸，「卡在检查中」就会无限持续（用户真机截图）。
                        if (System.currentTimeMillis() - started > DEADLINE_MS) {
                            err = "deadline";
                            break;
                        }
                        String url = Version.UPDATE_SOURCES[i];
                        try {
                            String body = httpGet(url);
                            String v = parse(body);
                            if (v != null && v.length() > 0) {
                                found = v;
                                src = shortUrl(url);
                                break;
                            }
                            err = shortUrl(url) + " 解析失败";
                        } catch (Throwable t) {
                            err = shortUrl(url) + " " + t.getClass().getSimpleName();
                        }
                    }
                } finally {
                    sRunning = false;
                }
                try {
                    SharedPreferences.Editor e = prefs(app).edit();
                    e.putLong(K_AT, System.currentTimeMillis());
                    if (found != null) {
                        e.putString(K_VER, found);
                        e.putString(K_SRC, src);
                    } else {
                        e.putString(K_SRC, err);
                    }
                    e.commit();
                } catch (Throwable ignored) {}
                // 终态一定落地：成功 ⇒ latest/new，全失败/超时 ⇒ failed。
                // 这一行是「检查中永不结束」的判据：任何一轮都必须留下它。
                final String result = found == null ? "failed"
                        : (compare(found, Version.NAME) > 0 ? "new" : "latest");
                Log.i(TAG, "event=update_check_done result=" + result
                        + " elapsed=" + (System.currentTimeMillis() - started)
                        + " source=" + (found != null ? src : "none")
                        + (found != null ? (" latest=" + found) : (" err=" + err)));

                final String fv = found;
                final String fs = found != null ? src : err;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onResult(fv, fv != null && compare(fv, Version.NAME) > 0, fs);
                    }
                });
            }
        }, "mapclean-update").start();
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context c) {
        // Always compare Version.NAME (the embedded module), never the host map's PackageInfo.
        return c.getSharedPreferences(EmbeddedSettingsPolicy.isHostPackage(c.getPackageName())
                ? EmbeddedSettingsPolicy.UI_PREFS : PREF, Context.MODE_PRIVATE);
    }

    private static String shortUrl(String u) {
        try {
            String h = new URL(u).getHost();
            return h == null ? u : h;
        } catch (Throwable t) {
            return u;
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            // 拉的是 ~300 字节的 module.prop：4s 连接 + 5s 读取绰绰有余。
            // 原来的 6s/8s 乘以 8 个串行源 = 最坏 112 秒 —— 用户看到的就是「卡在检查中」。
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MapAdKiller/" + Version.NAME);
            conn.setRequestProperty("Accept", "application/vnd.github+json, */*");
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 512 * 1024) break;      // 防御：任何源都不该返回这么大
            }
            in.close();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final Pattern P_TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_NAME = Pattern.compile("\"name\"\\s*:\\s*\"[^\"]*?(\\d+\\.\\d+(?:\\.\\d+)*)\"");
    private static final Pattern P_VER = Pattern.compile("(?m)^\\s*version\\s*=\\s*(\\d+\\.\\d+(?:\\.\\d+)*)\\s*$");
    private static final Pattern P_ANY = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)*)");

    /** 从各种形状的响应里抠出版本号：GitHub JSON（tag_name/name）、module.prop 文本、纯版本号 */
    static String parse(String body) {
        if (body == null) return null;
        Matcher m = P_TAG.matcher(body);
        if (m.find()) return normalize(m.group(1));
        m = P_NAME.matcher(body);
        if (m.find()) return normalize(m.group(1));
        m = P_VER.matcher(body);
        if (m.find()) return normalize(m.group(1));
        String s = body.trim();
        if (s.length() < 40) {                       // 短响应才敢直接当版本号
            m = P_ANY.matcher(s);
            if (m.find()) return normalize(m.group(1));
        }
        return null;
    }

    /** tag 形如 "110-1.1.0" → 取最后一段版本号 */
    private static String normalize(String raw) {
        String s = raw.trim();
        int dash = s.lastIndexOf('-');
        if (dash >= 0 && dash < s.length() - 1) {
            String tail = s.substring(dash + 1).trim();
            if (tail.matches("\\d+(\\.\\d+)*")) s = tail;
        }
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    /** 语义化比较：a>b 返回 1，相等 0，a<b -1（位数不同按 0 补齐） */
    static int compare(String a, String b) {
        if (a == null || b == null) return 0;
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? toInt(x[i]) : 0;
            int yi = i < y.length ? toInt(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        return 0;
    }

    private static int toInt(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch >= '0' && ch <= '9') sb.append(ch);
                else break;
            }
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return 0;
        }
    }
}
