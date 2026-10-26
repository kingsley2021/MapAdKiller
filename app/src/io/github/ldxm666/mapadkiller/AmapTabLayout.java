package io.github.ldxm666.mapadkiller;

import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/**
 * The original 115 dp cap leaves a narrow selected pill after removing tabs.
 *
 * <p>另外：**只剩「首页」(Main) 一个标签时，整条底部栏隐藏**，而不是留一个孤立图标。
 * 只从 {@code init(List)} 里删掉 4 个 tab 是不够的 —— {@code tabContainer} 自带皮肤背景、
 * elevation 阴影和左右 padding，它们会原样留在屏幕上，也就是用户看到的「孤零零一个首页
 * 图标 + 一条空条」。所以「只剩一个」必须把 {@code LiteTabBar} 整个 GONE，并把自身与
 * 宿主包装容器的高度归零（宿主可能按固定高度预留空间 ⇒ 地图底部出现白条）。
 */
final class AmapTabLayout {
    static void install(ClassLoader cl) {
        Class<?> barClass = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar");
        H.hookSig(barClass, "init", "amap_tab_padding",
            new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    List<?> source = (List<?>) chain.getArg(0);
                    List<Object> kept = new ArrayList<Object>();
                    List<String> ids = new ArrayList<String>();
                    int extra = 0;
                    for (Object tab : source) {
                        String id = tabId(tab);
                        if (visible(id)) { kept.add(tab); ids.add(id); }
                        if (!known(id)) extra++;
                    }
                    if (kept.isEmpty()) {
                        // 源列表本身为空。注意「首页」Main 恒 visible（{@link #visible} 对未知 id
                        // 返回 true），所以「4 个开关全关」走不到这里，这里只是防御：不重建，
                        // 免得 init 内部按 List.size() 做除法/最小值运算踩空。
                        note(chain.getThisObject(), ids, extra);
                        Object result = chain.proceed();
                        settle(chain.getThisObject());
                        return result;
                    }
                    Object own = H.getObjectField(chain.getThisObject(), "mTabs");
                    if (own instanceof List) {
                        List<Object> tabs = (List<Object>) own;
                        tabs.clear(); tabs.addAll(kept);
                    }
                    note(chain.getThisObject(), ids, extra);
                    Object result = chain.proceed(new Object[]{kept});
                    Object container = H.getObjectField(chain.getThisObject(), "tabContainer");
                    if (container instanceof View) ((View) container).setPadding(0, 0, 0, 0);
                    settle(chain.getThisObject());
                    return result;
                }
            }, java.util.List.class);
        // 兜底一：`init` 之后宿主还可能 setVisibility(VISIBLE)、或在重新进入首页时把底部栏
        // 重新 attach 回来。`onAttachedToWindow` 是 LiteTabBar **自己声明**的方法
        // （`public onAttachedToWindow()V`，17.02 classes5 实测），所以 getDeclaredMethod 拿得到；
        // 每次 attach 都重新挂一次布局复核（detach 会销毁旧 ViewTreeObserver）。
        H.hookSig(barClass, "onAttachedToWindow", "amap_tab_bar_attach", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                settle(chain.getThisObject());
                return result;
            }
        });
        Class<?> click = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar$c");
        for (String method : new String[]{"onTabClick", "onTabDoubleClick"}) H.hookSig(click, method, "amap_tab_click_" + method, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object tab = H.getObjectField(chain.getThisObject(), "a");
                String displayed = viewId((View) chain.getArg(0));
                if (!displayed.isEmpty()) return visible(displayed) && displayed.equals(tabId(tab)) ? chain.proceed() : null;
                return visible(tabId(tab)) ? chain.proceed() : null;
            }
        }, View.class);
        // ★ 17.01 适配：17.00 的 tab 模型类叫 `th6`，17.01 变成 `re6`。
        // 单字母混淆类名每版必变，而下面 4 个 dispatchTab* 钩子全部被 `if (tab != null)`
        // 守着 —— 名字一错不是报错，而是**整体静默跳过**，表现为「底部 tab 隐藏开关点了没反应」。
        // 所以这里不硬编码：先从 `LiteTabBar.dispatchTabClickBefore` / `dispatchTabClick`
        // 的真实参数表反推模型类，再退回混淆类名候选。
        // 抗混淆锚点：`LiteTabBar` 是稳定类名，它的 `dispatchTab*(…,X)` 里的 X 就是 tab 模型类。
        Class<?> tab = dispatchTabModel(cl);
        Class<?> bar = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar");
        Class<?> layout = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.view.TabItemLayoutV2");
        H.hookSig(layout, "setTabId", "amap_embedded_home_entry", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                // BootHomeTabPage's default page is the stable Main tab and cannot be hidden.
                if ("Main".equals(chain.getArg(0)))
                    io.github.ldxm666.mapclean.EmbeddedSettings.bindEntry((View) chain.getThisObject(), MainHook.PKG_AMAP);
                return result;
            }
        }, String.class);
        H.hookSig(layout, "onTouchEvent", "amap_embedded_home_touch", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return io.github.ldxm666.mapclean.EmbeddedSettings.consumeEntryTouch(
                        (View) chain.getThisObject(), (MotionEvent) chain.getArg(0)) ? true : chain.proceed();
            }
        }, MotionEvent.class);
        if (tab != null) {
            XposedInterface.Hooker dispatch = new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object model = chain.getArg(chain.getArgs().size() - 1);
                    return visible(tabId(model)) ? chain.proceed() : null;
                }
            };
            H.hookSig(bar, "dispatchTabClick", "amap_tab_dispatch", dispatch, tab);
            H.hookSig(bar, "dispatchTabClick", "amap_tab_dispatch_event", dispatch, int.class, tab);
            H.hookSig(bar, "dispatchTabClickBefore", "amap_tab_dispatch_before", dispatch, tab);
        }
        if (tab != null) H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar"), "dispatchTabTouchEvent", "amap_tab_touch", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return visible(tabId(chain.getArg(0))) ? chain.proceed() : true;
            }
        }, tab, View.class, MotionEvent.class);
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.view.TabItemLayoutV2"),
            "applyAmapEdgeContentOffset", "amap_tab_center", H.VOID);
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.view.TabFocusBackgroundViewHolder"),
            "d", "amap_tab_focus_edges", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object image = H.getObjectField(chain.getThisObject(), "d");
                    if (image instanceof View) {
                        ViewGroup.LayoutParams lp = ((View) image).getLayoutParams();
                        if (lp instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams margin = (ViewGroup.MarginLayoutParams) lp;
                            margin.leftMargin = 0; margin.rightMargin = 0; margin.topMargin = 0; margin.bottomMargin = 0;
                        }
                    }
                    return null;
                }
            });
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.view.TabItemLayoutV2"), "onMeasure",
            "amap_tab_background", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    View view = (View) chain.getThisObject();
                    int id = tabBackgroundId(view);
                    View background = id == 0 ? null : view.findViewById(id);
                    if (background != null) {
                        ViewGroup.LayoutParams lp = background.getLayoutParams();
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    }
                    return chain.proceed();
                }
            }, int.class, int.class);
    }
    private static String tabId(Object tab) {
        if (tab == null) return "";
        Object id = H.getObjectField(tab, "a");
        return id == null ? "" : String.valueOf(id);
    }

    /**
     * `tab_bg_layer` 的资源 id，整个进程只解析一次。
     *
     * <p>原来是每次 onMeasure 都调 {@code Resources.getIdentifier(name, type, pkg)} ——
     * 那是一次真正的资源表查询（遍历/二分 type 表与 entry 表，还要拼字符串比较），
     * 而 onMeasure 在滑动、指示器动画、面板展开收起时每秒会跑几十上百次。
     * 同一个包名下的 id 恒定，缓存后每帧成本从「一次资源表查询」降为「一次 volatile 读」。
     *
     * <p>解析失败（=0）也照样记下：那说明这个版本没有该资源，重复查询只会更慢。
     * 行为与旧实现完全一致（拿到 0 时同样跳过），所以不需要开关。
     */
    private static volatile boolean tabBgResolved;
    private static volatile int tabBgId;

    private static int tabBackgroundId(View view) {
        if (tabBgResolved) return tabBgId;
        synchronized (AmapTabLayout.class) {
            if (tabBgResolved) return tabBgId;
            int id = 0;
            try {
                id = view.getResources().getIdentifier("tab_bg_layer", "id", MainHook.PKG_AMAP);
            } catch (Throwable ignored) {
                id = 0;
            }
            tabBgId = id;
            tabBgResolved = true;
            return id;
        }
    }
    private static String viewId(View view) {
        if (view == null) return "";
        try { return String.valueOf(view.getClass().getMethod("getTabId").invoke(view)); }
        catch (Exception ignored) { return ""; }
    }
    private static boolean visible(String id) {
        String label = "Nearby".equals(id) ? "探索" : "Message".equals(id) ? "长按说话"
            : "Taxi".equals(id) ? "打车" : "Mine".equals(id) ? "我的" : null;
        return label == null || Config.tabVisible(label);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 只剩「首页」时整条底部栏隐藏
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 判定只看**这个 LiteTabBar 实例自己的事实**，不用全局状态：
    //   ids   = init 实际保留下来的 tab id（Main + 没被关掉的那几个）；
    //   extra = init 参数里有多少个本模块不认识的标签。
    // 每次复核时按当前配置重新数一遍 ids：`live = ids 里仍 visible 的个数`。
    //   live <= 1  ⇒ 只剩「首页」⇒ 整条栏 GONE（这就是 Bug3 的验收条件）；
    //   extra > 0  ⇒ 栏里还有本模块管不到的标签（新版本多出来的），**永不整条隐藏**。
    //
    // 为什么不用「配置里开了几个开关」当门槛：宿主给的 tab 列表不是固定的 —— 17.02 实测
    // 同一台设备可能只给「首页 + 我的」两个 tab，此时把「我的」关掉后 kept 只剩 Main，
    // 但「探索/长按说话/打车」三个键仍是默认 true ⇒ 配置总数算出 4 ⇒ 永不隐藏 ⇒ bug 还在。
    // 只有按**实际建出来的 id** 数才两种形态都对。
    //
    // 用 id 实时重算而不是只在 init 时算一次，是为了**关闭方向实时生效**：用户把标签逐个
    // 关到只剩首页时，布局复核那一帧就会把整条栏收掉，不用退出首页。
    //
    // 防抖/幂等：hide()/show() 只在状态真的变了时才动 View，`applying` 兜住递归
    // （setLayoutParams → requestLayout → onGlobalLayout → apply 这条环）。
    private static final class BarState {
        int built = -1;              // init 实际保留的 tab 数（日志/排查用）
        int extra;                   // init 参数里本模块不认识的标签数；>0 ⇒ 永不整条隐藏
        String[] ids;                // init 实际保留的 tab id，用来实时算「现在还剩几个标签」
        int selfHeight = Integer.MIN_VALUE;    // 隐藏前 LiteTabBar 自己的 lp.height
        int parentHeight = Integer.MIN_VALUE;  // 隐藏前宿主包装容器（若唯一子 View）的 lp.height
        boolean hidden;              // 是否由**我们**隐藏（宿主的 GONE 不算，避免抢它的可见性）
        boolean logged;              // 是否已打过一条判定日志（每实例只打一条，避免刷屏）
        ViewTreeObserver vto;        // 已挂过复核监听的那个 ViewTreeObserver
        Runnable fix;                // 布局后复核用的 Runnable（每实例复用，避免每次布局都 new）
    }

    private static final WeakHashMap<View, BarState> states = new WeakHashMap<View, BarState>();
    private static boolean applying;

    private static BarState state(View bar) {
        synchronized (states) {
            BarState st = states.get(bar);
            if (st == null) { st = new BarState(); states.put(bar, st); }
            return st;
        }
    }
    private static void note(Object bar, List<String> ids, int extra) {
        if (!(bar instanceof View)) return;
        BarState st = state((View) bar);
        st.built = ids.size();
        st.ids = ids.toArray(new String[ids.size()]);
        st.extra = extra;
    }

    /** 本模块认识的标签 id：Main（首页，恒显示）+ 4 个可关的。其余一律当「未知标签」保守处理。 */
    private static boolean known(String id) {
        return "Main".equals(id) || "Nearby".equals(id) || "Message".equals(id)
            || "Taxi".equals(id) || "Mine".equals(id);
    }

    /** 按当前配置应该有几个标签：Main 恒有（BootHomeTabPage 默认页，不可隐藏）。 */
    private static int configTabs() {
        int n = 1;
        for (String label : Config.TABS) if (Config.tabVisible(label)) n++;
        return n;
    }

    /** init / onAttachedToWindow 都走这里：刷新 id 快照 + 挂一次布局复核 + 立刻按状态落一次。 */
    private static void settle(Object barObj) {
        try {
            if (!(barObj instanceof View)) return;
            View bar = (View) barObj;
            syncIds(bar);
            watch(bar);
            apply(bar);
        } catch (Throwable t) {
            // ⚠ 必须自己吞：HookGuard 只要看到 hooker 抛异常就会**永久禁用这个钩子**
            // （mapclean\HookGuard.intercept → disabled=true ⇒ 之后直通原方法），
            // 那等于底部 tab 过滤整体失效。所以这里宁可少隐藏一次，也不能让钩子被摘掉。
            fail(t);
        }
    }

    /**
     * 用宿主的权威列表 {@code mTabs} 重新取一遍 id。
     *
     * <p>为什么不能只在 init 时记一次：{@code mTabs} 是 {@code LiteTabBar} 自己的成员，
     * 宿主完全可能在 init 之后 {@code addTab(int,tab)} / 直接改这个 List。用旧快照数数会
     * 少数（多出来的 tab 被当成不存在）⇒ 可能**该显示时把整条栏隐藏掉**。
     * 这里只在 attach/init（= settle）时刷，成本可忽略；17.02 实测 {@code addTab} 在
     * LiteTabBar 上**没有调用方**、{@code removeTab} 零引用，所以刷新只是保险。
     */
    private static void syncIds(View bar) {
        Object own = H.getObjectField(bar, "mTabs");
        if (!(own instanceof List)) return;
        List<?> tabs = (List<?>) own;
        String[] ids = new String[tabs.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = tabId(tabs.get(i));
        BarState st = state(bar);
        st.built = ids.length;
        st.ids = ids;
    }

    private static boolean failLogged;
    private static void fail(Throwable t) {
        if (failLogged) return;
        failLogged = true;
        H.log("event=tab_bar_hide_failed err=" + t);
    }

    private static void apply(View bar) {
        if (bar == null || applying) return;
        BarState st = state(bar);
        boolean want = shouldHide(st);
        // 幂等：状态一致就一个 View 调用都不发。唯一的例外是「该隐藏但高度还没归零」——
        // init 时 LiteTabBar 往往还没 addView（getLayoutParams() 为 null），那时只来得及
        // setVisibility(GONE)；attach 之后再补一次高度归零，否则父布局仍可能留一条白条。
        boolean need = want != st.hidden || (want && st.selfHeight == Integer.MIN_VALUE);
        if (!need) {
            // 每个 bar 实例只留一条「判定结果」日志，供批验时一眼确认走的是哪条分支。
            if (!st.logged) { st.logged = true; H.log("event=tab_bar_state" + diag(st) + " note=保持原样"); }
            return;
        }
        applying = true;
        try {
            if (want) hide(bar, st); else show(bar, st);
        } catch (Throwable t) {
            fail(t);                    // 同上：绝不让异常冒到 HookGuard 那里把钩子摘掉
        } finally {
            applying = false;
        }
    }

    /** 一行诊断：批验时直接 grep `tabs= live= extra= hide=`。 */
    private static String diag(BarState st) {
        return " tabs=" + st.built + " live=" + liveCount(st) + " extra=" + st.extra + " hide=" + st.hidden;
    }

    /**
     * 整条栏该不该隐藏。
     *
     * <p>判据是**栏里实际建出来的标签里，按当前配置还有几个要显示**（不是配置里开了几个开关）——
     * 因为宿主给的 tab 列表不是固定的：17.02 实测同一台设备可能只给「首页 + 我的」两个，
     * 也可能给 5 个。用配置总数当门槛会在「宿主只给 2 个」时漏判（把「我的」关掉后
     * 配置总数仍算出 4 ⇒ 永不隐藏 ⇒ 用户看到的还是 bug）。
     *
     * <p>三个来源：`ids`（init 实际保留的 tab id，实时按配置重算）、`extra`（未知标签 ⇒ 保守
     * 不动）、以及「从没走过 init 钩子」时的配置兜底。
     */
    private static boolean shouldHide(BarState st) {
        if (st.extra != 0) return false;             // 栏里有本模块不认识的标签 ⇒ 永不整条抹掉
        return liveCount(st) <= 1;
    }

    /** 栏里**实际建出来**的标签中，按当前配置还有几个要显示（没走过 init 钩子时退回配置总数）。 */
    private static int liveCount(BarState st) {
        if (st.ids == null) return configTabs();
        int live = 0;
        for (String id : st.ids) if (visible(id)) live++;
        return live;
    }

    private static void hide(View bar, BarState st) {
        Object container = H.getObjectField(bar, "tabContainer");
        if (container instanceof View) ((View) container).setPadding(0, 0, 0, 0);
        st.selfHeight = collapse(bar, st.selfHeight);
        Object parent = bar.getParent();
        if (parent instanceof ViewGroup && ((ViewGroup) parent).getChildCount() == 1)
            st.parentHeight = collapse((View) parent, st.parentHeight);
        if (bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
        st.hidden = true;
        st.logged = true;
        H.log("event=tab_bar_hidden" + diag(st) + " note=只剩首页标签，整条底部栏隐藏");
    }

    private static void show(View bar, BarState st) {
        restore(bar, st.selfHeight);
        st.selfHeight = Integer.MIN_VALUE;
        Object parent = bar.getParent();
        if (parent instanceof ViewGroup && ((ViewGroup) parent).getChildCount() == 1) {
            restore((View) parent, st.parentHeight);
            st.parentHeight = Integer.MIN_VALUE;
        }
        if (bar.getVisibility() != View.VISIBLE) bar.setVisibility(View.VISIBLE);
        st.hidden = false;
        st.logged = true;
        // 诚实的边界：这里只把**栏**恢复出来，标签 View 是 init 时按 kept 建的，已经不存在了。
        // 要让重新打开的标签真的出现，需要首页重建（退出再进首页 / 重启高德）。
        H.log("event=tab_bar_restored" + diag(st) + " note=重新打开的标签需要首页重建后才出现");
    }

    /** 高度归零（GONE 之后父布局若仍按固定高度预留空间，地图底部就会留一条白条）。返回保存的原高度。 */
    private static int collapse(View view, int saved) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null || lp.height == 0) return saved;
        if (saved == Integer.MIN_VALUE) saved = lp.height;
        lp.height = 0;
        view.setLayoutParams(lp);
        return saved;
    }

    private static void restore(View view, int saved) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) return;
        lp.height = saved == Integer.MIN_VALUE ? ViewGroup.LayoutParams.WRAP_CONTENT : saved;
        view.setLayoutParams(lp);
    }

    /**
     * 布局完成后复核一次。
     *
     * <p>为什么不在 {@code onDraw}/{@code dispatchDraw} 里改可见性：那两条在**每一帧**的
     * 绘制路径上，改可见性会 requestLayout ⇒ 再绘制 ⇒ 再改，直接死循环。
     * {@code OnGlobalLayoutListener} 只在整棵树真的重新布局时触发，代价是每次几个字段读。
     *
     * <p>复核本身**不在布局过程中**改 View（那会触发 "requestLayout() improperly called
     * during layout"）：先做一次零分配的判断，只有状态确实不一致时才 post 一帧去改。
     */
    private static void watch(final View bar) {
        try {
            ViewTreeObserver vto = bar.getViewTreeObserver();
            final BarState st = state(bar);
            if (st.vto == vto) return;              // 同一个 VTO 只挂一次；detach 后 VTO 换了会重挂
            st.vto = vto;
            if (st.fix == null) st.fix = new Runnable() { @Override public void run() { apply(bar); } };
            final Runnable fix = st.fix;
            vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        // 直接闭包住 st，不再走 state() 的同步 map 查 —— 这个回调在**每次布局**
                        // 都会跑，热路径上不该有锁。判据不一致才 post 一帧去改。
                        if (st.hidden != shouldHide(st)) bar.post(fix);
                    } catch (Throwable t) {
                        // 这个回调跑在 ViewRootImpl.performTraversals 里，抛出去就是**整个 App 崩**
                        fail(t);
                    }
                }
            });
        } catch (Throwable ignored) {
            // 拿不到 ViewTreeObserver 不影响主路径：init / onAttachedToWindow 已经落过一次状态。
        }
    }

    /**
     * 反推 tab 模型类（17.00 叫 {@code th6}，17.01 叫 {@code re6}，17.02 叫 {@code fh6}）。
     *
     * <p>为什么必须反推而不是写死：{@code dispatchTabClick(I,tab)} /
     * {@code dispatchTabClickBefore(tab)} / {@code dispatchTabTouchEvent(tab,View,MotionEvent)}
     * 这四个钩子都用 {@code H.hookSig(..., tab)} 做**精确签名**匹配。类名一错，
     * {@code getDeclaredMethod} 直接抛 NoSuchMethod、{@code H.hookSig} 吞掉，结果是
     * **四个钩子一起静默失效**，而日志里只有 {@code miss}，看起来像「这版本来就没这功能」。
     *
     * <p>顺序：
     * <ol>
     *   <li>{@code dispatchTabClickBefore} 的唯一非基本类型参数（17.01 = {@code re6}，17.02 = {@code fh6}）；</li>
     *   <li>{@code dispatchTabClick(int, X)} 里那个非 int、非 View 的参数；</li>
     *   <li>退回混淆类名候选 {@code fh6} / {@code re6} / {@code th6}。</li>
     * </ol>
     * 拿不到就返回 null，四个钩子照旧跳过（与旧行为一致，不会更糟）。
     * <p>⚠ 反推出来的类名每版都变，**不要在注释或日志里把它当成稳定事实**；稳定性在
     * {@code LiteTabBar} 这个类名和 {@code dispatchTab*} 这组方法名上。
     */
    private static Class<?> dispatchTabModel(ClassLoader cl) {
        Class<?> bar = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar");
        if (bar != null) {
            try {
                for (java.lang.reflect.Method m : bar.getDeclaredMethods()) {
                    if (!"dispatchTabClickBefore".equals(m.getName())) continue;
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 1 && !types[0].isPrimitive()) return types[0];
                }
                for (java.lang.reflect.Method m : bar.getDeclaredMethods()) {
                    if (!"dispatchTabClick".equals(m.getName())) continue;
                    for (Class<?> type : m.getParameterTypes()) {
                        if (type.isPrimitive() || type == View.class) continue;
                        return type;
                    }
                }
            } catch (Throwable ignored) {
                // fall through to the name candidates
            }
        }
        // 17.02.0.1541 实测：tab 模型类是 `fh6`（`LiteTabBar` 的 dispatchTabClickBefore(Lfh6;)V、
        // dispatchTabClick(Lfh6;)V、dispatchTabClick(ILfh6;)V、dispatchTabTouchEvent(Lfh6;View;MotionEvent)Z
        // 四个方法里全是 Lfh6;）⇒ 上面的反推**依然能推出 fh6**，所以候选表只是兜底。
        // ⚠ 注意 `th6` 在 17.02 是**存在的**，但已经不是 tab 模型类（被无关类占用）。
        // 这也是为什么候选表必须把本版实测名 `fh6` 排在最前：万一反推失败而落到兜底，
        // 至少拿到的是对的那个类，而不是一个"存在但无关"的同名类。
        for (String name : new String[]{"fh6", "re6", "th6"}) {
            Class<?> candidate = H.cls(cl, name);
            if (candidate != null) return candidate;
        }
        H.log("event=anchor_miss anchor=LiteTabBar.dispatchTab*(tab) note=无法反推 tab 模型类，"
                + "底部 tab 隐藏/点击拦截将跳过");
        return null;
    }
}
