package io.github.ldxm666.mapadkiller;

import android.util.Log;

/**
 * 模块 App 进程（设置页那个普通进程）专用日志。
 *
 * <p>{@link H} 里有 {@code XposedInterface.Hooker} 的静态常量，类初始化需要 libxposed API，
 * 而设置页进程是普通 App 进程，类路径里没有那套 API —— 一碰 {@code H.log(...)} 就是
 * {@code NoClassDefFoundError}，连带把调用方（例如「重新抓取」）整个打断，用户看到的就是
 * 「请求失败」。这里只用 android.util.Log，绝不引用任何 libxposed 类型。</p>
 */
public final class SafeLog {

    private SafeLog() { }

    public static void i(String tag, String msg) { p(Log.INFO, tag, msg, null); }

    public static void w(String tag, String msg) { p(Log.WARN, tag, msg, null); }

    public static void w(String tag, String msg, Throwable t) { p(Log.WARN, tag, msg, t); }

    public static void e(String tag, String msg, Throwable t) { p(Log.ERROR, tag, msg, t); }

    private static void p(int priority, String tag, String msg, Throwable t) {
        try {
            if (t == null) Log.println(priority, tag, msg == null ? "" : msg);
            else Log.println(priority, tag, (msg == null ? "" : msg) + " : " + t);
        } catch (Throwable ignored) {
        }
    }
}
