package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface;

/** Exact signatures verified from the installed 17.00.0.2005 DEX via JADX MCP. */
public final class AmapProtocolHooks {
    private static final Map<Object, Boolean> sessions = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static volatile ClassLoader sHostLoader;
    private static final Map<Object, String> requests = Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final java.util.Set<String> reportedCards = Collections.synchronizedSet(new java.util.HashSet<String>());
    private AmapProtocolHooks() {}

    public static boolean compatible(ClassLoader cl) {
        PackageInfo p = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_AMAP);
        H.log("amap_adapter version=" + (p == null ? "unknown" : p.versionName) + " mode=capabilities");
        return true;
    }

    public static void install(final ClassLoader cl) {
        installStream(cl);
        installTools(cl);
        installResponses(cl);
        installIntentProbe(cl);
        installNavUriFix(cl);
        H.hookSig(H.cls(cl, "com.autonavi.inter.impl.HomeTabInitConfigFactory"), "obtainTabList", "amap_tabs", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object original = chain.proceed();
                if (!(original instanceof List)) return original;
                List<?> source = (List<?>) original;
                List<Object> out = new ArrayList<Object>();
                for (Object tab : source) {
                    String id = String.valueOf(tab);
                    String label = "Nearby".equals(id) ? "探索" : "Message".equals(id) ? "长按说话"
                        : "Taxi".equals(id) ? "打车" : "Mine".equals(id) ? "我的" : null;
                    if (label == null || Config.tabVisible(label)) out.add(tab);
                }
                return out.size() == source.size() ? original : out;
            }
        }, Context.class);
    }

    private static void installStream(final ClassLoader cl) {
        Class<?> session = H.cls(cl, "com.autonavi.bundle.vui.llm.d");
        final Class<?> callback = H.cls(cl, "com.autonavi.bundle.vui.api.ILLMSession$Callback");
        if (callback == null) return;
        H.hookSig(session, "setSceneId", "amap_stream_scope", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                sessions.put(chain.getThisObject(), "home_qs".equals(chain.getArg(0)));
                return chain.proceed();
            }
        }, String.class);
        H.hookSig(session, "start", "amap_stream_start", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final Object target = chain.getArg(0);
                H.log("amap_stream scoped=" + Boolean.TRUE.equals(sessions.get(chain.getThisObject())));
                if (target == null || !Boolean.TRUE.equals(sessions.get(chain.getThisObject()))) return chain.proceed();
                Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{callback}, new InvocationHandler() {
                    @Override public Object invoke(Object self, Method method, Object[] args) throws Throwable {
                        if ("onMessageReceived".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof JSONObject) {
                            JSONObject source = (JSONObject) args[0];
                            JSONObject result = source;
                            try { result = AmapData.stream(source, AmapData.CURRENT); }
                            catch (Exception e) { diagnose("stream", e); }
                            JSONObject component = source.optJSONObject("component");
                            JSONObject extra = component == null ? null : component.optJSONObject("extra_info");
                            String name = extra == null ? "terminal" : extra.optString("cardName");
                            if (reportedCards.add(name)) H.log("amap_stream card=" + name + " keep=" + (result != null));
                            if (result == null) return null;
                            args = new Object[]{result};
                        }
                        try { return method.invoke(target, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    }
                });
                return chain.proceed(new Object[]{proxy});
            }
        }, callback);
    }

    private static void installTools(ClassLoader cl) {
        Class<?> storage = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage");
        H.hookSig(storage, "getItemSync", "amap_tool_storage", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (!"toolbox".equals(chain.getArg(0)) || !(result instanceof String)) return result;
                try { return AmapData.storage((String) chain.getArg(1), (String) result, AmapData.CURRENT); }
                catch (Exception e) { diagnose("tool_storage", e); return result; }
            }
        }, String.class, String.class);
        final Class<?> callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        if (callback != null && storage != null) {
            try {
                final Method callbackMethod = callback.getMethod("callback", Object[].class);
                final Method getItem = storage.getMethod("getItemSync", String.class, String.class);
                H.hookSig(storage, "getItemASYNC", "amap_tool_async", new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!"toolbox".equals(chain.getArg(0)) || chain.getArg(2) == null) return chain.proceed();
                        Object value;
                        try { value = getItem.invoke(chain.getThisObject(), chain.getArg(0), chain.getArg(1)); }
                        catch (Exception e) { diagnose("tool_async_read", e); return chain.proceed(); }
                        try { callbackMethod.invoke(chain.getArg(2), new Object[]{new Object[]{value}}); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                        return null;
                    }
                }, String.class, String.class, callback);
            } catch (Exception e) { diagnose("tool_async_signature", e); }
        }
        H.hookSig(H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl"), "getToolboxData", "amap_tool_boot", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (!(result instanceof JSONObject)) return result;
                try {
                    // fill=true: this is the payload the home toolbar actually renders, and it
                    // is the only place where switching a tool on may ADD a cell.
                    JSONObject out = AmapData.toolbox((JSONObject) result, AmapData.CURRENT, true);
                    if (out != (Object) result) H.log("amap_tool_boot slots=" + AmapData.slotIds(out));
                    return out;
                }
                catch (Exception e) { diagnose("tool_boot", e); return result; }
            }
        });
    }

    private static void installResponses(ClassLoader cl) {
        Class<?> request = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest");
        Class<?> callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        if (callback == null) return;
        XposedInterface.Hooker register = new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object options = chain.getArg(1);
                    JSONObject j = options instanceof JSONObject ? (JSONObject) options : new JSONObject((String) options);
                    String path = AmapData.requestPath(j.optString("url"));
                    if (AmapData.handles(path) && chain.getArg(2) != null) requests.put(chain.getArg(2), path);
                } catch (Exception ignored) {}
                return chain.proceed();
            }
        };
        H.hookSig(request, "fetch", "amap_request_fetch", register, String.class, String.class, callback);
        H.hookSig(request, "sendHttp", "amap_request_http", register, String.class, JSONObject.class, callback);
        H.hookSig(request, "notifyJs", "amap_request_response", response(true), callback, int.class, int.class, String.class, String.class, int.class, String.class, String.class);
        H.hookSig(request, "notifyResponse", "amap_http_response", response(false), callback, int.class, JSONObject.class, String.class, int.class, String.class);
    }
    private static XposedInterface.Hooker response(final boolean xhr) {
        return new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (xhr && !Integer.valueOf(4).equals(chain.getArg(2))) return chain.proceed();
                String path = requests.remove(chain.getArg(0));
                Object raw = chain.getArg(3);
                if (path == null || !(raw instanceof String) || !Integer.valueOf(200).equals(chain.getArg(1))) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                try { args[3] = AmapData.response(path, (String) raw, AmapData.CURRENT); }
                catch (Exception e) { diagnose("response", e); return chain.proceed(); }
                return chain.proceed(args);
            }
        };
    }
    private static void diagnose(String area, Exception e) {
        if (Config.debugLog()) H.log("amap_adapter area=" + area + " error=" + e.getClass().getSimpleName());
    }

    // ── 外部 intent 落点探针（Bug1：冷启动导航跳转只开首页、不进导航选择页）───────────
    // 只观测，不改行为。每个落点打一条 event=nav_probe 日志，一次实机即可判定 intent 断在哪环。
    //
    // 17.02 实测链路（classes5 / classes6，锚点见 amap_17.02.0.1541_registry.md）：
    //   androidamap://navi?… → SchemeHandleActivity.onCreate 复制 intent 后
    //     setComponent("com.autonavi.map.activity.SplashActivity") + startActivity
    //   ★ "SplashActivity" 是 <activity-alias>，targetActivity = NewMapActivity
    //     ⇒ 反射 "com.autonavi.map.activity.SplashActivity" 取类必然 NOT FOUND，
    //       探针只能挂 NewMapActivity 这一侧（本类 NewMapActivity 自身没有覆写
    //       onCreate/onNewIntent，全部逻辑在 presenter com.autonavi.minimap.lite.a 里）。
    //   NewMapActivity.solveScheme(Intent) → lite.a.solveScheme → IntentController.f(intent,cb)
    //     → IntentController.c(intent,cb) 才是真正分发（f 内部分支可丢弃并回 callback(FALSE)）。
    //   lite.a.<init> / onNewIntent 把 launch intent 存进字段 b，dispatchIntent 再取出送分发。
    //   ⇒ 冷启动丢页只可能是「b 没存 / dispatchIntent 没走到 / f 内部分支丢弃」三者之一，
    //     下面这些探针点正好覆盖三种可能，配合 ctl_f→ctl_c 是否成对出现即可一眼定位。
    private static void installIntentProbe(final ClassLoader cl) {
        sHostLoader = cl;
        Class<?> presenter = H.cls(cl, "com.autonavi.minimap.lite.a");
        Class<?> controller = H.cls(cl, "com.autonavi.minimap.intent.IntentController");
        int n = 0;
        n += probeAll(presenter, "<init>", "presenter_init");
        n += probeAll(presenter, "solveScheme", "solveScheme");
        n += probeAll(presenter, "onNewIntent", "presenter_onNewIntent");
        n += probeAll(presenter, "dispatchIntent", "dispatchIntent");
        n += probeAll(presenter, "loadPage", "loadPage");
        n += probeAll(controller, "f", "ctl_f");
        n += probeAll(controller, "c", "ctl_c");
        n += probeAll(controller, "d", "ctl_d");
        n += probeAll(controller, "g", "ctl_g");
        // 外部 scheme 的真实入口：私有 scheme 与通用 geo/地图链接都声明在这个 activity 上。
        Class<?> entry = H.cls(cl, "com.autonavi.map.activity.SchemeHandleActivity");
        n += probeAll(entry, "onCreate", "scheme_entry");
        H.log("event=nav_probe_install hooks=" + n
                + " presenter=" + (presenter != null) + " controller=" + (controller != null)
                + " entry=" + (entry != null));
    }

    /** 按方法名挂该类全部同名重载（不猜签名），返回挂上的个数。 */
    private static int probeAll(Class<?> c, String name, final String tag) {
        if (c == null || H.module == null) return 0;
        int n = 0;
        try {
            if ("<init>".equals(name)) {
                for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                    if (attach(k, tag, 0)) n++;
                }
                return n;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (attach(m, tag, m.getParameterTypes().length)) n++;
            }
        } catch (Throwable t) {
            H.log("event=nav_probe_error tag=" + tag + " err=" + t);
        }
        return n;
    }

    private static boolean attach(java.lang.reflect.Executable e, String tag, int arity) {
        try {
            e.setAccessible(true);
            H.module.hook(e)
                    .setId("nav_probe_" + tag + "_" + arity)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(probeHook(tag));
            return true;
        } catch (Throwable t) {
            H.log("event=nav_probe_attach_failed tag=" + tag + " err=" + t.getClass().getSimpleName());
            return false;
        }
    }

    private static XposedInterface.Hooker probeHook(final String tag) {
        return new XposedInterface.Hooker() {
            private int hits;
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object intent = null;
                Object[] args = chain.getArgs().toArray();
                for (Object a : args) {
                    if (a instanceof android.content.Intent) { intent = a; break; }
                }
                // onCreate(Bundle) 这类入口没有 Intent 参数，直接读宿主的 getIntent()。
                if (intent == null && chain.getThisObject() instanceof android.app.Activity) {
                    try { intent = ((android.app.Activity) chain.getThisObject()).getIntent(); }
                    catch (Throwable ignored) {}
                }
                final String before = describe(intent);
                // 默认只留首 2 次（每次启动约 20 行，够判定断点）；设置页打开调试日志后放全。
                final boolean verbose = hits < (Config.debugLog() ? 12 : 2);
                if (verbose) H.log("event=nav_probe tag=" + tag + " phase=enter " + before);
                Object result = chain.proceed();
                if (verbose) {
                    hits++;
                    H.log("event=nav_probe tag=" + tag + " phase=exit ret=" + brief(result)
                            + " launch=" + launchRecord()
                            + " at=" + frame());
                }
                return result;
            }
        };
    }

    private static String describe(Object intent) {
        if (!(intent instanceof android.content.Intent)) return "intent=null";
        android.content.Intent i = (android.content.Intent) intent;
        StringBuilder sb = new StringBuilder("action=").append(i.getAction());
        sb.append(" data=").append(i.getDataString());
        try {
            android.os.Bundle b = i.getExtras();
            if (b == null) sb.append(" extras=null");
            else {
                sb.append(" extras={");
                boolean first = true;
                for (String k : b.keySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(k);
                }
                sb.append('}');
            }
        } catch (Throwable t) {
            sb.append(" extras=?");
        }
        return sb.toString();
    }

    private static String brief(Object o) {
        if (o == null) return "null";
        if (o instanceof Boolean || o instanceof Number || o instanceof String) return String.valueOf(o);
        return o.getClass().getSimpleName();
    }

    /** com.amap.bundle.utils.app.LaunchRecord.h 是宿主保存的启动 Uri，冷启动是否丢页看它最直接。 */
    private static String launchRecord() {
        try {
            Class<?> c = H.cls(sHostLoader, "com.amap.bundle.utils.app.LaunchRecord");
            if (c == null) return "?";
            java.lang.reflect.Field f = c.getDeclaredField("h");
            f.setAccessible(true);
            Object v = f.get(null);
            return v == null ? "null" : v.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String frame() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("io.github.libxposed") || cn.startsWith("java.lang.Thread")) continue;
                if (cn.startsWith("io.github.ldxm666.mapadkiller.AmapProtocolHooks")) continue;
                if (n++ > 0) sb.append('<');
                sb.append(cn).append('.').append(e.getMethodName());
                if (n >= 5) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ── 外部导航跳转 URI 归一化（只补缺失参数，绝不覆盖已有值）───────────────────────
    //
    // 为什么在 SchemeHandleActivity.onCreate 上做：它是外部 scheme 的唯一入口
    // （androidamap:// / amapuri:// 在 manifest 里都指向它），onCreate 里先
    // new Intent(原intent) → setComponent(别名 SplashActivity) → startActivity。
    // 我们在 proceed 之前改 Activity.getIntent()，宿主后面复制到的就是改好的那份，
    // 冷/热启动两条路都覆盖到，且不需要碰任何高德业务类。
    //
    // 为什么这是「无复现证据下唯一站得住的修复」：实测证明高德自身的分发链是好的
    // （nav_probe 里 intent 完整送到 IntentController.c），所以问题只可能出在
    // 「送进来的 URI 本身参数不全」——缺 dev / dname / t 时高德解析不出终点，
    // 只能停在首页。补缺失参数不改变任何既有值，最坏情况等于没改。
    private static final java.util.Set<String> sNavSkipLogged =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void installNavUriFix(final ClassLoader cl) {
        Class<?> entry = H.cls(cl, "com.autonavi.map.activity.SchemeHandleActivity");
        if (entry == null || H.module == null) {
            H.log("event=nav_uri_fix_install hooks=0 found=" + (entry != null));
            return;
        }
        int n = 0;
        for (Method m : entry.getDeclaredMethods()) {
            if (!"onCreate".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || !android.os.Bundle.class.equals(ps[0])) continue;
            try {
                m.setAccessible(true);
                H.module.hook(m).setId("nav_uri_fix")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                try { normalizeNav(chain.getThisObject()); }
                                catch (Throwable t) {
                                    H.log("event=nav_uri_fix applied=false reason=exception err=" + t);
                                }
                                return chain.proceed();
                            }
                        });
                n++;
            } catch (Throwable t) {
                H.log("event=nav_uri_fix_install hook_failed err=" + t);
            }
        }
        H.log("event=nav_uri_fix_install hooks=" + n + " enabled=" + Config.visible(Config.K_NAV_URI_FIX));
    }

    private static void normalizeNav(Object self) {
        if (!(self instanceof android.app.Activity)) return;
        android.app.Activity act = (android.app.Activity) self;
        android.content.Intent in = act.getIntent();
        if (in == null) { navSkip("no_data", null); return; }
        if (!android.content.Intent.ACTION_VIEW.equals(in.getAction())) { navSkip("not_external_nav", null); return; }
        final android.net.Uri data = in.getData();
        if (data == null) { navSkip("no_data", null); return; }
        final String raw = data.toString();
        String scheme = lower(data.getScheme());

        // ── 通用地图 intent（系统选择器里点「高德地图」走的就是这一支）─────────────
        // 其他 App 发的是 geo:/pois:/wechatnav:/maps.google.com 这类**通用** URI，
        // 系统弹选择器后把原样 intent 交给高德；高德自己只会把它当「打开地图」，
        // 冷启动时停在首页。这里把它改写成高德自己的路线规划 URI。
        if ("geo".equals(scheme) || "pois".equals(scheme) || "wechatnav".equals(scheme)
                || (("http".equals(scheme) || "https".equals(scheme)) && isMapHost(lower(data.getHost())))) {
            normalizeGeneric(act, in, data, raw);
            return;
        }
        if (!"androidamap".equals(scheme) && !"amapuri".equals(scheme)) { navSkip("scheme_unsupported", raw); return; }
        // 模块自己发的 toolbox 跳转（amapuri://ajx?path=path://amap_bundle_toolbox/…）一律不碰。
        if (raw.contains("path://")) { navSkip("not_external_nav", raw); return; }
        String host = lower(data.getHost());
        String path = lower(data.getPath());
        boolean route = contains(host, "route") || contains(path, "route");
        boolean navi = contains(host, "navi") || contains(path, "navi");
        if (!route && !navi) { navSkip("not_external_nav", raw); return; }
        if (!Config.visible(Config.K_NAV_URI_FIX)) { navSkip("switch_off", raw); return; }

        java.util.Set<String> have = new java.util.HashSet<String>();
        try { have.addAll(data.getQueryParameterNames()); } catch (Throwable ignored) {}

        android.net.Uri.Builder b = data.buildUpon();
        int added = 0;
        if (!have.contains("dev")) { b.appendQueryParameter("dev", "0"); added++; }
        if (!have.contains("sourceApplication")) {
            b.appendQueryParameter("sourceApplication", "MapAdKiller");
            added++;
        }
        boolean hasCoord = have.contains("lat") || have.contains("lon")
                || have.contains("dlat") || have.contains("dlon");
        if (!have.contains("dname") && hasCoord) {
            String name = firstNonEmpty(data.getQueryParameter("poiname"),
                    data.getQueryParameter("name"), data.getQueryParameter("dname"));
            b.appendQueryParameter("dname", name == null ? "目的地" : name);
            added++;
        }
        if (route && !have.contains("t")) { b.appendQueryParameter("t", "0"); added++; }

        if (added == 0) {
            H.log("event=nav_uri_fix applied=false reason=already_ok data=" + raw);
            return;
        }
        android.net.Uri fixed = b.build();
        android.content.Intent out = new android.content.Intent(in);
        out.setData(fixed);
        act.setIntent(out);
        H.log("event=nav_uri_fix applied=true added=" + added
                + " before=" + raw + " after=" + fixed);
    }

    // ── 通用地图 intent 归一化（geo: / pois: / wechatnav: / Google·高德 地图链接）──────
    //
    // 用户在系统「选择要打开的方式」里点高德时，高德收到的就是这一支：通用地图 URI。
    // 高德 manifest 里这些 scheme 全部声明在 SchemeHandleActivity 上（geo/pois/wechatnav/
    // http(s) 的 12 个 host），所以入口和私有 scheme 是同一个类，钩子不用换。
    // 高德自身只会把它当「打开地图」，不解析终点 ⇒ 停在首页。
    // 这里把经纬度/名称抽出来，改写成高德自己的 amapuri://route/plan/ 路线规划 URI。
    private static final String[] MAP_HOSTS = {
            "amap.com", "m.amap.com", "wb.amap.com", "f.amap.com", "mo.amap.com", "dypx.amap.com",
            "amap.onelink.me", "surl.test.myyamap.com",
            "maps.google.com", "maps.google.cn", "ditu.google.com", "ditu.google.cn"
    };

    private static final java.util.regex.Pattern COORD_PAIR = java.util.regex.Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)");

    private static final java.util.regex.Pattern PAREN_NAME = java.util.regex.Pattern.compile(
            "\\(([^)]{1,80})\\)");

    private static boolean isMapHost(String host) {
        if (host == null || host.length() == 0) return false;
        for (String h : MAP_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    private static void normalizeGeneric(android.app.Activity act, android.content.Intent in,
                                         android.net.Uri data, String raw) {
        if (!Config.visible(Config.K_NAV_URI_FIX)) { navSkip("switch_off", raw); return; }
        double[] p = parseCoord(data);
        if (p == null) { navSkip("no_coord", raw); return; }
        String name = parseName(data);
        if (name == null || name.length() == 0) name = "目的地";
        android.net.Uri fixed;
        try {
            fixed = android.net.Uri.parse("amapuri://route/plan/?dlat=" + fmtCoord(p[0])
                    + "&dlon=" + fmtCoord(p[1])
                    + "&dname=" + android.net.Uri.encode(name)
                    + "&dev=0&t=0");
        } catch (Throwable t) {
            navSkip("parse_error", raw);
            return;
        }
        android.content.Intent out = new android.content.Intent(in);
        out.setData(fixed);
        act.setIntent(out);
        H.log("event=nav_uri_fix applied=true kind=generic before=" + raw + " after=" + fixed);
    }

    /** 从通用地图 URI 里抽 (lat, lon)；抽不出或越界返回 null。 */
    private static double[] parseCoord(android.net.Uri data) {
        String s = data.toString();
        int qm = s.indexOf('?');
        String pathPart = qm >= 0 ? s.substring(0, qm) : s;
        int colon = pathPart.indexOf(':');
        String body = colon >= 0 ? pathPart.substring(colon + 1) : pathPart;
        // ★ wechatnav://type=nav&fromcoord=..&tocoord=.. 这种**没有 `?`** 的形式：
        //   参数全在 authority（`//` 之后）里。以前 queryPart 直接取空串 ⇒ 一个参数都读不到
        //   ⇒ 日志 `skip reason=no_coord`，修复完全不生效。这里把 authority 当 query 用。
        String queryPart;
        if (qm >= 0) {
            queryPart = s.substring(qm + 1);
        } else {
            queryPart = body.startsWith("//") ? body.substring(2) : "";
        }

        // ★ 0) 终点专用键，优先级最高。
        //   微信 `wechatnav://type=nav&fromcoord=<起点>&tocoord=<终点>&from=..&to=..`
        //   以前没认 tocoord，就回落到「正文里第一个坐标对」= fromcoord（起点）⇒ 终点变成
        //   用户当前位置，路线显示「1 分钟 6 米」。这是实测踩到的真 bug，务必保持 tocoord 优先。
        for (String key : new String[]{"tocoord", "toCoord", "destcoord", "endcoord", "to"}) {
            String v = queryValue(queryPart, key);
            if (v == null) continue;
            double[] q = pair(v);
            if (q != null && !(q[0] == 0d && q[1] == 0d)) return q;
        }

        // 1) geo:lat,lng 主体（仅限 geo: —— 这类正文本身就是终点坐标）。
        //    ⚠ wechatnav / pois 的正文是 `k=v&k=v`，第一个坐标对通常是 **fromcoord（起点）**，
        //    绝不能拿来当终点。这条限制就是上面那个 bug 的根因。
        String scheme = data.getScheme() == null ? "" : data.getScheme().toLowerCase(java.util.Locale.US);
        if (!"wechatnav".equals(scheme) && !"pois".equals(scheme)) {
            double[] p0 = pair(body);
            if (p0 != null && !(p0[0] == 0d && p0[1] == 0d)) return p0;
        }

        // 2) query 里的坐标：q / ll / daddr / saddr / position / center / latlng / dlat
        for (String key : new String[]{"q", "ll", "daddr", "saddr", "position", "center", "latlng", "dlat"}) {
            String v = queryValue(queryPart, key);
            if (v == null) continue;
            double[] p = pair(v);
            if (p != null && !(p[0] == 0d && p[1] == 0d)) return p;
        }
        // 3) 分开写的 lat/lon、dlat/dlon、latitude/longitude
        Double lat = num(queryValue(queryPart, "lat"), queryValue(queryPart, "latitude"),
                queryValue(queryPart, "dlat"));
        Double lon = num(queryValue(queryPart, "lon"), queryValue(queryPart, "lng"),
                queryValue(queryPart, "longitude"), queryValue(queryPart, "dlon"));
        if (lat != null && lon != null) {
            double[] out = {lat, lon};
            return valid(out) ? out : null;
        }
        return null;
    }

    private static String parseName(android.net.Uri data) {
        String s = data.toString();
        int qm = s.indexOf('?');
        String queryPart;
        if (qm >= 0) {
            queryPart = s.substring(qm + 1);
        } else {
            int colon = s.indexOf(':');
            String body = colon >= 0 ? s.substring(colon + 1) : s;
            queryPart = body.startsWith("//") ? body.substring(2) : "";   // 同 parseCoord：authority 当 query
        }
        for (String key : new String[]{"dname", "to", "toname", "toName", "q", "name", "title", "daddr", "saddr", "position"}) {
            String v = queryValue(queryPart, key);
            if (v == null || v.length() == 0) continue;
            java.util.regex.Matcher m = PAREN_NAME.matcher(v);
            if (m.find()) return m.group(1).trim();
            if (!COORD_PAIR.matcher(v).find()) return v.trim();   // 纯文字（地址）
        }
        return null;
    }

    /** 取 query 里某个键的原始（已解码）值；手工解析，geo: 是不透明 URI，getQueryParameter 拿不到。 */
    private static String queryValue(String query, String key) {
        if (query == null || query.length() == 0) return null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            String k = eq >= 0 ? kv.substring(0, eq) : kv;
            if (!key.equals(k)) continue;
            String v = eq >= 0 ? kv.substring(eq + 1) : "";
            try { return android.net.Uri.decode(v); } catch (Throwable t) { return v; }
        }
        return null;
    }

    private static double[] pair(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = COORD_PAIR.matcher(text);
        if (!m.find()) return null;
        try {
            double[] out = {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
            return valid(out) ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean valid(double[] p) {
        if (p == null) return false;
        return p[0] >= -90d && p[0] <= 90d && p[1] >= -180d && p[1] <= 180d
                && !(Math.abs(p[0]) < 1e-9 && Math.abs(p[1]) < 1e-9);
    }

    private static Double num(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v == null || v.length() == 0) continue;
            try { return Double.valueOf(v.trim()); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String fmtCoord(double v) { return String.format(java.util.Locale.US, "%.6f", v); }

    private static void navSkip(String reason, String raw) {
        // 每个原因每个进程只记一次，避免每次启动都刷屏；打开调试日志后全量记录。
        if (Config.debugLog() || sNavSkipLogged.add(reason)) {
            H.log("event=nav_uri_fix skip reason=" + reason + (raw == null ? "" : " data=" + raw));
        }
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(); }

    private static boolean contains(String s, String part) { return s != null && s.contains(part); }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && v.length() > 0) return v;
        }
        return null;
    }
}
