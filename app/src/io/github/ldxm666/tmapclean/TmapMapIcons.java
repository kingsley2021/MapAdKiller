package io.github.ldxm666.tmapclean;

import android.util.Log;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 主页地图图标开关 —— 地图上那一条悬浮图标控件。
 *
 * <h3>先说清楚这个开关到底关掉什么</h3>
 * 腾讯地图主页地图上叠着两类东西，<b>来源完全不同</b>：
 * <ol>
 *   <li><b>地图引擎画的兴趣点图标与名称</b>（竹林寺 / 图书馆 / 幺哥子辣卤老火锅…）。
 *       它们由原生引擎按样式资源绘制：实证 11.7.0 里只有
 *       {@code map_icon_4.1} / {@code map_icon_version}（classes9.dex，SDK 配置数据）与
 *       {@code map_icon_poi_8_7_}（classes20.dex，{@code com.tencent.map.init.tasks.optional.ConfigUpdateTask}
 *       远程下发的配置键）这几个字面量，<b>Java 层没有对应的开关</b>。
 *       高德侧能做到是因为模块自带原生库改写样式 protobuf；腾讯这边属于同类工作，本开关不覆盖它。</li>
 *   <li><b>应用层叠在地图上的图标控件</b>（图层 / 消息 / 上报 / 路况 / 2D-3D / 指南针 / 加减号 /
 *       定位 / 截图 / 分享 / 昼夜 / 版权 / 比例尺）。这些是普通 Android View，
 *       由 {@code com.tencent.map.api.view.MapBaseView} 摆放 —— <b>本开关关掉的就是这一组</b>。</li>
 * </ol>
 *
 * <h3>锚点（实证：真机 11.7.0 base.apk，classes19.dex）</h3>
 * <pre>
 *   com.tencent.map.api.view.MapBaseView（RelativeLayout，386 个方法）
 *       getToolBar()Lcom/tencent/map/api/view/ToolBar;
 *       getSceneSwitch()Lcom/tencent/map/api/view/MapSceneSwitchView;
 *       getCompass()Landroid/widget/ImageView;      getZoomView()Lcom/tencent/map/api/view/ZoomView;
 *       getLocateBtn()Lcom/tencent/map/api/view/LocateBtn;
 *       getScreenshotView()Landroid/widget/ImageView;
 *       getShare()/getShareView()Lcom/tencent/map/api/view/ThemeShareView;
 *       getDayNightView()Landroid/widget/ImageView;  getCopyrightView()Landroid/widget/ImageView;
 *       getScale()/getScaleView()Lcom/tencent/map/api/view/ScaleView;
 *       showViews()V   updateZoomStatus()V/updateZoomStatus(Z)V/updateZoomStatus(ZZ)V
 *       setToolBarElementVisible()V   setCompassVisibility(I)V   showZoomContral()V
 *       isHomePage()Z            ← 腾讯自己判定的"是不是主页"，用来把作用域限制在主页
 *
 *   com.tencent.map.api.view.ToolBar（RelativeLayout，64 个方法）
 *       setVisible(Landroid/view/View;I)V          ← 工具栏自己显示/隐藏图标的唯一收口
 *       getLayerBtn()/getLayerBtnGroup()  getMessageBtn()/getMessageBtnGroup()
 *       getReportBtn()/getReportBtnGroup()  getTrafficBtn()/getTrafficBtnGroup()
 * </pre>
 *
 * <p>做法分两处：{@code ToolBar.setVisible} 是工具栏图标的总闸，直接把要显示的那个改成 GONE；
 * 其余图标（指南针 / 缩放 / 图层切换 / 定位 / 截图…）在 {@code MapBaseView} 每次重排之后统一收口，
 * 否则下一次 {@code updateZoomStatus} 会把它们又摆回来。
 */
final class TmapMapIcons {

    private static final String BASE = "com.tencent.map.api.view.MapBaseView";
    private static final String TOOLBAR = "com.tencent.map.api.view.ToolBar";

    /** 逐个图标取用；名字全部实证自 11.7.0 的 MapBaseView 方法表 */
    private static final String[] ICON_GETTERS = {
            "getToolBar", "getSceneSwitch", "getCompass", "getZoomView",
            "getLocateBtn", "getScreenshotView", "getShare", "getShareView",
            "getDayNightView", "getCopyrightView", "getScale", "getScaleView",
    };

    /** MapBaseView 每次重排图标之后都会被调用；挂这些点才能保证不被摆回来 */
    private static final String[] RESHOW_HOOKS = {
            // Initialization and explicit toolbar reconfiguration only. Zoom updates
            // run during every map gesture and do not create the native POI labels.
            "showViews", "setToolBarElementVisible",
    };

    /**
     * 被本开关压成 GONE 的图标视图。
     *
     * <p>用途只有一个：开关重新打开时把它们恢复成 VISIBLE。没有这份记录，
     * 关掉再打开就必须重开腾讯地图 —— 而腾讯自己的 {@code updateZoomStatus} 只在
     * 缩放状态变化时才走，不会主动把图标摆回来。弱引用避免持有已销毁的页面。
     */
    private static final Map<View, Boolean> HIDDEN = new WeakHashMap<View, Boolean>();

    private TmapMapIcons() {}

    static void install(ClassLoader cl) {
        Class<?> base = H.cls(cl, BASE);
        Class<?> toolbar = H.cls(cl, TOOLBAR);

        // ① 工具栏图标总闸：应用自己要显示 → 改成 GONE
        H.hookSig(toolbar, "setVisible", "tmap_mapicons_bar", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (Cfg.visible(Spec.K_MAP_ICONS, true)) return chain.proceed();
                return chain.proceed(new Object[]{chain.getArg(0), Integer.valueOf(View.GONE)});
            }
        }, android.view.View.class, int.class);

        // ② 其余图标：每次重排之后统一收口
        XposedInterface.Hooker after = new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                hideAll(chain.getThisObject());
                return result;
            }
        };
        for (int i = 0; i < RESHOW_HOOKS.length; i++) {
            H.hookAll(base, RESHOW_HOOKS[i], "tmap_mapicons_" + RESHOW_HOOKS[i], after);
        }
    }

    /**
     * 已经解析过的 {@code MapBaseView} → 那一组图标视图。
     *
     * <p><b>为什么必须缓存</b>：{@link #RESHOW_HOOKS} 里那几个方法在<b>拖动地图</b>时会被
     * 高频调用（`updateZoomStatus` / `showViews` …）。每次回调都去反射取 12 个图标 +
     * 写诊断表 + 反射设置引擎开关，就是 LO 实测"拖动地图非常卡顿"的来源。
     * 缓存之后热路径只剩"几个 getVisibility 比较"，命中直接返回。
     */
    private static final Map<Object, View[]> CACHE =
            java.util.Collections.synchronizedMap(new WeakHashMap<Object, View[]>());

    /** 热路径节流：还没解析出视图时，最快 400ms 才做一次反射解析。 */
    private static volatile long lastResolveAt;

    private static void hideAll(Object base) {
        if (base == null) return;
        if (Cfg.visible(Spec.K_MAP_ICONS, true)) return;

        // ① 热路径守卫：手势期间会被高频调用，这里只允许做"极廉价"的事。
        View[] cached = CACHE.get(base);
        if (cached != null) {
            boolean need = false;
            for (int i = 0; i < cached.length; i++) {
                View v = cached[i];
                if (v != null && v.getVisibility() != View.GONE) { need = true; break; }
            }
            if (!need) return;                 // 已经都收好了 → 直接返回（不反射、不写诊断）
        } else {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastResolveAt < 400L) return;   // 还没解析过但刚做过 → 节流
            lastResolveAt = now;
        }

        // ② 慢路径：只在"第一次"或"有图标又被摆回来了"时走。
        //    作用域限定在主页：腾讯自己用 isHomePage() 判定；读不到（null）时按"是主页"处理，
        //    避免因为版本改名而整个开关失效。
        Object home = H.call(base, "isHomePage");
        if (Boolean.FALSE.equals(home)) return;
        int found = 0;
        int hidden = 0;
        View[] views = new View[ICON_GETTERS.length];
        for (int i = 0; i < ICON_GETTERS.length; i++) {
            Object v = H.call(base, ICON_GETTERS[i]);
            if (!(v instanceof View)) continue;
            views[i] = (View) v;
            found++;
            if (hide((View) v)) hidden++;
        }
        CACHE.put(base, views);
        Diag.note("mapicons_home", String.valueOf(home));
        Diag.count("mapicons", found, hidden);
        if (hidden > 0 && Cfg.debug()) {
            H.log(Log.INFO, MainHook.TAG, "tmap_mapicons: hid " + hidden + "/" + found + " icon view(s)");
        }
        // 引擎那几个开关只需设一次（缓存已建 = 本实例已设过）
        suppressEnginePoi(base);
    }

    /**
     * 顺手把<b>地图引擎</b>的兴趣点渲染也压一压。
     *
     * <p>引擎实例就在 {@code MapSceneSwitchView} 的 {@code map} 字段上（实证：
     * {@code MapSceneSwitchView#setSceneVisible} 里 {@code iget-object MapSceneSwitchView->map}）。
     * 这三个都是 {@code com.tencent.map.lib.TencentMap} 自己的公开方法：
     * <pre>
     *   setPOIRichEnable(Z)        兴趣点富气泡
     *   setAppPoiRichEnabled(Z)    应用层兴趣点富气泡
     *   setLandmarkEnable(Z)       地标
     * </pre>
     * <b>注意边界</b>：这些只影响"富气泡 / 地标"，地图上最基础的兴趣点图标与名称是
     * 引擎按下载的<b>样式资源</b>画的，Java 层没有对应开关（实证 11.7.0 里只有
     * {@code map_icon_4.1} / {@code map_icon_version} / {@code map_icon_poi_8_7_} 三个字面量）。
     * 要彻底去掉那一层得走原生样式改写，与高德侧 {@code libmapclean_labels.so} 同一条路。
     */
    private static void suppressEnginePoi(Object base) {
        Object scene = H.call(base, "getSceneSwitch");
        if (scene == null) {
            Diag.note("engine_scene", "null");
            return;
        }
        Object map = H.field(scene, "map");
        if (map == null) {
            Diag.note("engine_map", "null");
            return;
        }
        Diag.note("engine_map", map.getClass().getName());
        int ok = 0;
        if (H.call1(map, "setPOIRichEnable", Boolean.FALSE) != null) ok++;
        if (H.call1(map, "setAppPoiRichEnabled", Boolean.FALSE) != null) ok++;
        if (H.call1(map, "setLandmarkEnable", Boolean.FALSE) != null) ok++;
        Diag.count("engine_poi", 3, ok);
    }

    private static boolean hide(Object v) {
        if (!(v instanceof View)) return false;
        View view = (View) v;
        try {
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                synchronized (HIDDEN) { HIDDEN.put(view, Boolean.TRUE); }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 开关变化后立刻生效，不必重开腾讯地图。
     *
     * <p>重新打开时把之前压成 GONE 的图标恢复；关掉时把当前能取到的图标再收一遍。
     * 只碰已经登记过的 {@code MapBaseView} 实例（弱引用，页面销毁后自动失效），
     * 不做视图树遍历 —— 那正是这个模块卡顿的来源之一。
     */
    static void refresh() {
        boolean show = Cfg.visible(Spec.K_MAP_ICONS, true);
        CACHE.clear();                 // 开关变了 → 下次回调重新解析一次
        lastResolveAt = 0;
        synchronized (HIDDEN) {
            java.util.Iterator<Map.Entry<View, Boolean>> it = HIDDEN.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<View, Boolean> entry = it.next();
                View view = entry.getKey();
                if (view == null) { it.remove(); continue; }
                if (show) {
                    try {
                        if (view.getVisibility() == View.GONE) view.setVisibility(View.VISIBLE);
                    } catch (Throwable ignored) {}
                    it.remove();
                }
            }
        }
        if (!show) {
            // 关掉：把最近一次登记过的主页图标再压一遍（HIDDEN 里已含它们）。
            // 这里不重新遍历视图树，交给下一次腾讯自己的重排回调收口。
            Diag.note("mapicons_refresh", "off");
        }
    }
}
