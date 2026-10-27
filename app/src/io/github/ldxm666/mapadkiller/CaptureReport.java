package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 抓取状态回报通道（宿主进程 → 设置页）。
 *
 * <p><b>为什么不用 RemotePreferences 回写</b>：libxposed API 102 在 hook 侧拿到的
 * RemotePreferences 是<b>只读实现</b>，{@code edit().commit()} 必抛
 * {@code UnsupportedOperationException: Read only implementation}（真机 logcat 实测，
 * 与 {@code bmapclean.Report} 里记录的是同一个坑）。所以宿主进程抓完数据**没法**把
 * {@code baseline_captured_v<code>} 写回给设置页看 —— 这也是用户看到「抓取一直失败、
 * 首页一直显示未抓取」的直接原因。
 *
 * <p>方向反过来就行：<b>宿主进程发一条显式广播，模块 App 收到后落到自己的
 * {@code capture_report} prefs</b>。设置页只读这份本地记录，不再依赖 root（模块 App
 * 不在 KernelSU 白名单时 {@code su} 必失败）。
 */
public final class CaptureReport {

    /** 与 AndroidManifest 里 {@code CaptureReportReceiver} 的 action 一致。 */
    public static final String ACTION = "io.github.ldxm666.mapadkiller.CAPTURE_STATUS";

    /** 接收端落盘用的 prefs 名。 */
    public static final String P = "capture_report";

    public static final String K_KNOWN = "known";
    public static final String K_STAGE = "stage";
    public static final String K_CAPTURED = "captured";
    public static final String K_FILES = "files";
    public static final String K_VERSION = "version";
    public static final String K_AT = "at";
    public static final String K_ERROR = "error";

    /** stage 取值：state=启动时上报现状，done=抓取完成，failed=抓取失败，pending=面板已请求。 */
    public static final String S_STATE = "state";
    public static final String S_DONE = "done";
    public static final String S_FAILED = "failed";
    public static final String S_PENDING = "pending";

    private static volatile Context sCtx;

    private CaptureReport() {}

    /** 记下宿主 Application（抓取线程里没有 Activity，用它发包）。 */
    public static void attach(Context ctx) {
        try {
            if (ctx != null) sCtx = ctx.getApplicationContext();
        } catch (Throwable ignored) { }
    }

    /**
     * 发一条状态回报。失败只记日志，绝不影响抓取本身。
     *
     * @param stage  {@link #S_STATE} / {@link #S_DONE} / {@link #S_FAILED}
     * @param files  抓到的配置文件数
     * @param error  失败原因（可为 null）
     */
    public static void send(Context ctx, String stage, boolean captured, int files,
                            String version, String error) {
        Context c = ctx != null ? ctx : sCtx;
        if (c == null) return;
        try {
            Intent i = new Intent(ACTION);
            i.setPackage(MainHook.PKG_SELF);
            i.putExtra("stage", stage);
            i.putExtra("captured", captured);
            i.putExtra("files", files);
            i.putExtra("version", version == null ? "" : version);
            i.putExtra("error", error == null ? "" : error);
            i.putExtra("ts", System.currentTimeMillis());
            c.sendBroadcast(i);
            H.log(Log.INFO, MainHook.TAG, "event=capture_report stage=" + stage
                    + " captured=" + captured + " files=" + files);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_report_failed err=" + t);
        }
    }
}
