package io.github.ldxm666.bmapclean;

import android.view.View;

/**
 * 路线页（地铁 / 公交方案详情）上的浮层挂件与推荐位。
 *
 * <h3>锚点怎么来的</h3>
 * 全部来自 22.1.0 的**资源实证**，不是猜名字：
 * <ul>
 *   <li>地图左下角那两个挂件在同一个布局 {@code res/y3G.xml} 里互为兄弟，
 *       aapt2 解析出的资源名是 {@code layout/bus_default_map_layout} (0x7f0e02d4)：
 *       <pre>
 *       @ll_map_buttons_view_group
 *         ├─ com.baidu.walknavi.widget.CarbonBallView          @carbon_ball_view  ← 低碳能量
 *         └─ …route.footbike.widget.PubtravBannerFloatingEntryView @carbon_entry ← 一键穿越
 *       </pre>
 *       宿主类是 {@code com.baidu.baidumaps.route.bus.widget.BusDefaultMapLayout}
 *       （superclass {@code SimpleMapLayout}，内含 {@code CarbonBallView$OnClickListener}）。</li>
 *   <li>路线页底部那张「广告 / 点击查看详情→」白卡，宿主是
 *       {@code com.baidu.baidumaps.talos.view.advert.RNAdvertContainer}（运行时视图树实测
 *       1080x433 @ y1899，正好盖住 x 广告角标 y2161 与 点击查看详情 y2246）。
 *       反面教材：{@code com.baidu.baidumaps.integratedads.*} 在这个 APK 里**根本不存在** ——
 *       DEX 里只有 {@code integratedads} 这个字符串，没有任何该包下的类型描述符。
 *       照名字挂 updateViewData/bindAd 全是静默空钩子，还会打出假的 banner=4。</li>
 *   <li>「探索××周边」三图卡是 {@code solutiondetail.BSDLAiTalosCard}（Talos 自绘，没有资源 id），
 *       「特惠打车」领券条是 {@code solutiondetail/taxiremind} 下的 {@code TaxiCouponViewBase} 家族。</li>
 * </ul>
 *
 * <h3>为什么用构造器挂，而不是挂某个方法名</h3>
 * R8 会把方法名混淆成 {@code a/b/c}，每次发版都变；但 **{@code <init>} 永远叫 {@code <init>}**。
 * 在这几个类的构造器上挂「创建即 GONE」，既不用猜方法名，也不会误伤别的视图。
 * 构造完宿主还可能把可见性改回来，所以再挂一个 attach 监听补一刀。
 *
 * <h3>顶部横幅轮播为什么在数据层断</h3>
 * 照高德那套（{@code AmapMapWidget} 在 presenter 的 showActivity 上断源）：
 * {@code BusResultTalosCard.sendBusUpdateRouteData2Talos(Map)} 是「服务端数据 → Talos 渲染」的
 * 唯一入口，进去把 {@code bus} 那个 247KB JSON 字符串里的 {@code notices} 切掉，
 * JS 排版时就没有这个节点 —— 不占位、兄弟自然上移，不用藏、不用平移、不留白。
 */
final class BaiduRoute {

    /** install() 传进来的 ClassLoader，供需要延迟解析类的地方复用。 */
    private static final ClassLoaderHolder CLASSLOADER_HOLDER = new ClassLoaderHolder();

    private static final class ClassLoaderHolder { volatile ClassLoader cl; }

    private BaiduRoute() {}

    /** 创建即隐藏的类 → 对应的开关。 */
    private static final String[][] HIDE = {
            {"com.baidu.walknavi.widget.CarbonBallView", Spec.K_ROUTE_WIDGET},
            {"com.baidu.baidumaps.route.footbike.widget.PubtravBannerFloatingEntryView", Spec.K_ROUTE_WIDGET},
        // 路线页底部那张「点击查看详情→ / 广告」白卡。运行时视图树实证：
        // com.baidu.baidumaps.talos.view.advert.RNAdvertContainer 1080x433 @ y1899，
        // 正好覆盖 x 广告角标(y2161) 与 点击查看详情(y2246)。Talos RN 自绘，无资源 id。
        {"com.baidu.baidumaps.talos.view.advert.RNAdvertContainer", Spec.K_ROUTE_AD},
            // 「探索××周边」：布局实证是 route.busnearby 这一族（4 个类都挂，覆盖
            // 列表态/详情态/子视图三种形态），外加 Talos 卡片形态。
            {"com.baidu.baidumaps.route.busnearby.widget.BusNearbyWidget", Spec.K_ROUTE_EXPLORE},
            {"com.baidu.baidumaps.route.busnearby.widget.BusNearbyListView", Spec.K_ROUTE_EXPLORE},
            {"com.baidu.baidumaps.route.busnearby.widget.BusNearbyDetailWidget", Spec.K_ROUTE_EXPLORE},
            {"com.baidu.baidumaps.route.busnearby.widget.BusNearbySubWidget", Spec.K_ROUTE_EXPLORE},
            {"com.baidu.baidumaps.route.bus.widget.solutiondetail.BSDLAiTalosCard", Spec.K_ROUTE_EXPLORE},
            {"com.baidu.baidumaps.route.bus.widget.solutiondetail.BSDLTalosCard", Spec.K_ROUTE_EXPLORE},
            {"com.baidu.baidumaps.route.bus.widget.taxiremind.TaxiCouponViewBase", Spec.K_ROUTE_TAXI},
            {"com.baidu.baidumaps.route.bus.widget.taxiremind.HorizontalTaxiCouponView", Spec.K_ROUTE_TAXI},
            {"com.baidu.baidumaps.route.bus.widget.taxiremind.VerticalTaxiCouponView", Spec.K_ROUTE_TAXI},
            {"com.baidu.baidumaps.route.bus.widget.solutiondetail.BSDLTaxiItemCouponView", Spec.K_ROUTE_TAXI},
            {"com.baidu.baidumaps.route.bus.widget.solutiondetail.BSDLTaxiItem", Spec.K_ROUTE_TAXI},
            // 底部操作栏的「第 4 个按钮」= 打车（带「特惠」角标）。
            // 实证：BSDPageBottomBar 底部一排是 收藏/截图/分享/[FourthView]/关注路线/开始导航，
            // 这个槽位由 BSDPageBottomFourthView + BSDPFourthButtonUtils +
            // BusDetailBottomFourthButtonConfigModel 共同驱动，类名直说它就是第四格。
            {"com.baidu.baidumaps.route.bus.widget.BSDPageBottomFourthView", Spec.K_ROUTE_TAXI},
            // 底部打车条（布局实证：route.footbike.widget.banner.WalkBikeBottomBarTaxiView）
            {"com.baidu.baidumaps.route.footbike.widget.banner.WalkBikeBottomBarTaxiView", Spec.K_ROUTE_TAXI},
    };

    /** 首页出行方式快捷格：{@code layout/aihome_route_choose_tag_uicomponent} = 0x7f0e00d1。 */
    private static final int LAYOUT_ROUTE_CHOOSE_TAG = 0x7f0e00d1;

    /** 要摘掉的格子文案。这一格没有 id，只有服务端下发的标题，只能按文案认。 */
    private static final String[] BLOCKED_LABELS = {"古今地图"};

    /**
     * 摘掉首页出行方式快捷格里的「古今地图」。
     *
     * <p>取证链：整棵首页视图树里 {@code ShortCutRow #7f02405a row3_container} 的五个槽位
     * 依次是「步行 / 骑行 / 古今地图 / 空 / 空」，每格结构固定为
     * {@code FrameLayout > (BMLottieAnimationView image, TextView #7f0248be text)}。
     * 这个 {@code ShortCutRow} 是 {@code com.baidu.baidumaps.duhelper.view.ShortCutRow}，
     * 所在布局 {@code res/YlX.xml} = {@code aihome_route_choose_tag_uicomponent}。
     */
    private static void installShortcutFilter() {
        // 不再挂 LayoutInflater：实测 aihome_route_choose_tag_uicomponent 并不是通过
        // inflate(int, ViewGroup, boolean) 出来的（同一个钩子对搜索框布局有效，对它无效）。
        // 改挂 ShortCutRow 自己的构造器 —— <init> 不会被 R8 改名，且与布局加载路径无关。
        Class<?> row = H.cls(CLASSLOADER_HOLDER.cl, "com.baidu.baidumaps.duhelper.view.ShortCutRow");
        if (row == null) return;
        for (java.lang.reflect.Constructor<?> ctor : row.getDeclaredConstructors()) {
            H.hook(ctor, "baidu_shortcut_row", chain -> {
                Object r = chain.proceed();
                try {
                    final View v = (View) chain.getThisObject();
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    // 格子的文案是配置下发之后才填的，构造完立刻扫是空的。
                    int[] delays = {400, 1200, 2600, 5000};
                    for (int i = 0; i < delays.length; i++) {
                        h.postDelayed(new Runnable() { @Override public void run() {
                            if (Spec.effectiveVisible(Spec.K_ROUTE_GUJIN, Spec.HOOK_READ)) return;
                            if (hideBlockedLabels(v, 0) > 0) report("ShortCutRow", "gujin_hidden");
                        }}, delays[i]);
                    }
                } catch (Throwable ignored) {}
                return r;
            });
        }
    }

    /** 递归找文案命中的 TextView，把它所在格子藏掉。返回处理个数。 */
    private static int hideBlockedLabels(View v, int depth) {
        if (v == null || depth > 8) return 0;
        int n = 0;
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null) {
                String text = t.toString().trim();
                for (int i = 0; i < BLOCKED_LABELS.length; i++) {
                    if (text.equals(BLOCKED_LABELS[i])) {
                        Object parent = v.getParent();
                        removeAndCollapse(parent instanceof View ? (View) parent : v);
                        return 1;
                    }
                }
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) n += hideBlockedLabels(g.getChildAt(i), depth + 1);
        }
        return n;
    }

    /**
     * 数据层摘除：在 {@code BusResultTalosCard.sendBusUpdateRouteData2Talos(Map)} 上动手。
     *
     * <p>这是「把数据喂给 Talos 渲染」的唯一入口（同族还有 {@code jsonToMap(JSONObject)}、
     * {@code setData(Bus,Z,DmBusResultModel)}、{@code setDataInternal(...)}）。
     * 在这一步把横幅节点从 Map 里摘掉，Talos 排版时它根本不存在 ——
     * 上方不留白、下方也不留白，一次到位。
     *
     * <p>为什么不在视图层打补丁：Talos/RN 用绝对坐标布局，子节点 GONE 之后兄弟不上移、
     * 父容器高度也不会变，只能手工上移 + 反算高度，而 RN 每趟布局都会覆盖回去。
     * 实测那套只能消掉上方空白，下方会留下等高的空档。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean STRIP_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void installTalosDataFilter(ClassLoader cl) {
        Class<?> card = H.cls(cl, "com.baidu.baidumaps.route.bus.card.BusResultTalosCard");
        if (card == null) {
            H.log(android.util.Log.INFO, MainHook.TAG, "event=baidu_strip cardnull=1");
            return;
        }
        int n = 0;
        for (java.lang.reflect.Method m : card.getDeclaredMethods()) {
            if (!"sendBusUpdateRouteData2Talos".equals(m.getName())) continue;
            H.hook(m, "route.data.strip", chain -> {
                int r = -9;
                try {
                    if (!Spec.effectiveVisible(Spec.K_ROUTE_AD, Spec.HOOK_READ)) {
                        Object a0 = chain.getArg(0);
                        r = stripNotices(a0);
                    }
                } catch (Throwable t) {
                    r = -2;
                }
                if (STRIP_LOG.compareAndSet(false, true)) {
                    H.log(android.util.Log.INFO, MainHook.TAG, "event=baidu_strip r=" + r);
                }
                return chain.proceed();
            });
            n++;
        }
        H.log(android.util.Log.INFO, MainHook.TAG, "event=baidu_strip hooks=" + n);
    }

    /**
     * 从数据源头摘掉路线页的横幅轮播 —— 只摘 {@code notices}，别的一律不碰。
     *
     * <p>证据链：横幅文案「真的没想到，原来这里有这么多攻略」出现在
     * {@code bus} 的第 1046 字节，路径 {@code bus.notices[].prompts[].text}，
     * 同级还有 {@code icon_url} / {@code jump_url}，正好对上视图树里那张
     * ReactImageView + ReactTextView + 箭头的 289px 卡片。
     *
     * <p>在数据交给 Talos 之前摘掉，JS 排版时就没有这个节点 ——
     * 不占位、兄弟自然上移，不用藏、不用平移、不留白。
     *
     * <p>上一版按「键名含 tips/guide/recommend」批量删，把公共交通页正文一起删了，
     * 整页变空；这一版只认 notices 一个键。
     */
    @SuppressWarnings("unchecked")
    private static int stripNotices(Object arg) {
        if (!(arg instanceof java.util.Map)) return -3;
        java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) arg;
        Object bus = m.get("bus");
        if (!(bus instanceof String)) return -4;
        String in = (String) bus;
        String out = stripNoticesJson(in);
        if (out.length() == in.length()) return 0;
        try {
            m.put("bus", out);
        } catch (Throwable t) {
            return -8;
        }
        return 1;
    }

    private static final String NOTICES_KEY = "\"notices\"";

    /**
     * 从 bus 的 JSON 文本里切掉顶层的 {@code notices} 键。
     *
     * <p>{@code bus} 是 {@code java.lang.String}（实测 247,015 字符的 JSON），
     * 所以这里是字符串手术，不是反射，也不是重新序列化 ——
     * 只切一段区间，其余字节原样保留，渲染层看到的东西和服务器下发的一模一样。
     *
     * <p>重新 parse + toString 会改转义（\u003d 之类）和键序，RN 侧可能因此重新布局，
     * 所以宁可自己扫括号。
     */
    private static String stripNoticesJson(String s) {
        int n = s.length();
        int idx = s.indexOf(NOTICES_KEY);
        while (idx >= 0) {
            int p = idx + NOTICES_KEY.length();
            while (p < n && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
            if (p < n && s.charAt(p) == ':' && jsonDepthAt(s, idx) == 1) {
                int v = p + 1;
                while (v < n && (s.charAt(v) == ' ' || s.charAt(v) == '\t')) v++;
                int e = jsonValueEnd(s, v);
                if (e > v) return jsonCut(s, idx, e);
            }
            idx = s.indexOf(NOTICES_KEY, idx + NOTICES_KEY.length());
        }
        return s;
    }

    /** 从 0 到 pos 的括号净深度，字符串内部的括号不算。顶层对象的键 = 1。 */
    private static int jsonDepthAt(String s, int pos) {
        int d = 0;
        boolean inStr = false;
        for (int i = 0; i < pos; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                d++;
            } else if (c == '}' || c == ']') {
                d--;
            }
        }
        return d;
    }

    /** 返回值的结束下标（不含）；返回 -1 表示不是复合值。 */
    private static int jsonValueEnd(String s, int v) {
        int n = s.length();
        if (v >= n) return -1;
        char c = s.charAt(v);
        if (c != '[' && c != '{') return -1;
        int d = 0;
        boolean inStr = false;
        for (int i = v; i < n; i++) {
            char x = s.charAt(i);
            if (inStr) {
                if (x == '\\') i++;
                else if (x == '"') inStr = false;
                continue;
            }
            if (x == '"') { inStr = true; continue; }
            if (x == '[' || x == '{') d++;
            else if (x == ']' || x == '}') {
                d--;
                if (d == 0) return i + 1;
            }
        }
        return -1;
    }

    /** 切掉 [start,end)，顺手吞掉相邻的一个逗号，保证 JSON 仍然合法。 */
    private static String jsonCut(String s, int start, int end) {
        int n = s.length();
        int a = start;
        int b = end;
        int j = b;
        while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
        if (j < n && s.charAt(j) == ',') {
            b = j + 1;
        } else {
            int k = a - 1;
            while (k >= 0 && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) k--;
            if (k >= 0 && s.charAt(k) == ',') a = k;
        }
        return s.substring(0, a) + s.substring(b);
    }

    /**
     * 原生广告壳（BannerAdView / RotateBannerAdView）的断源。
     *
     * <p>同一个广告位有两套渲染，实测两套都在这个 APK 里：
     * <ul>
     *   <li>Talos RN 模板：{@code talos.view.advert.RNAdvertContainer}
     *       （classes.dex 与 classes13.dex 都有，已由 HIDE 表在构造时收掉）</li>
     *   <li>原生壳：{@code PubTravelBaseHomeCard.showAd(BannerAdView)} →
     *       {@code RotateBannerAdView.attachAdView()}，壳是
     *       {@code integratedads.view.BannerAdView}（classes12.dex，父类 BaseAdView）</li>
     * </ul>
     * RN 那套是「视图建出来再收」，这一套更进一步：在**取数**那一步就断，
     * 连网络请求都不发，也不会触发广告曝光回调。
     *
     * <p>只挂 {@code fetchAd()} 与 {@code showAd(1参数)} 这两个具名方法 ——
     * 这两个名字在 classes13.dex 里是明文，没被 R8 混淆；
     * 参数类型（integratedads.ad.b）解析不到也不影响，按名字+参数个数匹配。
     */
    private static int blockNativeAd(ClassLoader cl) {
        Class<?> card = H.cls(cl, "com.baidu.baidumaps.route.pubtravel.card.PubTravelBaseHomeCard");
        if (card == null) return 0;
        int n = 0;
        for (java.lang.reflect.Method m : card.getDeclaredMethods()) {
            String name = m.getName();
            int pc = m.getParameterTypes().length;
            boolean fetch = "fetchAd".equals(name) && pc == 0;
            boolean show = "showAd".equals(name) && pc == 1;
            if (!fetch && !show) continue;
            if (H.hook(m, "baidu_ad_" + name, chain -> {
                if (Spec.effectiveVisible(Spec.K_ROUTE_AD, Spec.HOOK_READ)) return chain.proceed();
                return null;
            })) n++;
        }
        return n;
    }
    static void install(ClassLoader cl) {
        CLASSLOADER_HOLDER.cl = cl;
        StringBuilder landed = new StringBuilder();
        int hit = 0;
        for (int i = 0; i < HIDE.length; i++) {
            if (hideOnCreate(cl, HIDE[i][0], HIDE[i][1])) {
                hit++;
                landed.append(landed.length() == 0 ? "" : ",").append(simple(HIDE[i][0]));
            }
        }
        int adhook = blockNativeAd(cl);
        installShortcutFilter();
        // 全局 setText 探针已拆除：它会挂在整个 App 的每一个 TextView 上，实测明显卡顿。
        installTalosDataFilter(cl);  // 数据层摘横幅：只删 bus.notices，别的不动
        // 原因：在 sendBusUpdateRouteData2Talos(Map) 上按「键名含 tips/guide/recommend…」
        // 递归删除，把公共交通页的正文数据一起删了，整页变空。
        // 数据层这条路需要精确知道横幅字段名，靠键名猜必然误伤 —— 保留方法体备查，
        // 但不再挂载。横幅仍由视图层兜住。
        // 一行交代清楚：哪些锚点在这一版还在、哪些没了。缺了就是这版改了类名，
        // 拿这行去 DEX 里重新定位，不用靠猜。
        H.log(android.util.Log.INFO, MainHook.TAG, "baidu_route widgets=" + hit + "/" + HIDE.length
                + " ad=" + adhook + " landed=" + landed);
    }

    private static String simple(String className) {
        int i = className.lastIndexOf('.');
        return i < 0 ? className : className.substring(i + 1);
    }

    /**
     * 在该类的构造器上挂「创建即 GONE」。
     *
     * <p>{@code H.method(c, "<init>", -1)} 的 {@code -1} 表示不限参数个数 ——
     * 这几个类都有多个重载（带 attrs / 不带 attrs），必须全挂上。
     */
    private static boolean hideOnCreate(ClassLoader cl, String className, final String key) {
        Class<?> c = H.cls(cl, className);
        if (c == null || !View.class.isAssignableFrom(c)) return false;
        return hook(c, key) > 0;
    }

    /** 每个类只报一次，避免刷屏。 */
    private static final java.util.Set<String> REPORTED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void report(String cls, String what) {
        if (REPORTED.add(cls + "/" + what)) {
            H.log(android.util.Log.INFO, MainHook.TAG, "baidu_route_" + what + " cls=" + cls);
        }
    }

    private static int hook(Class<?> c, final String key) {
        final String simple = c.getSimpleName();
        // 挂满**全部** <init> 重载：这几个类都有 (Context) 和 (Context,AttributeSet) 两个，
        // H.method 只返回第一个匹配，漏掉的那个重载会让控件照常出现。
        int n = 0;
        for (java.lang.reflect.Constructor<?> m : c.getDeclaredConstructors()) {
            H.hook(m, "baidu_route_" + c.getSimpleName() + "_" + n, chain -> {
                Object result = chain.proceed();
                if (Spec.effectiveVisible(key, Spec.HOOK_READ)) return result;
                final View view = (View) chain.getThisObject();
                BaiduHooks.hide(view);
                // 构造完立刻复查一次：宿主常在 init/onFinishInflate 里把可见性改回来，
                // 只藏一次等于没藏。这里在下一帧再压一次，并把「有没有被改回来」记进日志。
                try {
                    view.post(new Runnable() { @Override public void run() {
                        if (view.getVisibility() != View.GONE) report(simple, "relapse");
                        else report(simple, "hidden");
                        removeAndCollapse(view);
                    }});
                } catch (Throwable ignored) {}
                try {
                    view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override public void onViewAttachedToWindow(View v) { removeAndCollapse(v); }
                        @Override public void onViewDetachedFromWindow(View v) { }
                    });
                } catch (Throwable ignored) {}
                // 宿主会在构造之后、动画里、onResume 里反复把可见性改回 VISIBLE
                // （实测 PubtravBannerFloatingEntryView 就是 relapse）。
                // 只靠构造时藏一次必然被改回来，所以在它自己的布局回调里再压一次 ——
                // 这个监听只挂在这几个控件上，不是全局的 setVisibility，代价可以忽略。
                try {
                    view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                             int ol, int ot, int or, int ob) {
                            if (v.getVisibility() != View.GONE || wrapperAlive(v)) removeAndCollapse(v);
                        }
                    });
                } catch (Throwable ignored) {}
                return result;
            });
            n++;
        }
        return n;
    }

    /**
     * 把这个视图、以及一路向上「只包着它一个孩子」的包装层全部 GONE，再让根重排。
     *
     * <p>为什么不能只 GONE 自己：广告卡片外面通常还套了一层带背景的容器，
     * 只藏内层会剩下一块空白卡片 —— 就是用户看到的那个白框。
     * 向上最多收 3 层，且只在父容器**只有一个孩子**时继续，
     * 避免把宿主真正的列表项/页面容器误伤。
     */
    /**
     * 内层已经 GONE，但外面那层带圆角/白底的壳还占着高度 —— 就是用户看到的空白卡片。
     * 判断标准：父容器只有一个孩子（说明它纯粹是个包装层）。
     */
    private static boolean wrapperAlive(View v) {
        Object parent = v.getParent();
        return parent instanceof android.view.ViewGroup
                && ((android.view.ViewGroup) parent).getVisibility() != View.GONE
                && onlyOwnsMe((android.view.ViewGroup) parent, v);
    }

    /**
     * 这个容器除了 me 之外，还有没有「看得见的」孩子。
     *
     * <p>原来只判断 {@code getChildCount()==1}，实测不够：广告卡的父容器里常常还挂着一个
     * 宽度 0 的占位 View 或者一个已经 GONE 的兄弟，孩子数是 2，于是壳被留下 ——
     * 用户看到的就是中间空出一大块白。改成按「有没有别的可见孩子」判断。
     *
     * <p>构造期还没布局，孩子高度全是 0，此时不能拿高度当证据，
     * 所以只有容器本身已经量过（height > 0）时才用高度过滤，否则只看可见性。
     */
    private static boolean onlyOwnsMe(android.view.ViewGroup g, View me) {
        boolean laidOut = g.getHeight() > 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == me) continue;
            if (c.getVisibility() == View.VISIBLE) {
                if (!laidOut || c.getHeight() > 0 || c.getWidth() > 0) return false;
            }
        }
        return true;
    }

    private static void removeAndCollapse(View v) {
        if (v == null) return;
        View root = null;
        try { root = v.getRootView(); } catch (Throwable ignored) {}
        BaiduHooks.hide(v);
        View cur = v;
        for (int depth = 0; depth < 4; depth++) {
            Object parent = cur.getParent();
            if (!(parent instanceof android.view.ViewGroup)) break;
            android.view.ViewGroup group = (android.view.ViewGroup) parent;
            if (!onlyOwnsMe(group, cur)) break;
            BaiduHooks.hide(group);
            cur = group;
        }
        if (root != null) root.requestLayout();
    }
}
