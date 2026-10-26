package io.github.ldxm666.mapadkiller;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 高德 15.21「我的」页数据源探针 —— <b>只打印，不过滤</b>。
 *
 * <h3>为什么单独一个类</h3>
 * 17.02 的「我的」页是 {@code /ws/shield/dsp/profile/index/nodefaasv3} 下发
 * {@code data.cardList[]}，用 {@code dataType} 区分区块（MineNewBEntranceCardV3/V4、
 * PopularActivitiesCardV4/V5、ContentCenterFeedCard…），过滤逻辑在
 * {@code AmapData.response()} 里。15.21 完全不是这一套：
 * <ul>
 *   <li>15.21 的 7 个 dex 里 <b>没有</b> {@code nodefaas}、{@code dsp/profile}、
 *       也没有任何一个 17.02 的 card 类型名；</li>
 *   <li>「我的」页是 AJX 页面 {@code path://amap_bundle_mine/src/pages/MinePage.page.js}；</li>
 *   <li>页面上的文案（互动专区/拆福袋/小德果园/每日签到/个性化导航）在 APK 的 dex、assets、
 *       oajx 里<b>全部搜不到</b> ⇒ 这些字是服务端下发的，不是本地资源。</li>
 * </ul>
 * 所以 15.21 必须重新找「哪个响应、哪些 key」。这个类的唯一职责就是把它们打印出来。
 *
 * <h3>怎么用</h3>
 * 在 {@code AmapHooks.install(ClassLoader)} 里加一行（本类不改 AmapHooks，避免并行冲突）：
 * <pre>
 *   feature("mine1521", new Runnable() { public void run() { AmapMine1521.install(cl); } });
 * </pre>
 * 然后打开高德 →「我的」页，抓 logcat：
 * <pre>
 *   adb logcat -d | grep -E 'mine1521'
 * </pre>
 *
 * <h3>会打印什么</h3>
 * <ul>
 *   <li>每一个 AJX 网络请求的 URL（全量，带 {@code url=} 前缀），mine/profile/user 相关的高亮；</li>
 *   <li>命中 URL 的响应体前 {@value #SNIPPET} 字符；</li>
 *   <li>响应里出现 {@code cardList} / {@code dataType} / {@code entranceList} 时，
 *       把每个 card 的 {@code dataType} 逐个列出来 —— 这就是过滤要用的 key。</li>
 * </ul>
 */
public final class AmapMine1521 {

    private static final String TAG = MainHook.TAG;
    private static final int SNIPPET = 1200;
    private static final int MAX_URL_LOGS = 400;

    /** 同一个回调对象 → 请求 URL，用于把响应配回请求。 */
    private static final Map<Object, String> PENDING =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    private AmapMine1521() {}

    public static void install(final ClassLoader cl) {
        String version = versionName(cl);
        if (version == null || !version.startsWith("15.21")) {
            H.log(Log.INFO, TAG, "event=mine1521 skip reason=version name=" + version);
            return;
        }
        AmapData.IS_1521 = true;
        int n = 0;
        n += hookRequest(cl);
        n += hookStorage(cl);
        H.log(Log.INFO, TAG, "event=mine1521 install hooks=" + n + " version=" + version);
    }

    // ── AJX 网络层 ────────────────────────────────────────────────────────────────
    // 15.21 里这三个类名和 17.02 一致（classes5.dex），所以探针可以直接复用同一组签名。
    private static int hookRequest(ClassLoader cl) {
        final Class<?> callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        Class<?> request = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest");
        if (request == null || callback == null) {
            H.log(Log.WARN, TAG, "event=mine1521 request_class_missing request=" + (request != null)
                    + " callback=" + (callback != null));
            return 0;
        }
        int n = 0;
        H.hookSig(request, "fetch", "mine1521_fetch", remember(), String.class, String.class, callback);
        n++;
        H.hookSig(request, "sendHttp", "mine1521_http", remember(), String.class, org.json.JSONObject.class, callback);
        n++;
        H.hookSig(request, "notifyJs", "mine1521_notifyjs", dump(), callback, int.class, int.class,
                String.class, String.class, int.class, String.class, String.class);
        n++;
        H.hookSig(request, "notifyResponse", "mine1521_notifyresp", dump(), callback, int.class,
                org.json.JSONObject.class, String.class, int.class, String.class);
        n++;
        return n;
    }

    /** 记下 url → callback，并把 URL 打出来。 */
    private static XposedInterface.Hooker remember() {
        return new XposedInterface.Hooker() {
            private int logged;
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    String url = urlOf(chain.getArg(0), chain.getArg(1));
                    Object cb = chain.getArgs().get(chain.getArgs().size() - 1);
                    if (url != null && cb != null) PENDING.put(cb, url);
                    if (url != null && logged < MAX_URL_LOGS) {
                        logged++;
                        boolean hot = hot(url);
                        if (hot || logged <= 40) {
                            H.log(Log.INFO, TAG, "event=mine1521 url" + (hot ? "_hot" : "") + "=" + url);
                        }
                    }
                } catch (Throwable t) {
                    H.log(Log.WARN, TAG, "event=mine1521 url_error err=" + t);
                }
                return chain.proceed();
            }
        };
    }

    /** 先按 15.21 的 dataType 过滤，再把关键结构打出来。 */
    private static XposedInterface.Hooker dump() {
        return new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object[] args = chain.getArgs().toArray();
                String body = bodyOf(java.util.Arrays.asList(args));
                if (body != null && body.contains("\"cardList\"") && body.contains("MineNew")) {
                    try {
                        String out = AmapData.response(
                                "/ws/shield/dsp/profile/index/nodefaasv3", body, AmapData.CURRENT);
                        if (out != null && !out.equals(body)) {
                            for (int i = 0; i < args.length; i++) {
                                if (args[i] instanceof String && ((String) args[i]).length() > 200) {
                                    args[i] = out;
                                    break;
                                }
                            }
                            H.log(Log.INFO, TAG, "event=mine1521_filtered before="
                                    + body.length() + " after=" + out.length());
                            body = out;
                        }
                    } catch (Throwable t) {
                        H.log(Log.WARN, TAG, "event=mine1521_filter_err err=" + t.getClass().getSimpleName());
                    }
                }
                try {
                    if (body != null && (body.contains("cardList") || body.contains("dataType")
                            || body.contains("entranceList"))) {
                        H.log(Log.INFO, TAG, "event=mine1521 resp len=" + body.length()
                                + " cardTypes=" + cardTypes(body));
                    }
                } catch (Throwable ignored) { }
                return chain.proceed(args);
            }
        };
    }

    // ── AJX 本地存储：页面常把「上次渲染的卡片」缓存下来，顺手看一眼 ──────────────
    private static int hookStorage(ClassLoader cl) {
        Class<?> storage = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage");
        if (storage == null) return 0;
        H.hookSig(storage, "getItemSync", "mine1521_storage", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                try {
                    String key = String.valueOf(chain.getArg(0)) + "/" + chain.getArg(1);
                    if (r instanceof String && hot(key) && ((String) r).length() < SNIPPET) {
                        H.log(Log.INFO, TAG, "event=mine1521 storage key=" + key + " value=" + r);
                    }
                } catch (Throwable ignored) {}
                return r;
            }
        }, String.class, String.class);
        return 1;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private static boolean hot(String url) {
        if (url == null) return false;
        String s = url.toLowerCase();
        return s.contains("mine") || s.contains("profile") || s.contains("usercenter")
                || s.contains("user_center") || s.contains("personal") || s.contains("activity")
                || s.contains("dsp") || s.contains("badge") || s.contains("voice");
    }

    private static String urlOf(Object a, Object b) {
        if (a instanceof String) return (String) a;
        if (b instanceof String) return (String) b;
        return null;
    }

    private static String bodyOf(java.util.List<Object> args) {
        for (Object a : args) {
            if (a instanceof String) {
                String s = (String) a;
                if (s.length() > 2 && (s.startsWith("{") || s.startsWith("["))) return s;
            }
            if (a instanceof org.json.JSONObject) return a.toString();
        }
        return null;
    }

    private static String topKeys(String body) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(body);
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(it.next());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 把 data.cardList[].dataType 全量列出来 —— 这就是过滤要用的 key。 */
    private static String cardTypes(String body) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(body);
            org.json.JSONObject data = o.optJSONObject("data");
            org.json.JSONArray cards = data == null ? null : data.optJSONArray("cardList");
            if (cards == null) cards = o.optJSONArray("cardList");
            if (cards == null) return "none";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cards.length(); i++) {
                org.json.JSONObject c = cards.optJSONObject(i);
                if (c == null) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(c.optString("dataType", "?"));
            }
            return sb.length() == 0 ? "empty" : sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String versionName(ClassLoader cl) {
        try {
            android.content.pm.PackageInfo p =
                    io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_AMAP);
            return p == null ? null : p.versionName;
        } catch (Throwable t) {
            return null;
        }
    }
}
