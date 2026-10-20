package io.github.ldxm666.bmapclean;

import android.view.View;
import java.util.List;

/** Native homepage promotions, verified against Baidu Map 22.0.0 (1650). */
final class BaiduAdHooks {
    private static final String PRESENTERS = "com.baidu.baidumaps.aihome.map.presenter.";

    private BaiduAdHooks() {}

    static void install(ClassLoader cl) {
        Class<?> yellow = H.cls(cl, PRESENTERS + "YellowBannerPresenter");
        // Prevent both ordinary notices and carousel notices before inflation/timer creation.
        BaiduHooks.hook(yellow, "showYellowBanner", chain -> null,
                H.cls(cl, "com.baidu.baidumaps.base.yellowbanner.e"));
        BaiduHooks.hook(yellow, "showSwitcherBanner", chain -> null, List.class);
        BaiduHooks.hook(yellow, "tryShowYellowBanner", chain -> null);
        BaiduHooks.hook(yellow, "showYbBannerAnim", chain -> null);
        BaiduHooks.hook(yellow, "onResumeForYB", chain -> null);
        BaiduHooks.hook(yellow, "initViews", chain -> {
            Object result = chain.proceed();
            BaiduHooks.hide((View) BaiduHooks.field(chain.getThisObject(), "mYbView"));
            return result;
        });

        Class<?> operation = H.cls(cl, PRESENTERS + "HomeMapOperatePresenter");
        // V22/coin activity icon: suppress ordinary and splash-linked entry creation.
        BaiduHooks.hook(operation, "showEventEntry", chain -> null, boolean.class);
        // 22.0.50 混淆名迁移：该重载的事件参数类由 xe.c 换成 fe.c。
        // 反编译实证（tools/dex_query.py --Class Lcom/baidu/.../HomeMapOperatePresenter; --Methods）：
        //   HomeMapOperatePresenter 只声明了 onEventMainThread(Lfe/c;)V，
        //   没有 onEventMainThread(Lxe/c;)V；fe.c = { <init>(SplashLinkageModel), a()SplashLinkageModel }
        //   —— 正是开屏联动图标（现金签到入口）的事件载体。
        // 旧代码传 H.cls(cl,"xe.c")：类还在（classes10.dex），但该类型上没有此重载 →
        // getDeclaredMethod 抛 NoSuchMethodException → H.hook(null) 记 miss。
        // 这就是 install_done miss=1 的那一条（logcat: miss baidu_onEventMainThreadHomeMapOperatePresenter）。
        BaiduHooks.hook(operation, "onEventMainThread", chain -> null, H.cls(cl, "fe.c"));
        BaiduHooks.hook(operation, "setEventEntryVisibleForYB", chain ->
                chain.proceed(new Object[]{false}), boolean.class);
        BaiduHooks.hook(operation, "onResume", chain -> {
            Object result = chain.proceed();
            BaiduHooks.hide((View) BaiduHooks.field(chain.getThisObject(), "mEventEntryView"));
            return result;
        });
    }
}
