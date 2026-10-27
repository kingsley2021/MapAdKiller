package io.github.ldxm666.mapadkiller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 接收宿主进程（高德）发来的抓取状态回报，落进模块 App 自己的本地存储。
 *
 * <p>这条通道只承载「抓到没 / 抓了几个文件 / 失败原因」这类文本状态，不含任何权限操作。
 * 设置页每次心跳都会读一遍，所以宿主抓完的下一秒首页那一行就会变。
 */
public final class CaptureReportReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) return;
            SharedPreferences sp = context.getSharedPreferences(CaptureReport.P, Context.MODE_PRIVATE);
            String stage = intent.getStringExtra("stage");
            boolean captured = intent.getBooleanExtra("captured", false);
            SharedPreferences.Editor e = sp.edit()
                    .putBoolean(CaptureReport.K_KNOWN, true)
                    .putString(CaptureReport.K_STAGE, stage)
                    .putBoolean(CaptureReport.K_CAPTURED, captured)
                    .putInt(CaptureReport.K_FILES, intent.getIntExtra("files", 0))
                    .putString(CaptureReport.K_VERSION, intent.getStringExtra("version"))
                    .putLong(CaptureReport.K_AT, intent.getLongExtra("ts", System.currentTimeMillis()));
            if (CaptureReport.S_DONE.equals(stage)) e.remove(CaptureReport.K_ERROR);
            else if (intent.getStringExtra("error") != null) e.putString(CaptureReport.K_ERROR, intent.getStringExtra("error"));
            e.commit();
            Log.i("MapCleanCapture", "event=capture_report_recv stage=" + stage
                    + " captured=" + captured);
        } catch (Throwable t) {
            Log.w("MapCleanCapture", "capture report receive failed: " + t);
        }
    }
}
