package io.github.ldxm666.tmapclean;

/**
 * 腾讯地图 11.7.0 的**锚点清单** —— 所有 hook 点的唯一索引。
 *
 * <p>存在的理由与 {@code bmapclean.Anchors} 一样：跨版本维护时，先拿这份表去新 DEX 里
 * 定向检索，而不是重新反编译整个包。每一项都标注了它是<b>稳定锚点</b>（腾讯自己的命名）
 * 还是<b>混淆锚点</b>（跨版本会变，必须靠指纹重新定位）。
 *
 * <h3>证据来源</h3>
 * 真机安装版 {@code com.tencent.map} 11.7.0（versionCode 6011，base.apk 174,709,476 B，
 * SHA-256 {@code ca7c9feb85eb894dbf5cf41b2abfdce98531ee3111503c1d282fe64d124d18bc}），
 * 23 个 DEX，由 {@code tools/dex_query.py} 逐类解析。
 *
 * <h3>混淆锚点的定位指纹</h3>
 * <pre>
 *   首页工具回调   11.6.0 = navisdk.a.gak$12     11.7.0 = navisdk.a.gas$12     ← 名字会变，不挂
 *                  指纹：super = com.tencent.map.net.ResultCallback
 *                        方法签名 (Ljava/lang/Object;Lcom/tencent/map/jce/MapTools/GetHomepageGroupToolsResponse;)V
 *                  本模块改挂 JCE 层（见下），因此不需要它。
 *   底栏构造器     11.6.0 = navisdk.a.exh         11.7.0 已被复用为同名接口  ← 名字会变，不挂
 *                  指纹：调用 LiquidTabBarGroup#setTabViews(java.util.List)
 *                  本模块改挂 setTabViews（稳定），因此不需要它。
 *   工具适配器     11.7.0 = navisdk.a.gaa         指纹：extends RecyclerView$Adapter 且
 *                  onCreateViewHolder 里 new ToolsItemView(Context)   ← 名字会变，不挂
 *   设置家 hippy  11.6.0 = navisdk.a.ewp         11.7.0 = navisdk.a.ewt
 *                  指纹：J()Ljava/lang/String; 内含字面量 "设置家"
 *                  本模块改挂 HomeFavComponentImpl（稳定），因此不需要它。
 * </pre>
 *
 * <p><b>结论：本模块的 6 个 hook 全部落在稳定锚点上</b>，没有一个依赖混淆名。
 * 混淆锚点只在上面作为"如果将来稳定锚点也消失，该去哪找"的备份记录。
 */
final class Anchors {

    private Anchors() {}

    // ── ① 底部入口栏 ────────────────────────────────────────────────────
    /** 稳定：classes18.dex（11.7.0） / classes17.dex（11.6.0） */
    static final String TAB_BAR_LIQUID = "com.tencent.map.ama.mainpage.frame.tab.LiquidTabBarGroup";
    /** 稳定：旧版实现，11.6/11.7 均存在（11.6 已无调用点，保留挂钩以防回退） */
    static final String TAB_BAR_LEGACY = "com.tencent.map.ama.mainpage.frame.tab.TabPageGroup";
    static final String M_SET_TAB_VIEWS = "setTabViews";
    static final String M_ON_CLICK = "onClick";
    /** 稳定：标签视图的页名读取口（TabItemView / LiquidTabItemView 同名方法） */
    static final String M_GET_PAGE = "getPage";

    // ── ② 首页工具宫格 ──────────────────────────────────────────────────
    /** 稳定：JCE 结构体，未混淆。classes20.dex（11.7.0） */
    static final String TOOL_RESP = "com.tencent.map.jce.MapTools.GetHomepageGroupToolsResponse";
    static final String TOOL_GROUP = "com.tencent.map.jce.MapTools.Group";
    static final String TOOL_ITEM = "com.tencent.map.jce.MapTools.Tool";
    static final String M_READ_FROM = "readFrom";
    static final String F_GROUPS = "groups";
    static final String F_PREFER = "prefer";
    static final String F_SELECT = "select";
    static final String F_OPERATION = "operation";
    /** Group 的工具列表字段（实证自 Group.<init> 的 iput） */
    static final String F_TOOLS = "tools";
    static final String F_TOOLS_CACHE = "cache_tools";
    /** Tool 的标识与文案字段 */
    static final String F_NAME = "name";
    static final String F_TITLE = "title";

    // ── ③ 设置家 / 设置公司 ─────────────────────────────────────────────
    /** 稳定：classes19.dex（11.7.0） */
    static final String HOME_FAV_COMP = "com.tencent.map.ama.newhome.component.HomeFavComponentImpl";
    static final String HOME_FAV_VIEW = "com.tencent.map.ama.newhome.widget.HomePlaceView";
    static final String M_SET_CARD_DATA = "setCardData";
    static final String M_UPDATE_ADDRESS = "updateAddressList";
    static final String M_ON_CREATE_VIEW = "onCreateView";

    // ── ④ 大家都在看 ────────────────────────────────────────────────────
    /** 稳定：classes23.dex（11.7.0） / classes21.dex（11.6.0） */
    static final String LOOK_RENDER =
            "com.tencent.map.kuiklyPoi.pages.poidetail.components.render.RenderPeopleStillWatchingKt";
    static final String LOOK_ATTR =
            "com.tencent.map.kuiklyPoi.pages.poidetail.components.LookAndLook.RecommendationViewAttr";
    static final String M_UPDATE_STILL_WATCHING = "updateFromStillWatchingData";

    // ── ⑤ 「我的」页卡片 ────────────────────────────────────────────────
    /** 稳定：classes5.dex（11.7.0） / classes4.dex（11.6.0） */
    static final String MINE_PAGE = "com.tencent.map.personalCenter.PersonalCenter";
    /** 卡片数据管道（本模块不用它挂钩，仅用于确认模板号；11.6/11.7 同名同结构） */
    static final String MINE_CARD_MANAGER = "com.tencent.map.personalCenter.hooks.useCardManager";
    static final String M_PARSE_CARDS = "parseCardListResponse";

    /** 服务端卡片模板号 → 本模块的开关键（实证自 parseCardListResponse 的字面量） */
    static final int[] CARD_TEMPLATES = {12, 41, 76, 80, 81, 82, 83, 84};
    static final String[] CARD_LABELS = {
            "工具栏", "活动专区", "我的小程序", "订单", "积分中心", "个性化设置", "我的店铺", "我的车辆",
    };

    // ── ⑥ 主页地图图标 ──────────────────────────────────────────────────
    /** 稳定：classes19.dex（11.7.0） */
    static final String MAP_BASE_VIEW = "com.tencent.map.api.view.MapBaseView";
    static final String MAP_TOOLBAR = "com.tencent.map.api.view.ToolBar";
    static final String M_SET_VISIBLE = "setVisible";
    static final String M_IS_HOME_PAGE = "isHomePage";

    /** 地图图标视图的取用方法（全部实证自 MapBaseView 方法表） */
    static final String[] ICON_GETTERS = {
            "getToolBar", "getSceneSwitch", "getCompass", "getZoomView",
            "getLocateBtn", "getScreenshotView", "getShare", "getShareView",
            "getDayNightView", "getCopyrightView", "getScale", "getScaleView",
    };

    /** 图标重排后会被调用的方法 —— 挂这些点，否则下一次重排会把图标摆回来 */
    static final String[] ICON_RESHUFFLE = {
            "showViews", "updateZoomStatus", "setToolBarElementVisible",
            "showZoomContral", "showZoomButton", "setCompassVisibility",
    };

    // ── 不属于本模块、但同属"主页地图"的说明 ─────────────────────────────
    /**
     * 地图引擎画的兴趣点图标与名称：由原生引擎按样式资源绘制，Java 层无开关。
     * 证据：11.7.0 里只有 {@code map_icon_4.1} / {@code map_icon_version}
     * （classes9.dex，{@code com.tencent.mapsdk2.internal.config.data.k.<init>}）
     * 与 {@code map_icon_poi_8_7_}（classes20.dex，
     * {@code com.tencent.map.init.tasks.optional.ConfigUpdateTask#run}）三个字面量。
     * 要做只能像高德侧那样走原生样式改写。
     */
    static final String[] MAP_ENGINE_STYLE_KEYS = {
            "map_icon_4.1", "map_icon_version", "map_icon_poi_8_7_",
    };
}
