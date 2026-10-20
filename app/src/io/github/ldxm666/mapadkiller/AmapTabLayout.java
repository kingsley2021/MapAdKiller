package io.github.ldxm666.mapadkiller;

import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/** The original 115 dp cap leaves a narrow selected pill after removing tabs. */
final class AmapTabLayout {
    static void install(ClassLoader cl) {
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar"), "init", "amap_tab_padding",
            new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    List<?> source = (List<?>) chain.getArg(0);
                    List<Object> kept = new ArrayList<Object>();
                    for (Object tab : source) if (visible(tabId(tab))) kept.add(tab);
                    if (kept.isEmpty()) return chain.proceed();
                    Object own = H.getObjectField(chain.getThisObject(), "mTabs");
                    if (own instanceof List) {
                        List<Object> tabs = (List<Object>) own;
                        tabs.clear(); tabs.addAll(kept);
                    }
                    Object result = chain.proceed(new Object[]{kept});
                    Object container = H.getObjectField(chain.getThisObject(), "tabContainer");
                    if (container instanceof View) ((View) container).setPadding(0, 0, 0, 0);
                    return result;
                }
            }, java.util.List.class);
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
