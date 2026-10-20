package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/** 在 Activity onResume 后延迟清扫视图树（异步/懒加载广告兜底）。API 102 hook。 */
public final class Sweeper {

    private Sweeper() {}

    public static void install(final ViewKiller killer) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            H.module.hook(onResume)
              .setId("sweep_activity")
              .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
              .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                  @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                      Object r = chain.proceed();
                      final Activity act = (Activity) chain.getThisObject();
                      Handler h = new Handler(Looper.getMainLooper());
                      // v2.1.7：只保留 300ms 一轮，砍掉 2500ms 那轮全树扫描。
                      //
                      // 腾讯侧广告已在**数据层**掐断：GDTADManager.initWith / isInitialized
                      // 返回 false、TGSplashAD.fetchAdOnly / preLoad 空转、gcd.a(ArrayList)
                      // 过滤服务端卡片。视图树上不会再长出广告子树，每多一轮全树递归都是
                      // 纯浪费 —— 用户反馈的"hook 卡顿"主要来自这里：地图是重视图树场景，
                      // 每次切页面回来都要多走一遍上千节点的递归。
                      // 真有懒加载广告冒出来，300ms 这一轮仍在。
                      h.postDelayed(new Runnable() {
                          @Override public void run() { killer.sweep(act); }
                      }, 300);
                      return r;
                  }
              });
            H.log(Log.INFO, MainHook.TAG, "sweeper installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "sweeper fail " + t);
        }
    }
}
