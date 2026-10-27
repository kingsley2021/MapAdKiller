package io.github.ldxm666.mapadkiller;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 阿里妈妈（Alimama）广告请求层 —— 高德主 APK 里**唯一**的真·广告 SDK 接入点。
 *
 * <h3>为什么单独立一个类</h3>
 * 17.02 的 8 个 DEX 里**没有任何第三方广告 SDK**（穿山甲 / 优量汇 / 快手 / 百度 / MBridge /
 * Sigmob / TopOn / TradPlus / 北子 / 美数 / 章鱼 / 风铃 / 趣盟 全部 0 命中，见
 * {@code adsdk-impl.md} 的厂商前缀扫描表）。高德的广告走**自有运营协议**（首页卡片、
 * 我的页卡List、POI 详情 WaterFeed、地图标注 —— 已由 {@code AmapData}/{@code AmapPlaceData}/
 * {@code AmapPoiLayer} 在数据层剥离），唯一"SDK 形态"的广告通道就是这里的阿里妈妈。
 *
 * <h3>DEX 证据（17.02.0.1541，classes6.dex）</h3>
 * <pre>
 * Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;        单例（getInstance），7 个方法
 *   public sendSplashScreen(SplashScreenRequest, falcon.base.a, AosResponseCallback)V   ← 开屏广告请求（实现）
 *   public sendSplashScreen(SplashScreenRequest, AosResponseCallback)V                  ← 2 参转发到上面
 *   public sendH5Log(H5LogRequest, falcon.base.a, AosResponseCallback)V                 ← 广告曝光/点击埋点（实现）
 *   public sendH5Log(H5LogRequest, AosResponseCallback)V                                ← 2 参转发到上面
 *   public cancel(AosRequest)V
 * Lcom/autonavi/minimap/alimama/param/SplashScreenRequest;   super=AosPostRequest
 *   &lt;clinit&gt;: const-string "aos_url" + "ws/valueadded/alimama/splash_screen" → 静态字段 E
 * Lcom/autonavi/minimap/alimama/param/H5LogRequest;          super=AosPostRequest
 *   &lt;clinit&gt;: const-string "h5_log_url" + "ws/h5_log?" → 静态字段 L
 * </pre>
 * 调用方（{@code --InvokeXref} 全库只有这两处）：
 * <ul>
 *   <li>{@code Lzg5;->run()V}（classes6，code_off=0x408894）→ {@code sendSplashScreen(3 参)}。
 *       该 run 的形态是「拿锁 → 发请求 → 埋点 → finally 解锁」，
 *       **解锁在调用之后**，所以这里 return 掉不会把锁卡死；</li>
 *   <li>{@code Lcom/autonavi/minimap/track/b;->e(...)V}（classes6）→ {@code sendH5Log(2 参)}，
 *       静态 void 打点函数，调用方不等返回值。</li>
 * </ul>
 *
 * <h3>为什么挂 3 参重载而不是 2 参</h3>
 * 2 参是纯转发（体内唯一一条调用就是 3 参版）。挂实现版本一次覆盖两个入口，
 * 也不会因为"挂错了重载"而静默失效（{@code notifyJs} 那次事故就是这么来的）。
 *
 * <h3>风险与回滚</h3>
 * 两个开关都在设置面板里，默认 true（=已拦截）：
 * <ul>
 *   <li>{@code alimama_splash_off} —— 关掉后开屏广告请求照发（与旧版一致）；</li>
 *   <li>{@code alimama_log_off} —— 关掉后广告埋点照传。</li>
 * </ul>
 * 唯一需要真机确认的副作用：拦截开屏广告请求后，若宿主有一个"等广告回调"的开屏容器，
 * 它可能等满超时再放行 ⇒ 冷启动变慢。开屏显示本身已由 {@code canShowSplash=false} 挡掉，
 * 所以理论上不会有容器在等；这一条**必须真机确认**（见 subprocess-impl.md 的验证步骤）。
 */
final class AmapAlimama {

    private static final String CLS_HOLDER = "com.autonavi.minimap.alimama.AlimamaRequestHolder";
    private static final String CLS_SPLASH_REQ = "com.autonavi.minimap.alimama.param.SplashScreenRequest";
    private static final String CLS_H5LOG_REQ = "com.autonavi.minimap.alimama.param.H5LogRequest";
    private static final String CLS_FALCON = "com.autonavi.minimap.falcon.base.a";
    private static final String CLS_CALLBACK = "com.amap.bundle.aosservice.response.AosResponseCallback";

    private AmapAlimama() {}

    static void install(final ClassLoader cl) {
        // ★ 安装期门控（两条各自独立）：关掉的那条**根本不装**，零成本。
        final boolean splashOn = Config.installGate(Config.K_ALIMAMA_SPLASH_OFF);
        final boolean logOn = Config.installGate(Config.K_ALIMAMA_LOG_OFF);
        if (!splashOn && !logOn) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_splash_off+alimama_log_off"
                    + " reason=switch_off");
            return;
        }
        try {
            Class<?> holder = H.cls(cl, CLS_HOLDER);
            Class<?> splash = H.cls(cl, CLS_SPLASH_REQ);
            Class<?> h5log = H.cls(cl, CLS_H5LOG_REQ);
            Class<?> falcon = H.cls(cl, CLS_FALCON);
            Class<?> callback = H.cls(cl, CLS_CALLBACK);
            if (holder == null || falcon == null || callback == null) {
                // 不猜替代品：类名对不上就只留痕，绝不去 hook 名字相近的类。
                H.log(Log.WARN, MainHook.TAG, "event=anchor_miss anchor=AlimamaRequestHolder"
                        + " holder=" + (holder != null) + " falcon=" + (falcon != null)
                        + " callback=" + (callback != null));
                return;
            }
            if (splashOn) {
                H.hookSig(holder, "sendSplashScreen", "amap_alimama_splash",
                        block("sendSplashScreen", "ws/valueadded/alimama/splash_screen"),
                        splash, falcon, callback);
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_splash_off reason=switch_off");
            }
            if (logOn) {
                H.hookSig(holder, "sendH5Log", "amap_alimama_h5log",
                        block("sendH5Log", "ws/h5_log"),
                        h5log, falcon, callback);
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_log_off reason=switch_off");
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=alimama_failed reason=" + t.getClass().getSimpleName());
        }
    }

    /**
     * 两个目标方法都是 {@code void} ⇒ 短路只有一种正确写法：{@code return null}（不 proceed）。
     *
     * <p>不能"proceed 之后再返回"：请求已经在 {@code proceed} 里发出去了，拦截就没有意义。
     *
     * <p><b>2026-10-27 真机事故修复</b>：只 return 不回调 ⇒ 开屏永久卡死。
     * 调用方 {@code zg5.run()} 拿到 {@code a76} 锁后把回调
     * {@code com.autonavi.minimap.net.Callback} 传进来，**锁只在 onSuccess/onFailure 里释放**
     * （{@code a76.m77b()}）。把请求吞掉就再也没有回调 ⇒ 锁永不释放 ⇒ 宿主一直停在
     * SplashActivity（实测点图标 4 分钟不进首页）。所以短路后必须补一次
     * {@code onFailure(request, null)}：Callback.onFailure 只做埋点 + 释放锁，不碰异常对象，
     * 传 null 安全。
     */
    private static XposedInterface.Hooker block(final String label, final String endpoint) {
        return new XposedInterface.Hooker() {
            private boolean reported;
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    if (!reported) {
                        reported = true;
                        H.log(Log.INFO, MainHook.TAG, "event=alimama_blocked method=" + label
                                + " endpoint=" + endpoint);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    releaseCallback(chain.getArgs().toArray());
                } catch (Throwable ignored) {
                }
                // 装上就无条件短路（安装期门控已决定装不装），拦截器里零 Config 读。
                return null;
            }
        };
    }

    /**
     * 补一次 {@code onFailure(AosRequest, AosResponseException)}：释放宿主开屏锁。
     * 全反射调用，不依赖宿主类在编译期可见。
     */
    private static void releaseCallback(Object[] args) {
        if (args == null || args.length == 0) return;
        Object request = args[0];
        for (Object a : args) {
            if (a == null) continue;
            java.lang.reflect.Method m = null;
            try {
                for (java.lang.reflect.Method cand : a.getClass().getMethods()) {
                    if (!"onFailure".equals(cand.getName())) continue;
                    Class<?>[] ps = cand.getParameterTypes();
                    if (ps != null && ps.length == 2) { m = cand; break; }
                }
                if (m == null) continue;
                m.setAccessible(true);
                m.invoke(a, request, null);
                H.log(Log.INFO, MainHook.TAG, "event=alimama_callback_released cb="
                        + a.getClass().getName());
            } catch (Throwable ignored) {
            }
        }
    }
}
