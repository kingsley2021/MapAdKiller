package io.github.ldxm666.mapadkiller;

import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;

/** Blocks the operational map widget before it starts image/animation loading. */
final class AmapMapWidget {
    static void install(ClassLoader cl) {
        // ★ 安装期门控：map_promo 开着且首页没精简 ⇒ 不需要任何拦截，一条都不装（零成本）。
        // 只有「map_promo 关」或「compact 生效」时才需要装（compact 生效时画布不建，
        // 地图挂件也必须一起压住，否则它会从别的路径浮上来）。
        if (!AmapNativeHome.compact() && Config.visible(Config.K_MAP_PROMO)) {
            H.log("event=hook_skipped key=map_promo reason=switch_off");
            return;
        }
        Class<?> presenter = H.cls(cl, "com.autonavi.bundle.uitemplate.mapwidget.widget.activity.OperateActivityWidgetPresenter");
        Class<?> message = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.entity.AmapMessage");
        Class<?> type = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.entity.ActivityMsgType");
        Class<?> callback = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.listener.ActivityEventCallback");
        if (message == null || type == null || callback == null) return;
        H.hookSig(presenter, "showActivity", "amap_map_promo_start", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                // The map-side promo widget (ranking / campaign entry) is also fed by the
                // home canvas. Keep it down while the canvas is suppressed so a later
                // canvas rebuild cannot surface it above the switch state.
                if (AmapNativeHome.compact()) return false;
                return Config.visible(Config.K_MAP_PROMO) ? chain.proceed() : false;
            }
        }, message, java.util.HashMap.class, String.class, type, callback, Bundle.class);
        H.hookSig(presenter, "setWidgetVisible", "amap_map_promo_visibility", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!AmapNativeHome.compact() && Config.visible(Config.K_MAP_PROMO)) return chain.proceed();
                return chain.proceed(new Object[]{android.view.View.GONE, null});
            }
        }, int.class, message);
    }
}
