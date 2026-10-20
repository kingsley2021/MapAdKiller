package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.ldxm666.mapclean.FloatingPanel;

/**
 * 模块的<b>唯一入口</b>：长按底部「我的」tab → 弹出模块自己的设置面板。
 *
 * <h3>为什么是这个入口（而不是往「我的」页塞第 4 个图标）</h3>
 * 往胶囊里注入第 4 个图标这条路，在 17.01 / 17.02 真机上连试五轮都没出现过：
 * 先是 layout 监听丢失（resume 时窗口还没 attach），再是 probe 扫到的是**没有窗口的空壳树**
 * （`NewMapActivity` 是 RESUMED，但窗口属于 `SplashActivity`），再是胶囊 381px 塞不下第 4 格，
 * 最后是探针在冷启动首页就把 5 次配额烧光（`give_up reason=attempts n=5`）。
 * ⇒ **2026-10-20 定案：整条探针路径已删除**（连同 `probe/scan/inject/verify/placeInRow/
 * alignBesidePill/pillOf/iconDesc/containsEntry/buildParams/stepOf/glyph/applyState/toggle`
 * 与 `INJECTED/PENDING/DIAG/TRACKED` 及各轮询常量，约 1300 行）。
 * 模块里**不再有任何扫视图树的代码**，UI 线程零遍历 —— 这是「性能优先」的硬要求。
 *
 * <h3>为什么长按这条路能成（真机验证过）</h3>
 * <pre>
 * 2026-10-20 02:30:52 设备 975aaf17 / 高德 17.02.0.1541
 * event=mine_entry_long_press held=750 mine=true view=TabItemLayoutV2
 * event=mine_entry_open_panel near=false
 * event=mine_entry_own_panel shown=true host=DecorView decor=1080x2400 children=2
 * event=mine_entry_own_panel measured=1080x2400 vis=0 open=true hostAttached=true
 * uiautomator dump 抓到面板真实文字：「恢复默认配置」「首页工具宫格」「底栏」「其他」…
 * </pre>
 * 三个关键点（都是真机踩出来的）：
 * <ol>
 *   <li>{@code LiteTabBar.dispatchTabTouchEvent} 是**必然触发**的（HIT `amap_tab_dispatch*`），
 *       而 decor 的 layout 回调不一定跑；</li>
 *   <li>长按**不能靠 ACTION_UP 算时长** —— `input swipe` 式长按只送 DOWN、不送 UP；所以改成
 *       「按下后 {@link #TAB_LONG_PRESS_MS} 自触发」，UP/CANCEL/CLICK 只负责取消；</li>
 *   <li>面板**必须在 {@code host.post()} 的下一帧才 {@code open()}**：`open()` 内部先
 *       `card.setTranslationY(420dp)` 再动画回 0，在 addView 同一帧调用会让卡片**停在屏幕外**
 *       （面板 VISIBLE、尺寸也对，但一个字都看不见）。</li>
 * </ol>
 *
 * <h3>为什么不依赖 Activity</h3>
 * 17.02 上「resume 的 Activity」和「拥有窗口的 Activity」是**分离**的（`NewMapActivity` vs
 * `SplashActivity`），Context 链也指向错的那个。所以这里全程只用 <b>View</b>：
 * {@code pressedView.getRootView()} 就是真正在显示的 DecorView，对象恒等，不受任何包装影响。
 * 面板也因此是**自己托管**的（{@link #showOwnPanel}），不依赖悬浮球是否挂上。
 */
public final class AmapMineEntry {

    /**
     * 应急开关（**默认 false = 入口开启**）。置 true 时 {@link #install} 直接返回，连 hook 都不装。
     *
     * <p>读法注意：不能用 {@code FloatingConfig.read(K_MINE_ENTRY_OFF)} —— 那条路径对未知 key
     * 走 {@code Config.defaultVisible()}，而它对未知 key 返回 **true**，会让入口默认变成「关」。
     * 这里要「原始布尔 + 默认 false」，所以用 {@code Config.selected()}。
     *
     * <p>⚠️ 用这个开关做 A/B 之后**必须改回 false**：本轮的「改了没反应」就是它被留在 true 造成的。
     */
    private static final String K_MINE_ENTRY_OFF = "mine_entry_off";
    /** 「我的」tab 的文案（长按判定用）。 */
    private static final String LABEL_MINE = "我的";
    /** 按住多久算长按。 */
    private static final long TAB_LONG_PRESS_MS = 700L;

    /** 自己托管的面板与其宿主（弱引用，随窗口回收）。 */
    private static volatile WeakReference<ViewGroup> SELF_HOST =
            new WeakReference<ViewGroup>(null);
    private static volatile WeakReference<FloatingPanel> SELF_PANEL =
            new WeakReference<FloatingPanel>(null);

    /** 当前这一次按下的状态（长按自触发用）。 */
    private static volatile long tabDownAt;
    private static volatile boolean tabFired;
    private static volatile WeakReference<View> tabDownView =
            new WeakReference<View>(null);

    /**
     * 长按已经弹过面板 ⇒ 把紧随其后的那一次 tab 点击**吞掉**。
     *
     * <p>为什么需要：真机 r6 实测长按之后仍会跟一次 {@code dispatchTabClick}，页面顺带切到
     * 「我的」—— 用户预期是「只弹面板，页面不动」。判据只有「刚刚发生过长按」这一条，
     * 普通轻点不受影响。
     *
     * <p>{@link #SWALLOW_WINDOW_MS} 是保险：万一长按后没有跟点击，标志也不能长期悬着。
     */
    private static volatile boolean swallowNextClick;
    private static volatile long swallowAt;
    private static final long SWALLOW_WINDOW_MS = 800L;

    private AmapMineEntry() {}

    /** 由 {@link AmapHooks} 的 feature 机制调用；任何异常都只会变成一条日志。 */
    public static void install(final ClassLoader cl) {
        boolean off;
        try {
            off = Config.selected(K_MINE_ENTRY_OFF);
        } catch (Throwable t) {
            off = false;   // 读不到配置就按「开启」处理，绝不因为读配置失败把入口弄没
        }
        if (off) {
            H.log("event=mine_entry_off switch=" + K_MINE_ENTRY_OFF + " (hooks NOT installed)");
            return;
        }
        installTabTrigger(cl);
    }

    /**
     * 挂 tab 钩子。`H.hookAll` **按方法名挂全部重载**（`dispatchTabTouchEvent` 有多个重载，
     * 写死签名一换版就静默失效）；同一个方法被 `AmapTabLayout` 也挂一次是合法的。
     */
    private static void installTabTrigger(final ClassLoader cl) {
        try {
            Class<?> bar = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar");
            if (bar == null) {
                // 抗混淆锚点丢了：只记一条，绝不硬编码别的类名去猜。
                H.log("event=anchor_miss anchor=LiteTabBar");
                return;
            }
            int n = 0;
            n += H.hookAll(bar, "dispatchTabTouchEvent", "amap_mine_entry_tab_touch", TAB_TOUCH);
            // 点击 = 不是长按：用来清掉待触发的长按状态，避免「轻点一下也弹面板」。
            n += H.hookAll(bar, "dispatchTabClick", "amap_mine_entry_tab_click", TAB_CLICK);
            n += H.hookAll(bar, "dispatchTabClickBefore", "amap_mine_entry_tab_click2", TAB_CLICK);
            H.log("event=mine_entry_tab_trigger hooks=" + n);
        } catch (Throwable t) {
            H.log("event=mine_entry_tab_error err=" + t.getClass().getSimpleName());
        }
    }

    /**
     * tab 触摸钩子：识别长按。
     *
     * <p>★ 不靠 ACTION_UP 算时长：`input swipe` 式长按**只送 DOWN、不送 UP**
     * （真机实测），靠 UP 判永远不触发。这里 DOWN 之后排一个 {@link #TAB_LONG_PRESS_MS}+50ms
     * 的一次性任务，到点仍未取消就直接触发；UP / CANCEL / CLICK 都只负责把状态清掉。
     */
    private static final XposedInterface.Hooker TAB_TOUCH = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                MotionEvent event = null;
                View touched = null;
                List<Object> args = chain.getArgs();
                if (args != null) {
                    for (int i = 0; i < args.size() && i < 4; i++) {
                        Object a = args.get(i);
                        if (event == null && a instanceof MotionEvent) {
                            event = (MotionEvent) a;
                        } else if (touched == null && a instanceof View) {
                            touched = (View) a;
                        }
                    }
                }
                if (event == null) return result;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    tabDownAt = android.os.SystemClock.uptimeMillis();
                    tabDownView = new WeakReference<View>(touched);
                    tabFired = false;
                    if (touched != null) {
                        final WeakReference<View> ref = new WeakReference<View>(touched);
                        touched.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    View down = ref.get();
                                    long downAt = tabDownAt;
                                    if (down == null || downAt == 0L || tabFired) return;
                                    long held = android.os.SystemClock.uptimeMillis() - downAt;
                                    if (held < TAB_LONG_PRESS_MS) return;
                                    tabFired = true;
                                    onTabLongPress(down, held);
                                } catch (Throwable ignored) { }
                            }
                        }, TAB_LONG_PRESS_MS + 50L);
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    long downAt = tabDownAt;
                    tabDownAt = 0L;
                    View down = tabDownView.get();
                    if (!tabFired && down != null && downAt != 0L) {
                        long held = android.os.SystemClock.uptimeMillis() - downAt;
                        if (held >= TAB_LONG_PRESS_MS) {
                            tabFired = true;
                            onTabLongPress(down, held);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 绝不影响宿主
            }
            return result;
        }
    };

    /**
     * tab 点击钩子：① 长按刚弹过面板 ⇒ **吞掉这一次点击**（页面不切走）；
     * ② 其余情况只清掉待触发的长按状态（轻点一下不该弹面板）。
     *
     * <p>吞点击用「不调用 {@code chain.proceed()} 并返回 {@code Boolean.TRUE}」——
     * 与本仓库 `AmapTabLayout` 对 {@code dispatchTabTouchEvent} 的既有写法一致
     * （那里是 `return visible(...) ? chain.proceed() : true;`），不引入新的返回类型风险。
     */
    private static final XposedInterface.Hooker TAB_CLICK = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                long at = swallowAt;
                if (swallowNextClick && at != 0L
                        && android.os.SystemClock.uptimeMillis() - at < SWALLOW_WINDOW_MS) {
                    swallowNextClick = false;
                    swallowAt = 0L;
                    tabDownAt = 0L;
                    tabDownView = new WeakReference<View>(null);
                    H.log("event=mine_entry_click_swallowed");
                    return Boolean.TRUE;      // 不 proceed ⇒ 这一次点击不再切 tab
                }
            } catch (Throwable ignored) { }
            Object result = chain.proceed();
            try {
                tabDownAt = 0L;
                tabDownView = new WeakReference<View>(null);
            } catch (Throwable ignored) { }
            return result;
        }
    };

    /** 长按真正触发点。 */
    private static void onTabLongPress(View down, long held) {
        boolean mine = looksLikeMineTab(down);
        H.log("event=mine_entry_long_press held=" + held + " mine=" + mine
                + " view=" + down.getClass().getSimpleName());
        if (!mine) return;
        // ① 按「同一窗口」找浮层开面板（不碰 Activity）
        boolean near = false;
        try {
            near = io.github.ldxm666.mapclean.FloatingController.openPanelNear(down);
        } catch (Throwable ignored) { }
        // ② 浮层没挂上就自己托管一个面板（只依赖按下 View 的根视图，最兜底）
        boolean self = false;
        if (!near) self = showOwnPanel(down);
        // 面板已经弹出来了 ⇒ 吞掉紧随其后的那次点击，页面不切走。
        if (near || self) {
            swallowNextClick = true;
            swallowAt = android.os.SystemClock.uptimeMillis();
        }
        // ★ 无条件日志（低频，只在长按时发生）：面板到底有没有弹、走的是哪条路。
        H.log("event=mine_entry_own_panel shown=" + (near || self)
                + " via=" + (near ? "near" : "self"));
    }

    /**
     * 自己托管一个 {@link FloatingPanel}，直接挂到按下 View 的**根视图**上。
     *
     * <p>为什么不走 {@code FloatingController}：真机证据显示悬浮层虽然 attach 了，但 attach 到的是
     * `NewMapActivity`（没有可见窗口），而 tab 栏属于 `SplashActivity` 的窗口 ⇒ 按窗口恒等找不到。
     * 自己挂则完全不依赖这些：{@code getRootView()} 一定是真正在显示的那个 DecorView。
     */
    private static boolean showOwnPanel(final View anchor) {
        if (anchor == null) return false;
        try {
            FloatingPanel open = SELF_PANEL.get();
            if (open != null && open.isOpen()) {          // 再长按一次 = 收起
                open.close();
                removeOwnPanel();
                H.log("event=mine_entry_own_panel closed=true");
                return true;
            }
            final View root = anchor.getRootView();
            if (!(root instanceof ViewGroup)) return false;
            final ViewGroup decor = (ViewGroup) root;
            final Context context = anchor.getContext();
            if (context == null) return false;

            final FloatingPanel panel = new FloatingPanel(context, isNight(context));
            final FrameLayout host = new FrameLayout(context);
            host.setClipChildren(false);
            host.addView(panel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            decor.addView(host, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            SELF_HOST = new WeakReference<ViewGroup>(host);
            SELF_PANEL = new WeakReference<FloatingPanel>(panel);
            panel.setOnClose(new Runnable() {
                @Override public void run() { removeOwnPanel(); }
            });
            // ★ 必须等这一帧真正 attach + layout 完再 open()，否则卡片停在屏幕外（详见类注释）。
            host.post(new Runnable() {
                @Override public void run() {
                    try {
                        panel.open();
                        H.log("event=mine_entry_own_panel shown=true host="
                                + decor.getClass().getSimpleName()
                                + " size=" + decor.getWidth() + "x" + decor.getHeight());
                    } catch (Throwable t) {
                        H.log("event=mine_entry_own_panel_error err=" + t.getClass().getSimpleName());
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            H.log("event=mine_entry_own_panel_error err=" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 摘掉自己托管的面板（幂等）。 */
    private static void removeOwnPanel() {
        try {
            ViewGroup host = SELF_HOST.get();
            SELF_HOST = new WeakReference<ViewGroup>(null);
            SELF_PANEL = new WeakReference<FloatingPanel>(null);
            if (host == null) return;
            ViewParent parent = host.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(host);
            host.removeAllViews();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** 按下的是不是「我的」tab（只看局部文本，最多上溯 4 层，不扫树）。 */
    private static boolean looksLikeMineTab(View view) {
        try {
            View node = view;
            for (int up = 0; up < 4 && node != null; up++) {
                CharSequence label = labelOf(node);
                if (label != null) return LABEL_MINE.contentEquals(label);
                ViewParent parent = node.getParent();
                if (!(parent instanceof ViewGroup)) break;
                node = (View) parent;
            }
        } catch (Throwable ignored) {
            // 判定失败就不触发，宁可不弹也不误弹
        }
        return false;
    }

    /** 该 View 自己或它的直接子 View 里的文本（只看一层）。 */
    private static CharSequence labelOf(View root) {
        if (root instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) root).getText();
            if (text != null && text.length() > 0) return text;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            int count = group.getChildCount();
            for (int i = 0; i < count && i < 8; i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.TextView) {
                    CharSequence text = ((android.widget.TextView) child).getText();
                    if (text != null && text.length() > 0) return text;
                }
            }
        }
        return null;
    }

    private static boolean isNight(Context context) {
        try {
            int mode = context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }
}