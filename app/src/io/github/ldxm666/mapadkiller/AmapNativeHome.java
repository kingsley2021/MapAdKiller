package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface;

/** Compact homepage keeps the native toolbar and never creates the recommendation canvas. */
public final class AmapNativeHome {
    private static final Set<View> attached = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final java.util.Map<View, int[]> sizes = new WeakHashMap<View, int[]>();
    private static final Set<View> returning = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static volatile boolean compactSupported;

    /* ---- live refresh ----------------------------------------------------------------
     * A Chain can only be proceeded once, so anything that has to run again later needs a real
     * Method handle. These are captured at install time and used by refresh() to rebuild the
     * panel content in place when a switch changes -- without them a toggle only took effect
     * after the home page happened to be rebuilt, which read as "开关无效".
     */
    private static volatile Method factoryMethod, chipsMethod, skeletonMethod;
    /** How the live container was built, so it can be built again the same way. */
    private static volatile Built built;
    /** The live ToolboxPanel; its content is swapped instead of waiting for a rebuild. */
    private static volatile java.lang.ref.WeakReference<View> panelRef;
    /** The 「设置家 / 设置单位 / 常去地点」 payload, cached even while the row is suppressed. */
    private static volatile JSONObject chipsPayload;
    /** Switch state the live container was built with; see {@link #signature()}. */
    private static volatile String builtSignature = "";
    private AmapNativeHome() {}

    /**
     * 「首页精简」是否生效。
     *
     * <p>★ 这是**热路径**：每次面板重建、每个 compact_* hook 都会调一次。
     * 原实现每次读 5 个 {@code Config.visible()}（5 次 map 查 + 5 次 defaultVisible 的
     * 若干 String.equals）；现在整个合取缓存成一个 {@link Config.Flag}，
     * 调用方**只读一个 volatile**。
     *
     * <p>刷新时机：prefs 变更监听器 → {@code Config.refreshFlags()}；
     * 另外 {@code install()} 末尾会显式 {@code resolve()} 一次 —— 因为
     * {@code compactSupported} 是在 install 里才赋值的，早于它读会缓存成 false。
     */
    private static final Config.Flag COMPACT = new Config.Flag() {
        @Override protected boolean compute() {
            return compactSupported && !Config.visible(Config.K_FEED_WEATHER)
                && !Config.visible(Config.K_FEED_BOARD) && !Config.visible(Config.K_FEED_CONTENT)
                && !Config.visible(Config.K_FEED_FESTIVAL) && Config.visible(Config.K_RIDE_CARD_OFF);
        }
    };

    public static boolean compact() {
        return COMPACT.on();
    }

    public static void install(final ClassLoader cl) {
        Class<?> qs = H.cls(cl, "com.autonavi.bundle.amaphome.lite.NewQuickServiceView");
        Class<?> page = H.cls(cl, "com.autonavi.common.IPageContext");
        Class<?> state = H.cls(cl, "sv3");
        Class<?> panelDelegate = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.IToolboxPanel$QsPanelStateDelegate");
        // ── 17.02 适配：`toolbox.b` → `toolbox.a`（单字母类名整体重排）──────────────
        // DEX 证据（17.02.0.1541）：
        //   · `Lcom/autonavi/bundle/amaphome/lite/quickservice/toolbox/a;`（classes5.dex）只有 1 个方法：
        //       public static a(Landroid/content/Context;Ljava/lang/String;ILorg/json/JSONObject;
        //                       Lcom/autonavi/bundle/amaphome/lite/quickservice/toolbox/IToolboxPanel$QsPanelStateDelegate;)
        //                      Lcom/amap/bundle/commonui/designtoken/view/viewgroup/DtLinearLayout;
        //     —— 与 17.01 的 `toolbox.b.a` **签名逐字相同**（返回类型同为 DtLinearLayout）；
        //   · `...quickservice.toolbox.b` 在 17.02 **不存在**（8 DEX 全量 --Find 无该类），
        //     而 17.01 里它存在 ⇒ 这是真实迁移，不是匹配条件写错。
        // 为什么必须走候选表而不是写死：类名一错，`compactSupported` 恒为 false ⇒
        // `compact()` 恒 false ⇒ 本类所有 compact_* hook 全部退化成直通，界面上
        // 完全看不出异常（只有 amap_compact_capability 那行日志能暴露）。
        // 抗混淆锚点：不认类名。认「包 ...amaphome.lite.quickservice.toolbox 下、静态方法 a、
        //   5 参 (Context,String,int,JSONObject,QsPanelStateDelegate)、返回 DtLinearLayout」。
        Class<?> factory = null;
        for (String holder : new String[]{"a", "b"}) {
            Class<?> candidate = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox." + holder);
            if (candidate == null) continue;
            if (io.github.ldxm666.mapclean.Compatibility.method(candidate, "a", null,
                    Context.class, String.class, int.class, JSONObject.class, panelDelegate) == null) continue;
            factory = candidate;
            break;
        }
        // ── 工具面板过滤（挂在**最上游**的两个入口，与 compact() 无关）───────────────
        installToolFilter(cl, factory, panelDelegate);
        java.util.List<java.lang.reflect.Method> loads = new java.util.ArrayList<java.lang.reflect.Method>();
        if (qs != null) for (java.lang.reflect.Method candidate : qs.getDeclaredMethods()) {
            Class<?>[] args = candidate.getParameterTypes();
            if ("loadAjxQs".equals(candidate.getName()) && View.class.isAssignableFrom(candidate.getReturnType())
                    && (args.length == 2 || args.length == 3) && args[0] == page && !args[1].isPrimitive()
                    && (args.length == 2 || args[2] == boolean.class)) {
                loads.add(candidate);
            }
        }
        compactSupported = !loads.isEmpty() && panelDelegate != null
            && io.github.ldxm666.mapclean.Compatibility.method(factory, "a", null,
                Context.class, String.class, int.class, JSONObject.class, panelDelegate) != null;
        H.log("amap_compact_capability supported=" + compactSupported
                + " loadAjxQs=" + loads.size() + " factory=" + (factory != null));
        // compactSupported 到这里才确定，显式刷一次缓存（否则首次读会把 false 缓存住）。
        COMPACT.resolve();
        if (loads.isEmpty() && qs != null) {
            // 诊断：把真实签名打出来，省得下次版本更新又只能靠猜"为什么整层精简静默失效"。
            // compactSupported=false 会让 compact() 恒 false ⇒ 本类所有 compact_* hook 全部变成
            // 直通，界面上看不出任何异常，只有这一行日志能暴露它。
            //
            // 17.01.0.2012 实测（classes4.dex NewQuickServiceView）：
            //   loadAjxQs(Lcom/autonavi/common/IPageContext;Lqu3;)Lcom/autonavi/minimap/ajx3/views/AmapAjxView;
            //   loadAjxQs(Lcom/autonavi/common/IPageContext;Lqu3;Z)Lcom/autonavi/minimap/ajx3/views/AmapAjxView;
            // 17.02.0.1541 实测（classes5.dex NewQuickServiceView，类从 classes4 挪到 classes5）：
            //   loadAjxQs(Lcom/autonavi/common/IPageContext;Lbw3;)Lcom/autonavi/minimap/ajx3/views/AmapAjxView;
            //   loadAjxQs(Lcom/autonavi/common/IPageContext;Lbw3;Z)Lcom/autonavi/minimap/ajx3/views/AmapAjxView;
            //   —— 只有第 2 参的混淆类名 qu3→bw3 变了，形状匹配器不看具体类型，所以**不受影响**。
            //   这条也是「别按固定签名挂 loadAjxQs」的证据：混淆类名每版必变。
            // 返回类型链 AmapAjxView → AjxView → AjxScalpelLayout → android.widget.FrameLayout，
            // 是 View；参数 0 是 IPageContext、参数 1 非基本类型、参数 2 是 boolean ⇒
            // 与下面的形状匹配器一致，正常版本上这条日志不应出现。
            StringBuilder why = new StringBuilder();
            for (java.lang.reflect.Method m : qs.getDeclaredMethods()) {
                if (!"loadAjxQs".equals(m.getName())) continue;
                why.append(' ').append(java.util.Arrays.toString(m.getParameterTypes()))
                   .append("->").append(m.getReturnType().getSimpleName());
            }
            H.log("amap_compact_capability reason=loadAjxQs_no_match candidates=" + why);
        }
        for (java.lang.reflect.Method load : loads) H.hookSig(qs, "loadAjxQs", "amap_compact_canvas_" + load.getParameterTypes().length, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return compact() ? null : chain.proceed();
            }
        }, load.getParameterTypes());
        // ── AI 预载（永不加载：compact 时连 LLM 会话都不建）───────────────────────────
        // 17.00.0.2005 挂的是 `xc5.preloadAIData()`；17.01.0.2012 该方法**从 xc5 移走**，
        // 落到 IQSNetworkDataProvider 的唯一实现类 `l95` 上。
        // DEX 证据（17.01.0.2012, classes4.dex）：
        //   · method_ids 里声明 `preloadAIData()V` 的只有 `l95`（接口 IQSNetworkDataProvider
        //     自身另有一条抽象声明），xc5 的 11 个方法里已经没有它；
        //   · `l95.preloadAIData()` 体内 3 条调用：AMapServiceManager.getService(Class) →
        //     ILLMService.createSession() → ILLMRequestParam.setTimeoutSeconds(J)；
        //   · 调用方 2 处：`zq1$a.run()`、`tp3.onAjxQsBizLogicStart()`，都是接口调用
        //     （IQSNetworkDataProvider->preloadAIData()V），所以只能挂实现类、挂不了接口。
        // 也就是说：只挂 xc5 在 17.01 上是**死锚点** —— AI 会话照建、流照开，卡片只是被
        // 后面的 amap_stream 过滤掉，正是"藏了但还在跑"。现在两代锚点都挂，存在哪个挂哪个。
        // 抗混淆锚点：`l95` 是单字母类名，跨版本必变，重定位请认
        //   `IQSNetworkDataProvider.preloadAIData` + `createSession`/`setTimeoutSeconds` 组合。
        //
        // ── 17.02 适配：实现类又从 `l95` 搬到了 `kb5`（这次连方法都只剩一个）────────
        // DEX 证据（17.02.0.1541）：
        //   · 全 8 DEX 的 method_ids 里声明 `preloadAIData()V` 的类只有两个：
        //       `Lcom/autonavi/bundle/amaphome/api/IQSNetworkDataProvider;`（classes5，接口抽象声明）
        //       `Lkb5;`（classes5，唯一实现）
        //   · `kb5.preloadAIData()V` 体内 3 条调用与 17.01 的 `l95` **逐条相同**：
        //       AMapServiceManager.getService(Class) → ILLMService.createSession() →
        //       ILLMRequestParam.setTimeoutSeconds(J)
        //   · `kb5` 同时实现接口的其余方法（cancelAIPreloadData / fetchAIPreloadData /
        //     fillReqParams / getAJXLocation / getPreloadLocation / getPreloadLocationTS /
        //     setAJXLocation）⇒ 身份确认无疑；
        //   · `l95` 在 17.02 仍存在，但已被无关类占用（只剩 `public static a(String)I`），
        //     所以它作为候选只会打一条 miss，无副作用。
        // 结论：`kb5` 是第三代锚点。三代全挂，存在哪个挂哪个（hookSig 找不到就空转）。
        for (String holder : new String[]{"kb5", "l95", "xc5"}) {
            H.hookSig(H.cls(cl, holder), "preloadAIData", "amap_compact_preload_" + holder, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable { return compact() ? null : chain.proceed(); }
            });
        }
        // 17.01.0.2012 迁移：`g13` 这个编号被另一个无关类占了（见下方说明），真正的
        // 「设置家/单位/常去地点」行构建器搬到了 `yz2`。
        // 证据（17.01.0.2012, classes4.dex）：
        //   · `Lyz2;` 只有两个方法，第一个是
        //       a(Landroid/content/Context;Ljava/lang/String;ILorg/json/JSONObject;)Landroid/view/ViewGroup;
        //     —— 与 17.00 的 `g13.a` **签名逐字相同**；
        //   · 唯一调用方是 `com.autonavi.bundle.amaphome.lite.quickservice.toolbox.v3.ToolboxPanelV3.init(Context,String,int)`，
        //     与已 MATCH 的 `ToolboxPanelV2.buildHomeAndCompanyView` **同包**（toolbox），
        //     即它就是工具面板里那一行的构建器。
        // 抗混淆锚点：`yz2` 是单字母类名，下次必变。重定位认
        //   「(Context,String,int,JSONObject)→ViewGroup 的静态方法」+ 调用方在 amaphome.lite.quickservice.toolbox 包内。
        //
        // ── 17.02 适配：`yz2` 也被无关类占了，构建器搬到了 `s03` ──────────────────────
        // DEX 证据（17.02.0.1541）：
        //   · 全 8 DEX 里唯一具有
        //       (Landroid/content/Context;Ljava/lang/String;ILorg/json/JSONObject;)Landroid/view/ViewGroup;
        //     这个**精确签名**的方法只有 `Ls03;->a`（classes5.dex）；
        //   · `Ls03;` 只有 2 个方法：上面那个 a(...)，以及 b(II,String,String)GradientDrawable
        //     （画圆角底色的辅助）—— 与 17.01 `yz2` 的形态完全一致；
        //   · `s03.a` 的**唯一调用方** = `com.autonavi.bundle.amaphome.lite.quickservice.toolbox.v3.ToolboxPanelV3.init(Context,String,int)`
        //     （classes5.dex, code_off=0x4209d4）—— 与 17.01 `yz2`、17.00 `g13` 的调用方**同一处**，
        //     这就是它的身份指纹。
        //   · 17.02 的 `yz2` 现在是 `a(String,ve2)Loe2`（无关类）；`g13` 变成可穿戴设备类
        //     （connect/disconnect/send/...）。两个旧候选都只会 miss。
        Class<?> homeCompany = null;
        for (String holder : new String[]{"s03", "yz2", "g13"}) {
            Class<?> candidate = H.cls(cl, holder);
            if (candidate == null) continue;
            if (H.method(candidate, "a", Context.class, String.class, int.class, JSONObject.class) == null) continue;
            homeCompany = candidate;
            break;
        }
        chipsMethod = H.method(homeCompany, "a", Context.class, String.class, int.class, JSONObject.class);
        // 17.01.0.2012：`g13` 这个编号被**另一个无关类**占了 —— 它现在是 okhttp3 的
        // 一个内部类（super=Lqd2，只有 <init>(okhttp3/internal/http2/b,[Object,int,List,Z)
        // 和 a()V 两个方法），不再是 HomeCompanyBuilderV3。
        // 按规矩不猜替代品：保留原锚点（hookSig 找不到方法时是空转，无副作用），
        // 只把这件事显式打成一条 anchor_miss，让它在真机日志里显形。
        // 「设置家/单位/常去地点」这一行在 17.01 由 ToolboxPanelV2.buildHomeAndCompanyView
        // 产出（已 MATCH），所以功能不依赖 g13；但 refresh() 里的原地重建走的是
        // chipsMethod（静态），它为 null ⇒ 切换开关后重建的面板会缺这一行，直到首页重建。
        // 待真机确认项，见 never-load-impl.md「未确认」。
        if (chipsMethod == null) {
            H.log("event=anchor_miss anchor=g13.a(Context,String,int,JSONObject)"
                    + " class=" + (homeCompany == null ? "null" : homeCompany.getName())
                    + " note=17.01 该类已被无关类占用，HomeCompanyBuilderV3 迁移；V2 路径仍生效");
        }
        H.hookSig(homeCompany, "a", "amap_native_home_chips", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return chipsHook(chain, cl);
            }
        }, Context.class, String.class, int.class, JSONObject.class);
        // 17.00.0.2005 的首页实际走 ToolboxPanelV2 自己构建「设置家 / 设置单位 / 常去地点」，
        // 不经过 g13。视图树里能看到 ToolboxPanelV2 -> DtLinearLayout -> 工具行，
        // 而 buildHomeAndCompanyView 正是那个位置的下一个 addView。
        // 只挂 g13 的话，这个开关在这个版本永远是死的 —— 切换了也不会 HIT。
        // 签名与 g13.a 完全一致 (Context, String, int, JSONObject)，所以共用一段逻辑。
        Class<?> v2 = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.ToolboxPanelV2");
        H.hookSig(v2, "buildHomeAndCompanyView", "amap_v2_home_chips", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return chipsHook(chain, cl);
            }
        }, Context.class, String.class, int.class, JSONObject.class);
        Class<?> v3 = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.v3.ToolboxPanelV3");
        skeletonMethod = H.method(v3, "buildSkeletonView", Context.class, String.class, int.class);
        H.hookSig(v3, "buildSkeletonView", "amap_native_skeleton", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!compact()) return chain.proceed();
                    View empty = new View((Context) chain.getArg(0));
                    empty.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
                    empty.setVisibility(View.GONE);
                    return empty;
                }
            }, Context.class, String.class, int.class);
        // 17.00.0.2005 首页实际用的是 ToolboxPanelV2，不是 V3（见 amap_tree 视图树：
        // ToolboxPanelV2 -> DtLinearLayout -> 工具行 + DtFrameLayout 964x189 的空灰块）。
        // 那块灰就是 V2 的 AI 骨架（buildAICotView / buildAILoadingView），
        // 内容拉回来之前先占位，拉不回来就一直留在那儿 —— 也就是用户看到的
        // 「工具栏下方一直有灰色隐形窗口」。V3 的 buildSkeletonView 在这个版本根本不会走，
        // 所以只挂 V3 等于没挂。
        for (String method : new String[]{"buildAICotView", "buildAILoadingView"}) {
            H.hookSig(v2, method, "amap_v2_skeleton_" + method, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // 只有在「首页精简」生效时才收掉骨架；否则原样返回，
                    // 让 AI 入口在用户没关推荐的情况下正常工作。
                    if (!compact()) return chain.proceed();
                    View empty = new View((Context) chain.getArg(0));
                    empty.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
                    empty.setVisibility(View.GONE);
                    return empty;
                }
            }, Context.class, String.class, int.class);
        }
        // 真正把工具过滤掉的不是这个 View 层，而是数据层：amap_tool_boot 在
        // AmapData.toolbox() 里就把未开启的工具从 JSON 里剔除，并把「已开启但原生列表里
        // 没有的」工具补进空位（步行 / 骑行 之所以永远不出现，就是因为旧实现只会删不会补）。
        // 所以这里保持原样，只把「造好的容器」和「再造一次所需的参数」记下来，
        // 供开关变化时原地重建 —— 否则改了开关要等首页重建才生效，看起来就是「开关无效」。
        Class<?> delegate = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.IToolboxPanel$QsPanelStateDelegate");
        if (delegate != null && factory != null) {
            factoryMethod = H.method(factory, "a", Context.class, String.class, int.class,
                    JSONObject.class, delegate);
            H.hookSig(factory,
            "a", "amap_native_tools", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (result instanceof ViewGroup) remember(chain, (ViewGroup) result);
                    return result;
                }
            }, Context.class, String.class, int.class, JSONObject.class, delegate);
        }
        H.hookSig(qs, "initToolBoxPanel", "amap_compact_height", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                // Captured no matter what compact() says: the panel that was just attached is
                // the one a later switch change has to rebuild, and it is reused from
                // BootUIPreloader's cache rather than recreated when the home page comes back.
                Object splashPanel = H.getObjectField(chain.getThisObject(), "mSplashToolboxPanel");
                if (splashPanel instanceof View) {
                    panelRef = new java.lang.ref.WeakReference<View>((View) splashPanel);
                    refresh();
                }
                if (compact() && chain.getThisObject() instanceof View) {
                    final View view = (View) chain.getThisObject();
                    // Dump the enclosing screen once so the empty grey card under the tool bar
                    // can be named instead of guessed at.
                    view.post(new Runnable() {
                        @Override public void run() {
                            try {
                                View root = view.getRootView();
                                Object board = H.getObjectField(view, "mSplashToolboxPanel");
                                dumpTree("qs", board instanceof View ? (View) board : view);
                                if (root != null) dumpTree("root", root);
                            } catch (Throwable ignored) { }
                        }
                    });
                    Object panel = H.getObjectField(view, "mSplashToolboxPanel");
                    if (panel instanceof View) {
                        View toolbar = (View) panel;
                        ViewGroup.LayoutParams lp = toolbar.getLayoutParams();
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; toolbar.setLayoutParams(lp);
                    }
                    synchronized (attached) {
                        if (attached.add(view)) view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                                if (compact()) resize(v);
                            }
                        });
                    }
                    resize(view);
                }
                return result;
            }
        }, String.class, int.class);
        H.hookSig(qs, "setTransparentHeight", "amap_compact_limit", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!compact() || !(chain.getThisObject() instanceof View)) return chain.proceed();
                View self = (View) chain.getThisObject();
                int[] size = sizes.get(self);
                if (size == null || self.getHeight() <= 0) return chain.proceed();
                return chain.proceed(new Object[]{Math.max(0, self.getHeight() - size[3] - statusBar(self))});
            }
        }, int.class);
        Class<?> home = H.cls(cl, "com.autonavi.bundle.amaphome.page.MapHomePage");
        if (home != null) H.hookSig(qs, "resetQSHeights", "amap_compact_reset", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (compact() && chain.getThisObject() instanceof View) {
                    View view = (View) chain.getThisObject();
                    sizes.remove(view);
                    resize(view);
                }
                return result;
            }
        }, home);
        Class<?> panelState = H.cls(cl, "com.autonavi.bundle.uitemplate.container.internal.SlidableLayout$PanelState");
        if (panelState != null) H.hookSig(qs, "onPanelStateChanged", "amap_compact_rebound", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (compact() && chain.getThisObject() instanceof View) {
                    Object panel = H.getObjectField(chain.getThisObject(), "mSplashToolboxPanel");
                    String next = String.valueOf(chain.getArg(2));
                    if (panel instanceof View) ((View) panel).setVisibility("COLLAPSED".equals(next) || "HIDDEN".equals(next) ? View.INVISIBLE : View.VISIBLE);
                }
                if (compact() && "EXPANDED".equals(String.valueOf(chain.getArg(2))) && chain.getThisObject() instanceof View)
                    rebound((View) chain.getThisObject());
                return result;
            }
        }, View.class, panelState, panelState);
    }
    /**
     * Shared body of the 「设置家 / 设置单位 / 常去地点」 hooks.
     *
     * <p>Two different builders can produce this row depending on the app build:
     * {@code g13.a} (HomeCompanyBuilderV3) and, on 17.00.0.2005,
     * {@code ToolboxPanelV2.buildHomeAndCompanyView}. They take the same arguments
     * {@code (Context, String, int, JSONObject)}, so both hooks delegate here and the
     * behaviour stays identical whichever one the app happens to call.
     *
     * <p>History worth keeping in mind before changing this:
     * <ol>
     *   <li>An older build folded {@code K_HOME_CHIPS} into {@code compact()}, so enabling
     *       this switch made {@code compact()} false, which released the four recommendation
     *       canvas hooks and brought the weather / ranking / feed / festival cards back.</li>
     *   <li>Making it unconditional in the other direction was worse: a user who had already
     *       hidden the recommendations had {@code compact() == true} permanently, so the
     *       switch became dead.</li>
     * </ol>
     * It therefore honours only its own key. The canvases are suppressed by
     * {@link #compact()} independently; the two never influence each other.
     */
    /**
     * 工具面板过滤：挂在 payload 变成格子之前的**两个最上游入口**。
     *
     * <h3>为什么原来的三道 hook 拦不住（17.02 实测证据）</h3>
     * 原来只在三处过滤：{@code BootBizDataPreloaderImpl.getToolboxData()()},
     * {@code AjxModuleLocalStorage.getItemSync/getItemASYNC}。但 17.02 的面板构建链是：
     * <pre>
     *  ToolboxPanelV2.init(Context,String,int,Z)   ┐
     *  ToolboxPanelV3.init(Context,String,int)     ┘→ getToolboxData() ──┐
     *                                                                     ├→ toolbox.a.a(ctx,name,i,JSONObject,delegate)
     *  两者都有兜底：if (data == null || data.length()==0)               │      └ 内部还有兜底：
     *      data = new JSONObject(k84.f())            ────────────────────┘         if (!data.has("id0"))
     *                                                                                 data = new JSONObject(k84.f())
     * </pre>
     * {@code k84.f()Ljava/lang/String;} 被 **5 处**调用（工厂兜底 / V2 兜底 / V3 兜底 /
     * {@code getToolboxData} 自己建缓存 / {@code prepare}）。只要走到任一兜底，
     * payload 就**完全绕过**上面三道 hook ⇒ 这正是"过滤逻辑正确、格子却照样显示"的根因。
     *
     * <h3>所以挂两处</h3>
     * <ol>
     *   <li>{@code toolbox.a.a(...,JSONObject,...)} 的**第 4 个参数**（0-based 3）：
     *       这是"原料 → 10 个格子"的唯一出口，V2/V3 都走它。改这个参数即可；
     *       它的 schema 就是 {@code id0..idN + size<i> + itemDataForClick<i>}，
     *       与 {@link AmapData#toolbox} 完全一致（DEX 逐条核对过它读的 key）。</li>
     *   <li>{@code k84.f()Ljava/lang/String;} 的**返回值**：所有兜底路径的共同上游，
     *       在最上游拦一次，任何兜底都拿不到未过滤的清单。</li>
     * </ol>
     * 两处都是**幂等**的：{@code AmapData.toolbox} 在"没有需要过滤的格子"时直接返回原对象，
     * 所以重复过滤、schema 不匹配、解析失败都不会有副作用（解析失败原样放行）。
     */
    private static void installToolFilter(final ClassLoader cl, final Class<?> factory, final Class<?> panelDelegate) {
        if (factory != null && panelDelegate != null) {
            H.hookSig(factory, "a", "amap_tool_factory", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object data = chain.getArg(3);
                        if (data instanceof JSONObject) {
                            JSONObject source = (JSONObject) data;
                            JSONObject out = AmapData.toolbox(source, AmapData.CURRENT, false);
                            if (out != source) {
                                Object[] args = chain.getArgs().toArray();
                                args[3] = out;
                                if (!toolFactoryReported) {
                                    toolFactoryReported = true;
                                    H.log("event=amap_tool_factory_filter kept=" + AmapData.slotIds(out));
                                }
                                return chain.proceed(args);
                            }
                        }
                    } catch (Throwable ignored) {
                        // 过滤失败就原样放行：宁可漏一次，也不能让工具面板空白。
                    }
                    return chain.proceed();
                }
            }, Context.class, String.class, int.class, JSONObject.class, panelDelegate);
        }
        // 默认工具清单的上游（单字母类名，跨版本会变）。
        // 抗混淆锚点：认「被 quickservice.toolbox.a.a 与 ToolboxPanelV2/V3.init 调用、
        //   静态、无参、返回 String、内容是带 id0 的 JSON」——`--InvokeXref 'k84;->f'` 可一次定位。
        Class<?> raw = H.cls(cl, "k84");
        if (raw != null) {
            H.hookSig(raw, "f", "amap_tool_default_raw", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!(result instanceof String)) return result;
                    String filtered = AmapData.toolboxRaw((String) result, AmapData.CURRENT);
                    if (filtered != null && !filtered.equals(result) && !toolDefaultReported) {
                        toolDefaultReported = true;
                        H.log("event=amap_tool_default_filter raw=" + ((String) result).length()
                                + " kept=" + filtered.length());
                    }
                    return filtered == null ? result : filtered;
                }
            });
        } else {
            H.log("event=anchor_miss anchor=k84.f() note=默认工具清单上游缺失，"
                    + "工具过滤只剩工厂 + 存储两路");
        }
    }

    /** 工厂过滤只报一次（首页每次重建都会走，不节流会刷屏）。 */
    private static volatile boolean toolFactoryReported;
    /** 默认清单上游过滤只报一次。 */
    private static volatile boolean toolDefaultReported;

    private static Object chipsHook(XposedInterface.Chain chain, ClassLoader cl) throws Throwable {
        boolean show = Config.visible(Config.K_HOME_CHIPS);
        Object data = chain.getArg(3);
        int items = -1, enable = -1;
        String uid = "?";

        // The payload is parsed -- and cached -- before the switch is consulted. Two things
        // need it later: the fallback row, and the in-place rebuild that makes the switch take
        // effect without waiting for the home page to be recreated.
        JSONObject payload = null;
        if (data instanceof JSONObject) {
            payload = (JSONObject) data;
        } else {
            try { payload = new JSONObject(String.valueOf(data)); } catch (Throwable ignored) { }
        }
        if (payload != null) chipsPayload = payload;

        try {
            JSONObject probe = payload != null ? payload : new JSONObject();
            JSONArray list = probe.optJSONArray("items");
            items = list == null ? 0 : list.length();
            enable = probe.optInt("enable", -1);
            uid = probe.optString("uid");
        } catch (Throwable ignored) { }

        if (!show) {
            H.log("amap_home_chips show=false -> blocked");
            return null;
        }

        // The native builder requires payload.uid to equal the signed-in uid and returns
        // null otherwise. The cached home_and_company_data can carry an empty uid (stale
        // cache after an account switch), which is why the whole row stayed invisible.
        // The payload is otherwise complete, so align the uid and let the app's own builder
        // render it -- that keeps the row pixel-identical to the native one.
        String alignedUid = null;
        if (payload != null && items > 0) {
            String current = currentUid(cl);
            if (current != null && current.length() > 0 && !current.equals(uid)) {
                try {
                    payload.put("uid", current);
                    alignedUid = current;
                } catch (Throwable t) {
                    H.log("amap_home_chips uid_align_failed=" + t.getClass().getSimpleName());
                }
            }
        }

        // A Chain may only be proceeded once, so the arguments are prepared up front and
        // exactly one call is made.
        Object built = alignedUid == null ? chain.proceed()
                : chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1),
                        chain.getArg(2), payload});
        if (built != null) {
            H.log("amap_home_chips built=" + built.getClass().getSimpleName()
                    + " uid_aligned=" + (alignedUid != null) + " items=" + items);
            return built;
        }

        // built == null means the native builder itself refused (enable==0, uid mismatch,
        // or no items). One line here is enough to tell those apart later.
        String raw = String.valueOf(data);
        if (raw.length() > 300) raw = raw.substring(0, 300) + "...";
        H.log("amap_home_chips refused enable=" + enable + " items=" + items
                + " uid=[" + uid + "] aligned=[" + alignedUid + "] payload=" + raw);

        // Last resort: draw the row from the same payload. Unit conversion follows the
        // app's own helper exactly, dx1.l(v) = round(v / 2 * density).
        if (payload != null && items > 0) {
            ViewGroup fallback = chips(cl, (Context) chain.getArg(0), payload);
            if (fallback != null) {
                H.log("amap_home_chips fallback_rendered items=" + items);
                return fallback;
            }
        }
        return null;
    }

    /**
     * Read the currently signed-in account uid exactly the way the native builder does
     * (BundleServiceManager -> IAccountService.getUID). Returns "" when unavailable, in
     * which case the caller must not touch the payload.
     */
    private static String currentUid(ClassLoader cl) {
        try {
            Class<?> manager = cl.loadClass("com.autonavi.wing.BundleServiceManager");
            Object instance = manager.getMethod("getInstance").invoke(null);
            Class<?> service = cl.loadClass("com.autonavi.bundle.account.api.IAccountService");
            Object account = manager.getMethod("getBundleService", Class.class).invoke(instance, service);
            if (account == null) return "";
            Object uid = service.getMethod("getUID").invoke(account);
            return uid == null ? "" : String.valueOf(uid);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Rebuild the 「设置家 / 设置单位 / 常去地点」 row when the native builder bails out.
     *
     * g13.a (HomeCompanyBuilderV3) returns null unless the payload's uid equals the
     * logged-in account uid. On this device the cached payload carries real items but an
     * empty uid, so the row is never created and the 设置家 switch looks dead. The payload
     * is otherwise complete (items[].name / .iconToken / .scheme), so the same row is drawn
     * here. Every text, target and measurement comes from the app's own payload; nothing is
     * invented.
     */
    private static ViewGroup chips(ClassLoader cl, Context context, JSONObject payload) {
        try {
            JSONArray items = payload.optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            JSONArray margin = payload.optJSONArray("margin");
            int top = 0, bottom = 0, left = 0, right = 0;
            if (margin != null && margin.length() == 4) {
                // g13: top=margin[0], right=margin[1], bottom=margin[2], left=margin[3]
                top = px(context, margin.optInt(0));
                right = px(context, margin.optInt(1));
                bottom = px(context, margin.optInt(2));
                left = px(context, margin.optInt(3));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(context, payload.optInt("height")));
            lp.topMargin = top;
            lp.bottomMargin = bottom;
            row.setLayoutParams(lp);
            row.setPadding(left, 0, right, 0);
            boolean split = payload.optInt("split") == 1;
            int spacing = payload.optInt("spacing");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(android.view.Gravity.CENTER_VERTICAL);
                cell.setPadding(px(context, item.optInt("paddingLeft")), 0,
                        px(context, item.optInt("paddingRight")), 0);
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (i > 0 && spacing > 0) cellLp.setMarginStart(px(context, spacing));
                if (split) { cellLp.width = 0; cellLp.weight = 1f; }
                cell.setLayoutParams(cellLp);
                android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
                background.setColor(0xFFF6F7F8);
                float radius = px(context, item.optInt("radius"));
                background.setCornerRadii(new float[]{radius, radius, radius, radius, radius, radius, radius, radius});
                cell.setBackground(background);
                // 图标：原版 dx1.l(24.0f)，即 24 设计 px
                String iconName = AmapToolIcons.tokenOf(item);
                int iconSize = px(context, 24);
                if (iconName.length() > 0 && cl != null) {
                    try {
                        Object image = cl.loadClass("com.amap.bundle.commonui.designtoken.view.DtImageView")
                                .getConstructor(Context.class).newInstance(context);
                        AmapToolIcons.load(cl, (android.widget.ImageView) image, iconName, null, null);
                        cell.addView((View) image, new LinearLayout.LayoutParams(iconSize, iconSize));
                    } catch (Throwable ignored) {}
                }
                TextView label = new TextView(context);
                label.setText(item.optString("name"));
                // 原版用 setTextSize(0, dx1.l(textSize))，单位是 px 不是 sp；
                // 直接当 sp 会变成巨大的字，把「设置家」拆行后又被裁掉。
                int designSize = item.optInt("textSize");
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        px(context, designSize > 0 ? designSize : 24));
                label.setTextColor(0xFF323232);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                labelLp.setMarginStart(px(context, 8));
                label.setLayoutParams(labelLp);
                cell.addView(label);
                final String scheme = item.optString("scheme");
                cell.setContentDescription(item.optString("name"));
                if (scheme.length() > 0) cell.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
                                .setPackage(MainHook.PKG_AMAP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                        catch (Exception e) { H.log("amap_home_chips_action failed=" + e.getClass().getSimpleName()); }
                    }
                });
                row.addView(cell);
            }
            return row;
        } catch (Throwable t) {
            H.log("amap_home_chips fallback_failed=" + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The app's own unit helper, reproduced exactly: dx1.l(v) = round(v / 2 * density).
     * Payload values are "design px" at 2x, so this is the only correct conversion.
     */
    private static int px(Context context, int value) {
        if (value <= 0) return 0;
        return Math.round(value / 2f * context.getResources().getDisplayMetrics().density);
    }

    private static void rebound(final View self) {
        if (!returning.add(self)) return;
        self.post(new Runnable() {
            @Override public void run() {
                returning.remove(self);
                if (!compact() || !self.isAttachedToWindow()) return;
                try {
                    Object state = self.getClass().getMethod("getPanelState").invoke(self);
                    if (!"EXPANDED".equals(String.valueOf(state))) return;
                    Object anchored = state.getClass().getField("ANCHORED").get(null);
                    // Use the native animator after release; never interrupt an active drag.
                    self.getClass().getMethod("setPanelState", state.getClass(), boolean.class).invoke(self, anchored, true);
                    H.log("amap_compact_rebound native_anchor");
                } catch (Exception e) { H.log("amap_compact_rebound failed=" + e.getClass().getSimpleName()); }
            }
        });
    }
    private static int contentHeight(View self) {
        try {
            Object p = H.getObjectField(self, "mSplashToolboxPanel");
            if (!(p instanceof View)) return 0;
            View panel = (View) p;
            int width = self.getWidth() > 0 ? self.getWidth() : self.getResources().getDisplayMetrics().widthPixels;
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            // The native minimum already includes the search bar, bottom tabs and system inset.
            return minimum(self) + panel.getMeasuredHeight() + dp(self.getContext(), 12);
        } catch (Exception ignored) { return 0; }
    }
    private static void resize(View self) {
        if (self.getWidth() <= 0 || self.getHeight() <= 0) return;
        int height = contentHeight(self);
        if (height <= 0) return;
        try {
            Class<?> type = self.getClass();
            int minimum = minimum(self);
            int max = Math.min(self.getHeight() - statusBar(self), height + dp(self.getContext(), 48));
            int[] last = sizes.get(self);
            if (last != null && last[0] == self.getWidth() && last[1] == self.getHeight() && last[2] == height && last[3] == max && last[4] == minimum) return;
            Object state = type.getMethod("getPanelState").invoke(self);
            if ("DRAGGING".equals(String.valueOf(state))) return;
            sizes.put(self, new int[]{self.getWidth(), self.getHeight(), height, max, minimum});
            type.getMethod("setMinHeight", int.class).invoke(self, minimum);
            type.getMethod("setAnchorHeight", int.class).invoke(self, height);
            type.getMethod("setTransparentHeight", int.class).invoke(self, Math.max(0, self.getHeight() - max - statusBar(self)));
            type.getMethod("computeSlideRange").invoke(self);
            type.getMethod("onMeasureComplete").invoke(self);
            // Reposition once after changing the range; never reset the state during a gesture.
            type.getMethod("setPanelState", state.getClass(), boolean.class).invoke(self, state, false);
            H.log("amap_compact_geometry min=" + minimum + " anchor=" + height + " max=" + max + " state=" + state);
        } catch (Exception e) { if (Config.debugLog()) H.log("amap_compact_height error=" + e.getClass().getSimpleName()); }
    }
    private static int minimum(View self) throws Exception {
        // 反射句柄缓存：ClassLoader.loadClass 与 Class.getMethod 都比 Method.invoke 贵一个数量级，
        // 而 resize() 在**每次布局变化**时都会走到这里（面板拖动/展开/返回首页）。
        // 缓存后只剩一次静态 invoke + 一次实例 invoke。
        Class<?> guide = guideClass;
        if (guide == null) {
            guide = self.getClass().getClassLoader()
                .loadClass("com.autonavi.bundle.uitemplate.searchbar.SearchBarGuideSpHelper");
            guideClass = guide;
        }
        Method mode = guideModeMethod;
        if (mode == null) { mode = guide.getMethod("a"); guideModeMethod = mode; }
        Object value = mode.invoke(null);
        Class<?> type = self.getClass();
        Method min = minHeightMethods.get(type);
        if (min == null) { min = type.getMethod("getMinHeight", boolean.class); minHeightMethods.put(type, min); }
        return (Integer) min.invoke(self, !"SEARCHBAR_MODE_TOP".equals(String.valueOf(value)));
    }
    private static volatile Class<?> guideClass;
    private static volatile Method guideModeMethod;
    private static final java.util.Map<Class<?>, Method> minHeightMethods =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Class<?>, Method>());

    /**
     * 状态栏高度，整个进程只解析一次。
     *
     * <p>原来每次调用都做 {@code getIdentifier("status_bar_height","dimen","android")} ——
     * 一次系统资源表查询。而它的调用方 {@link #resize(View)} 挂在 QS 面板的
     * onLayoutChange 上，一次 resize 里要调它两次（第 495、503 行），面板拖动时每秒几十次。
     * 同一个 dimen 在同一进程里恒定，缓存 id 与像素值即可，行为完全一致。
     */
    private static volatile boolean statusBarResolved;
    private static volatile int statusBarPx;

    private static int statusBar(View self) {
        if (statusBarResolved) return statusBarPx;
        synchronized (AmapNativeHome.class) {
            if (statusBarResolved) return statusBarPx;
            int px = 0;
            try {
                int id = self.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id != 0) px = self.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
                px = 0;
            }
            statusBarPx = px;
            statusBarResolved = true;
            return px;
        }
    }
    private static LinearLayout tools(ClassLoader cl, final Context context, JSONObject boot, boolean night) throws Exception {
        LinearLayout root = (LinearLayout) cl.loadClass("com.amap.bundle.commonui.designtoken.view.viewgroup.DtLinearLayout")
            .getConstructor(Context.class).newInstance(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        JSONArray source = new JSONArray();
        android.content.SharedPreferences prefs = context.getSharedPreferences("toolbox", Context.MODE_PRIVATE);
        for (String key : new String[]{"choiceTools", "recommendTools"}) {
            String raw = prefs.getString(key, null);
            if (raw == null) raw = prefs.getString(key + "_SP_DEFAULT_ITEM", null);
            if (raw == null) continue;
            JSONArray list = new JSONObject(raw).optJSONArray(key);
            if (list != null) for (int i = 0; i < list.length(); i++) source.put(list.opt(i));
        }
        {
            String raw = prefs.getString("tool_list_data", null);
            if (raw == null) raw = prefs.getString("tool_list_data_SP_DEFAULT_ITEM", null);
            if (raw != null) {
                JSONArray categories = new JSONObject(raw).optJSONArray("category");
                if (categories != null) for (int i = 0; i < categories.length(); i++) {
                    JSONObject category = categories.optJSONObject(i);
                    JSONArray list = category == null ? null : category.optJSONArray("tools");
                    if (list != null) for (int j = 0; j < list.length(); j++) {
                        JSONObject item = list.optJSONObject(j);
                        if (item != null && AmapData.toolVisible(item.optString("id"), AmapData.CURRENT)) source.put(item);
                    }
                }
            }
        }
        if (source.length() == 0) for (int i = 0; i < 10; i++) {
            String id = boot.optString("id" + i);
            if (id.length() > 0 && !"86".equals(id)) source.put(new JSONObject().put("id", id)
                .put("name", boot.optString("name" + i)).put("schema", boot.optString("schema" + i))
                .put("iconV2", boot.optString("imageUrlRender" + i)));
        }
        // A newly installed app may not have opened More Tools yet. Visible selections
        // still use the routes/icons extracted from that directory, never invented IDs.
        for (String[] entry : AmapToolCatalog.ENTRIES)
            if (AmapData.toolVisible(entry[0], AmapData.CURRENT)) source.put(AmapToolCatalog.item(entry));
        String more = "amapuri://ajx?path=path://amap_bundle_toolbox/src/pages/ToolboxHomepage.page.js&style=Page&animation=1";
        source.put(new JSONObject().put("id", "86").put("name", "更多工具").put("schema", more)
            .put("iconV2", "@Img_icon_aiqs_tool_more_Png"));
        java.util.Set<String> ids = new java.util.HashSet<String>();
        LinearLayout row = null;
        int count = 0;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (!ids.add(id) || !AmapData.toolVisible(id, AmapData.CURRENT)) continue;
            if (count % 5 == 0) {
                row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(android.view.Gravity.CENTER);
            cell.setPadding(dp(context, 2), dp(context, 4), dp(context, 2), dp(context, 4));
            cell.setMinimumHeight(dp(context, 76));
            View icon = (View) cl.loadClass("com.amap.bundle.commonui.designtoken.view.DtImageView")
                .getConstructor(Context.class).newInstance(context);
            String image = item.optString("iconV2", item.optString("imageV2", item.optString("image")));
            if (image.length() == 0) image = item.optString("icon").split(",")[0];
            AmapToolIcons.load(cl, (android.widget.ImageView) icon, image, boot, id);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(context, 38), dp(context, 38)));
            TextView label = new TextView(context); label.setText(item.optString("name"));
            label.setTextSize(12); label.setTextColor(night ? Color.WHITE : Color.rgb(50, 50, 50));
            label.setGravity(android.view.Gravity.CENTER); label.setMaxLines(2);
            cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
            final String schema = item.optString("schema");
            cell.setContentDescription(item.optString("name"));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (schema.length() == 0) return;
                    try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(schema))
                        .setPackage(MainHook.PKG_AMAP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (Exception e) { H.log("amap_tool_action failed=" + e.getClass().getSimpleName()); }
                }
            });
            if ("86".equals(id) && io.github.ldxm666.mapclean.EmbeddedSettings.enabledFor(MainHook.PKG_AMAP)) {
                cell.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View view) {
                        Context owner=view.getContext();
                        for (int depth=0;depth<16;depth++) {
                            if (owner instanceof android.app.Activity)
                                return io.github.ldxm666.mapclean.EmbeddedSettings.open((android.app.Activity)owner);
                            if (!(owner instanceof android.content.ContextWrapper)) break;
                            Context base=((android.content.ContextWrapper)owner).getBaseContext();
                            if (base==owner) break;
                            owner=base;
                        }
                        return false;
                    }
                });
            }
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
            count++;
        }
        if (row != null) for (int i = count % 5; i > 0 && i < 5; i++)
            row.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1));
        if (count == 0) {
            // 绝不能把整行设为 GONE：ToolboxPanelV3.init 在调用完本方法之后，
            // 还会把「设置家 / 设置单位 / 常去地点」那条 g13(HomeCompanyBuilderV3) 行
            // addView 到本返回值上。父容器一旦 GONE，后加进来的 chips 永远不可见
            // —— 这就是「设置家开关没反应」的真正原因。空 LinearLayout 本来就是 0 高，
            // 不需要也不应该靠 GONE 来收起。
            //
            // 但也不能直接还回 DtLinearLayout：它带设计令牌的卡片背景，
            // 空的时候会在工具栏下方画出一整块没有内容的灰色圆角矩形。
            LinearLayout blank = new LinearLayout(context);
            blank.setOrientation(LinearLayout.VERTICAL);
            return blank;
        }
        return root;
    }

    /** One-shot per view: a stray container only ever needs describing once. */
    private static final Set<View> dumped = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /**
     * Dump a view subtree to logcat, once per root.
     *
     * Added to locate the empty grey card that shows up under the tool bar: the panel's
     * anchor height is computed from the panel's measured size, so a container that is still
     * reserving space for removed content inflates the panel and leaves a blank card behind.
     * Reading the real tree beats guessing which of the panel's children it is.
     */
    static void dumpTree(String label, View root) {
        // 诊断专用，默认不跑。
        //
        // 它挂在 initToolBoxPanel 的 compact 分支上，而首页面板会随开关/主题/返回首页
        // 反复重建 —— 每次都是一个**新的 root**，`dumped` 去重挡不住，于是每轮都要
        // 递归 describe() 整棵子树（深度 5、每层 12 个子节点）再往 logcat 写最多 80 行。
        // 这是纯 CPU + IO 开销，对功能没有任何作用。debug_log 打开时行为与旧版完全一致。
        if (!Config.debugLog()) return;
        if (root == null || !dumped.add(root)) return;
        java.util.List<String> lines = new java.util.ArrayList<String>();
        describe(lines, root, 0);
        for (int i = 0; i < lines.size() && i < 80; i++) H.log("amap_tree " + label + " " + lines.get(i));
    }

    private static void describe(java.util.List<String> out, View v, int depth) {
        if (v == null || depth > 5) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName())
          .append(" v=").append(v.getVisibility())
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" bg=").append(v.getBackground() != null ? "Y" : "n");
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            sb.append(" text=").append(t == null ? "" : t.subSequence(0, Math.min(18, t.length())));
        }
        out.add(sb.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = Math.min(g.getChildCount(), 12);
            out.add(pad(depth + 1) + "kids=" + g.getChildCount() + (g.getChildCount() > n ? " (first " + n + ")" : ""));
            for (int i = 0; i < n; i++) describe(out, g.getChildAt(i), depth + 1);
        }
    }

    private static String pad(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    /* ------------------------------------------------------------- live refresh */

    /** One built panel content plus the arguments it was built from. */
    private static final class Built {
        final java.lang.ref.WeakReference<ViewGroup> container;
        final Context context;
        final String theme;
        final int mode;
        final JSONObject boot;
        final Object delegate;
        Built(ViewGroup container, Context context, String theme, int mode,
              JSONObject boot, Object delegate) {
            this.container = new java.lang.ref.WeakReference<ViewGroup>(container);
            this.context = context;
            this.theme = theme;
            this.mode = mode;
            this.boot = boot;
            this.delegate = delegate;
        }
    }

    private static void remember(XposedInterface.Chain chain, ViewGroup container) {
        Object boot = chain.getArg(3);
        built = new Built(container, (Context) chain.getArg(0), String.valueOf(chain.getArg(1)),
                ((Integer) chain.getArg(2)).intValue(),
                boot instanceof JSONObject ? (JSONObject) boot : new JSONObject(),
                chain.getArg(4));
        builtSignature = signature();
    }

    /**
     * The switches the panel content depends on, as one comparable string.
     *
     * <p>Rebuilding on every call would be wasteful and would fight the app's own animations,
     * so the rebuild is gated on this: it is computed once when the container is built and
     * again when a switch is written, and they only differ when something relevant changed.
     */
    private static String signature() {
        StringBuilder out = new StringBuilder(AmapToolCatalog.ENTRIES.length + 3);
        out.append(Config.visible(Config.K_HOME_CHIPS) ? '1' : '0');
        out.append(Config.visible(Config.K_TOOLS_VISIBLE) ? '1' : '0');
        out.append(Config.visible(Config.K_TOOLS_ALLOWLIST) ? '1' : '0');
        for (String[] entry : AmapToolCatalog.ENTRIES)
            out.append(Config.visible(AmapToolCatalog.key(entry[0])) ? '1' : '0');
        return out.toString();
    }

    /**
     * Re-apply the switches to the home panel that is on screen right now.
     *
     * <h3>Why this exists</h3>
     * The panel is built once, by {@code ToolboxPanelV3.init}, and then cached in
     * {@code BootUIPreloader}; returning to the home page reuses that same instance. A switch
     * change therefore used to change nothing at all until the home page happened to be
     * rebuilt by something else -- which is exactly what the user hit as
     * 「设置家无效，打开内容全部开关后才显示」: turning 天气 / 扫街榜 / 攻略 / 出行节 on
     * made {@link #compact()} false, that rebuilt the whole canvas, and only then did the
     * 设置家 row appear.
     *
     * <p>The panel and the hooks share one process in the integrated build, so the panel can
     * hand the change straight back here instead. Everything is best-effort: if any handle is
     * missing the method returns without touching the view tree, and the change still applies
     * the next time the home page is built.
     */
    public static void refresh() {
        Built current = built;
        if (current == null) return;
        View panel = panelRef == null ? null : panelRef.get();
        ViewGroup container = current.container.get();
        if (!(panel instanceof ViewGroup) || container == null) return;
        // Only V3 builds its content the way build() reproduces it. On a host whose panel is
        // V2 the change still applies, but only when the home page is next built.
        if (!panel.getClass().getName().endsWith("ToolboxPanelV3")) return;
        if (container != ((ViewGroup) panel).getChildAt(0)) return;
        if (!container.isAttachedToWindow()) return;
        if (signature().equals(builtSignature)) return;
        try {
            ViewGroup fresh = build(current);
            if (fresh == null) return;
            ViewGroup parent = (ViewGroup) panel;
            parent.removeAllViews();
            parent.addView(fresh);
            built = new Built(fresh, current.context, current.theme, current.mode,
                    current.boot, current.delegate);
            H.log("amap_native_home refresh ok signature_changed=true");
        } catch (Throwable error) {
            H.log("amap_native_home refresh_failed=" + error.getClass().getSimpleName());
        }
    }

    /**
     * Rebuild the panel content exactly the way {@code ToolboxPanelV3.init} does: the tool grid
     * from {@code b.a}, then the 「设置家」 row, then the skeleton placeholder.
     */
    private static ViewGroup build(Built source) throws Exception {
        Method factory = factoryMethod;
        if (factory == null) return null;
        Object created = factory.invoke(null, source.context, source.theme, source.mode,
                source.boot, source.delegate);
        if (!(created instanceof ViewGroup)) return null;
        ViewGroup container = (ViewGroup) created;

        // The row is produced by the app's own builder; going through the hooked method keeps
        // the switch and the uid alignment in one place.
        Method chips = chipsMethod;
        if (chips != null && Config.visible(Config.K_HOME_CHIPS) && chipsPayload != null) {
            try {
                Object row = chips.invoke(null, source.context, source.theme, source.mode, chipsPayload);
                if (row instanceof View) container.addView((View) row);
            } catch (Throwable error) {
                H.log("amap_native_home chips_rebuild_failed=" + error.getClass().getSimpleName());
            }
        }

        Method skeleton = skeletonMethod;
        if (skeleton != null) {
            try {
                Object view = skeleton.invoke(null, source.context, source.theme, source.mode);
                if (view instanceof View) container.addView((View) view);
            } catch (Throwable ignored) { }
        }
        return container;
    }

    /** Entry point for the floating panel: it writes a switch, then tells the hook side. */
    public static void onConfigChanged(String key) {
        if (key == null) return;
        if (Config.K_HOME_CHIPS.equals(key) || Config.K_TOOLS_VISIBLE.equals(key)
                || Config.K_TOOLS_ALLOWLIST.equals(key) || key.startsWith(Config.K_TOOL_PREFIX)) {
            refresh();
        }
    }

    private static int dp(Context c, int value) { return Math.round(c.getResources().getDisplayMetrics().density * value); }
}
