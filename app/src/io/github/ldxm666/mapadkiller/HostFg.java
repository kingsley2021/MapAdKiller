package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 进程内前台判定。
 *
 * <p>原来的 isForeground() 只看自己进程的 importance：别的应用（包括本模块自己的设置页）
 * 全屏盖上来时高德主进程必然掉到 SERVICE/CACHED，于是被判成后台；反过来后台跑前台服务
 * （importance=125）又会被判成前台，可能让车在库里也自动进巡航。这里改用 framework 的
 * ActivityLifecycleCallbacks 自维护 resumed 集合：只有宿主真的有 Activity 处于 resumed
 * 才算前台，完全不受别的进程影响。</p>
 *
 * <p>注册时机：AmapCruise.BOOT 拿到 Application 后立刻注册。若注册前已经有页面 resume 过
 * （热启动），everResumed 仍为 false，此时回落到旧的 isForeground() 兜底。</p>
 */
public final class HostFg {

    private static final Map<Activity, Boolean> RESUMED =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());

    private static volatile boolean everResumed;
    private static volatile String top = "-";

    private HostFg() {}

    static void attach(final Application app) {
        if (app == null) return;
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) {
                    RESUMED.put(a, Boolean.TRUE);
                    everResumed = true;
                    top = a.getClass().getName();
                    // 模块版（普通 LSPosed 安装）没有悬浮窗，FloatingHooks 一条 hook 都不装，
                    // 所以「设置页点了抓取云数据 → 宿主下次 resume 自己重启进采集」只能在这里接。
                    // 内置版也走同一条（CaptureGate 内部每进程只判定一次，重复调用无害）。
                    try {
                        io.github.ldxm666.mapclean.CaptureGate.applyRecaptureRequest(a);
                    } catch (Throwable ignored) { }
                }
                @Override public void onActivityPaused(Activity a) { RESUMED.remove(a); }
                @Override public void onActivityStopped(Activity a) { RESUMED.remove(a); }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) { RESUMED.remove(a); }
            });
            H.log(Log.INFO, MainHook.TAG, "event=hostfg_attach app=" + app.getClass().getName());
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=hostfg_attach failed err=" + t);
        }
    }

    /** 宿主是否在前台。 */
    public static boolean fg() {
        if (!everResumed) return AmapCruise.isForeground();
        return !RESUMED.isEmpty();
    }

    /** 最近一次 resume 的 Activity 类名，日志用。 */
    public static String top() {
        return top;
    }
}
