package io.github.ldxm666.mapclean;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 合并版（v2.0.0）里**唯一真正的 Application**。
 *
 * 原来 BMapClean 与 MapAdKiller 各自有一个 `App extends Application`，一个 APK 只能有一个，
 * 所以这里做转发：本类负责向 LSPosed 注册监听，拿到服务后喂给静态持有者，
 * 设置页代码因此一行都不用改（仍然读自己的 RemotePreferences group）：
 *   - io.github.ldxm666.mapadkiller.App → group `amap_enhancer_config`
 *
 * 免 root 内置版（v2.2.0 起）里只并入高德部分，因此不再转发百度侧。
 */
public final class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "MapCleanApp";

    private static volatile Application self;

    private static final FrameworkStatus.Monitor status = new FrameworkStatus.Monitor(
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread worker = new Thread(task, "MapClean-framework-status");
                    worker.setDaemon(true);
                    return worker;
                }
            }), new FrameworkStatus.Clock() {
                @Override public long elapsedMillis() { return android.os.SystemClock.elapsedRealtime(); }
            }, 5000);
    private static volatile XposedService boundService;
    private static FrameworkStatus.Source boundStatusSource;
    private static boolean listenerRegistered;
    private static volatile boolean embedded;
    /** 默认配置的自动应用每次进程只触发一次（真正的「是否已应用」判断在 DefaultConfig）。 */
    private static boolean defaultsRequested;

    private static final XposedServiceHelper.OnServiceListener embeddedListener =
            new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(XposedService service) { bindService(service); }
                @Override public void onServiceDied(XposedService service) { diedService(service); }
            };

    public static FrameworkStatus.Snapshot frameworkStatus() { return status.snapshot(); }
    public static void refreshFrameworkStatus() { status.refresh(); }
    public static boolean embedded() { return embedded; }
    static boolean hasEmbeddedService() { return boundService != null; }

    /**
     * The framework service this process is bound to, or {@code null}.
     *
     * <p>Package-private on purpose: it exists so {@link FloatingConfig} can read and write
     * the orb's own int-valued appearance keys without naming a vendor package (the other
     * three holders live in packages that {@code tools/build_variant.py} drops from the
     * shipped APK). Callers must handle {@code null} -- the bind is asynchronous.
     */
    static XposedService service() { return boundService; }

    /** LSPatch delivers its writable Binder after modules load, before the host Application. */
    static void listenEmbedded() { registerListener(embeddedListener); }

    /** Reuse the attached host Application; never construct an Application with an unset base. */
    public static synchronized void attachEmbedded(Application host) {
        if (host == null || !EmbeddedSettings.enabledFor(host.getPackageName())) {
            throw new IllegalStateException("Embedded settings require the LSPatch host");
        }
        if (self == host && embedded && listenerRegistered) return;
        self = host;
        embedded = true;
        io.github.ldxm666.mapadkiller.App.attach(host);
        // In an integrated build this method -- not onCreate -- is what runs, because the host
        // Application is the map's own, never the module's. So every vendor holder has to be
        // attached here too, or that vendor's orb has no service to write through.
        io.github.ldxm666.bmapclean.App.attach(host);
        io.github.ldxm666.tmapclean.App.attach(host);
        XposedService service = boundService;
        if (service != null) {
            try { io.github.ldxm666.mapadkiller.App.onBind(service); } catch (Throwable ignored) {}
            try { io.github.ldxm666.bmapclean.App.onBind(service); } catch (Throwable ignored) {}
            try { io.github.ldxm666.tmapclean.App.onBind(service); } catch (Throwable ignored) {}
        }
        registerListener(embeddedListener);
        // 免 root 内置模式下服务可能先到、宿主 Application 后到：这条路径同样要把默认配置
        // 应用一次，否则 attachEmbedded 之后就没有第二次机会了（标记键保证不会重复应用）。
        if (service != null) applyDefaultsOnce();
    }

    private static synchronized void registerListener(XposedServiceHelper.OnServiceListener listener) {
        if (listenerRegistered) return;
        try {
            XposedServiceHelper.registerListener(listener);
            listenerRegistered = true;
            Log.i(TAG, "registerListener ok");
        } catch (Throwable t) {
            Log.e(TAG, "registerListener failed", t);
        }
    }

    private static final class ServiceStatusSource implements FrameworkStatus.Source {
        private final XposedService service;
        ServiceStatusSource(XposedService service) { this.service = service; }
        @Override public int getApiVersion() { return service.getApiVersion(); }
        @Override public String getFrameworkName() { return service.getFrameworkName(); }
        @Override public String getFrameworkVersion() { return service.getFrameworkVersion(); }
        @Override public List<String> getScope() { return service.getScope(); }
    }

    public static Application app() { return self; }

    public static Context ctx() { return self; }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        io.github.ldxm666.mapadkiller.App.attach(this);
        // 百度那一半同样要拿到 Application：它的诊断/回报读取走的是自己的 SharedPreferences，
        // 少了这一步 bmapclean.App.app() 恒为 null，「最近一次规则执行」「累计回报」永远是空。
        io.github.ldxm666.bmapclean.App.attach(this);
        // 腾讯那一半同理：tmapclean.App.app() 为 null 时，它的诊断读取与 token 生成全失效。
        io.github.ldxm666.tmapclean.App.attach(this);
        registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService s) { bindService(s); }

    private static synchronized void bindService(XposedService s) {
        Log.i(TAG, "onServiceBind ok");
        // Grep-able form for a verification run: this line plus `event=cfg_write_ok via=svc`
        // is the whole proof that the framework write path is alive.
        Log.i(TAG, "event=svc_bind ok");
        boundService = s;
        boundStatusSource = new ServiceStatusSource(s);
        status.bind(boundStatusSource);
        try { io.github.ldxm666.mapadkiller.App.onBind(s); } catch (Throwable t) { Log.w(TAG, "amap bind", t); }
        // 百度组（bmapclean_config）的读写全靠这一句。少了它，设置页里百度那一页的每个开关
        // 都会写失败并弹「LSPosed 服务未连接」—— 用户看到的就是这个。
        try { io.github.ldxm666.bmapclean.App.onBind(s); } catch (Throwable t) { Log.w(TAG, "baidu bind", t); }
        // 腾讯组（tmapclean_config）的读写全靠这一句。
        try { io.github.ldxm666.tmapclean.App.onBind(s); } catch (Throwable t) { Log.w(TAG, "tencent bind", t); }
        // 每天最多一次的后台自动检查：只要用户今天开过一次高德，当天就会查
        // （24h 节流在 UpdateChecker 内部，失败也计时，不会每次启动都重试）。
        try {
            if (self != null) UpdateChecker.maybeAutoCheck(self);
        } catch (Throwable t) {
            Log.w(TAG, "auto update check failed to start", t);
        }
        // 自带默认配置：服务可用之后才有写入口（写配置必须走 XposedService），所以第一次
        // 应用挂在这里；标记键存在时内部直接跳过，不会覆盖用户改过的配置。
        applyDefaultsOnce();
    }

    /**
     * 全新安装后自动应用一次模块内置的默认配置。
     *
     * <p>只在服务可用之后调用（见 {@link #bindService} 与 {@link #attachEmbedded}）。
     * 真正的判断与写入在 {@link DefaultConfig}：标记键 {@code _defaults_applied} 存在即跳过。
     * 放到后台线程是因为 {@code commit()} 是同步落盘，不能占住主线程。
     */
    static void applyDefaultsOnce() {
        synchronized (App.class) {
            if (defaultsRequested) return;
            defaultsRequested = true;
        }
        try {
            Thread worker = new Thread(new Runnable() {
                @Override public void run() {
                    try { DefaultConfig.applyOnce(); } catch (Throwable ignored) { }
                }
            }, "MapClean-defaults");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            Log.w(TAG, "defaults worker failed to start", t);
        }
    }

    @Override
    public void onServiceDied(XposedService s) { diedService(s); }

    private static synchronized void diedService(XposedService s) {
        Log.w(TAG, "onServiceDied");
        if (boundService == s) {
            status.disconnected(boundStatusSource);
            boundStatusSource = null;
            boundService = null;
        }
        try { io.github.ldxm666.mapadkiller.App.onDied(s); } catch (Throwable ignored) {}
        try { io.github.ldxm666.bmapclean.App.onDied(s); } catch (Throwable ignored) {}
        try { io.github.ldxm666.tmapclean.App.onDied(s); } catch (Throwable ignored) {}
    }
}
