package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    /**
     * 「我的」tab 的**可接受文案集合**。
     *
     * <p>★ 真机 r21 阻断性 bug：**未登录时高德把 tab 文案渲染成「未登录」**
     * （`TextView t='未登录' rid='tab_name_v2'`），只认「我的」会让**所有全新安装 / 未登录用户
     * 100% 打不开面板**。所以判据必须是不依赖单一文案的集合 + 身份判定。
     */
    private static final String[] MINE_LABELS = { "我的", "未登录", "我的 ", "My" };
    /** 已知的「非我的」tab 文案：末位兜底判据先排除它们，避免误弹。 */
    private static final String[] OTHER_LABELS = {
            "首页", "探索", "工具", "内容", "地图", "打车", "长按说话", "附近", "消息", "我的位置"
    };
    /** `mine_tab_probe` 去重：每个 `label|via` 只打一次，最多 32 条。 */
    private static final Set<String> SEEN_LABELS =
            Collections.synchronizedSet(new LinkedHashSet<String>());
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
    /** DOWN 那一刻就判好的「是不是我的 tab」+ 命中的判据（供 750ms 后自触发直接读）。 */
    private static volatile boolean tabDownMine;
    private static volatile String matchVia = "none";
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
                Object model = null;
                List<Object> args = chain.getArgs();
                if (args != null) {
                    for (int i = 0; i < args.size() && i < 4; i++) {
                        Object a = args.get(i);
                        if (a == null) continue;
                        if (event == null && a instanceof MotionEvent) {
                            event = (MotionEvent) a;
                        } else if (touched == null && a instanceof View) {
                            touched = (View) a;
                        } else if (model == null && !(a instanceof MotionEvent)
                                && !(a instanceof View)) {
                            // dispatchTabTouchEvent(tab, View, MotionEvent) 的第 1 个参数 = tab 模型
                            model = a;
                        }
                    }
                }
                if (event == null) return result;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    tabDownAt = android.os.SystemClock.uptimeMillis();
                    tabDownView = new WeakReference<View>(touched);
                    tabFired = false;
                    // ★ 判定放在 DOWN 这一刻做：此时 tab 模型对象和 View 都拿得到。
                    // 750ms 之后自触发时只读结果（那时可能已经拿不到模型了）。
                    tabDownMine = matchMine(touched, model);
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
     * tab 点击钩子。
     *
     * <p>⚠️ **绝不能再短路**（r22 教训）：上一版在长按后「吞掉」这一次点击（不调用
     * {@code chain.proceed()}，直接返回 {@code Boolean.TRUE}），结果 **首次长按之后 tab 栏整体失效**
     * —— 连续 6 次长按连 {@code dispatchTabTouchEvent} 都不再进我的钩子。
     *
     * <p>原因：libxposed 的 hook 链是**串联**的。某个钩子不调用 {@code chain.proceed()}，
     * 就等于把排在它后面的**所有**钩子一起短路（包括 {@code AmapTabLayout} 自己对 tab 状态的
     * 记账）。宿主 tab 状态机从此不完整，后续触摸被它自己吞掉 —— 表现为「入口死掉」。
     *
     * <p>所以这里恢复成「永远 {@code proceed()}」，只在日志里记录「这次点击是长按之后跟来的」。
     * 代价是页面会顺带切到「我的」，但**远小于「入口整体失效」**。
     */
    private static final XposedInterface.Hooker TAB_CLICK = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                long at = swallowAt;
                if (swallowNextClick && at != 0L
                        && android.os.SystemClock.uptimeMillis() - at < SWALLOW_WINDOW_MS) {
                    H.log("event=mine_entry_click_after_long_press");
                }
            } catch (Throwable ignored) { }
            // 一次点击结束：长按计时与「跟随点击」标记都不该跨次存活。
            swallowNextClick = false;
            swallowAt = 0L;
            tabDownAt = 0L;
            tabDownView = new WeakReference<View>(null);
            return chain.proceed();
        }
    };

    /** 长按真正触发点。 */
    private static void onTabLongPress(View down, long held) {
        boolean mine = tabDownMine;
        String label = textOf(down);
        H.log("event=mine_entry_long_press held=" + held + " mine=" + mine
                + " via=" + matchVia + " label=" + label
                + " view=" + down.getClass().getSimpleName());
        if (!mine) {
            // ★ 负向判据：**不去重、无条件打**。放在这里而不是 matchMine 里，是因为
            // matchMine 在**每次 ACTION_DOWN** 都会跑（轻点也跑），在那里打会刷屏；
            // 而「长按了非我的 tab」是低频事件，正是验证末位兜底是否写宽的证据。
            H.log("event=mine_tab_miss label=" + label + " via=none"
                    + " view=" + down.getClass().getSimpleName());
            return;
        }
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

    /**
     * 判定按下的是不是「我的」tab —— **三层判据，任一命中即算**。
     *
     * <ol>
     *   <li><b>文案集合</b>（{@link #MINE_LABELS}）：`我的` / **`未登录`** / `我的 ` / `My`。
     *       未登录时高德把文案渲染成「未登录」，这一层是最小改动、立刻可用；</li>
     *   <li><b>模型身份</b>：反射 tab 模型对象（`dispatchTabTouchEvent` 的第 1 个参数）的
     *       String 字段，任一包含 `mine`（忽略大小写）即算 —— 不受文案本地化/登录态影响；</li>
     *   <li><b>末位兜底</b>：该 View 是父容器的**最后一个**子 View，且文案**不在**已知的其它 tab
     *       文案集合里。有误判风险，所以只在 ①② 都不成立时才用，并打 `via=index` 便于排查。</li>
     * </ol>
     *
     * <p>结果会通过 {@link #matchVia} 暴露，并打一条去重的 `mine_tab_probe`。
     */
    private static boolean matchMine(View view, Object model) {
        matchVia = "none";
        if (view == null) return false;
        String label = textOf(view);
        boolean mine;
        // ① 文案集合
        if (inSet(MINE_LABELS, label)) {
            matchVia = "label";
            mine = true;
        } else if (modelMentionsMine(model)) {
            // ② 模型身份（id / key / name 里含 mine）
            matchVia = "id";
            mine = true;
        } else if (isLastTab(view) && !inSet(OTHER_LABELS, label)) {
            // ③ 末位兜底（①② 都不成立时才用）
            matchVia = "index";
            mine = true;
        } else {
            mine = false;
        }
        probeOnce(label, matchVia, mine);
        return mine;
    }

    /** 该 View 自己或它的直接子 View 里的文本（只看一层，不扫树）。 */
    private static String textOf(View view) {
        try {
            View node = view;
            for (int up = 0; up < 4 && node != null; up++) {
                CharSequence label = labelOf(node);
                if (label != null) return label.toString().trim();
                ViewParent parent = node.getParent();
                if (!(parent instanceof ViewGroup)) break;
                node = (View) parent;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    private static boolean inSet(String[] set, String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() == 0) return false;
        for (int i = 0; i < set.length; i++) {
            if (set[i].trim().contentEquals(v)) return true;
        }
        return false;
    }

    /** 反射 tab 模型的 String 字段，任一包含 "mine"（忽略大小写）即算命中。 */
    private static boolean modelMentionsMine(Object model) {
        if (model == null) return false;
        // `dispatchTabTouchEvent` 的第 1 个参数在 17.02 上是 **tab id**（可能是 String），
        // 不一定是模型对象 —— 所以先看它本身是不是字符串。
        try {
            if (model instanceof CharSequence) {
                return model.toString().toLowerCase().contains("mine");
            }
        } catch (Throwable ignored) { }
        try {
            Class<?> c = model.getClass();
            for (int depth = 0; depth < 4 && c != null && c != Object.class; depth++) {
                java.lang.reflect.Field[] fields = c.getDeclaredFields();
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i].getType() != String.class) continue;
                    try {
                        fields[i].setAccessible(true);
                        Object v = fields[i].get(model);
                        if (v instanceof String
                                && ((String) v).toLowerCase().contains("mine")) {
                            return true;
                        }
                    } catch (Throwable ignored) { }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** 该 View 是不是父容器的最后一个子 View（「我的」在高德底栏是最后一格）。 */
    private static boolean isLastTab(View view) {
        try {
            ViewParent parent = view.getParent();
            if (!(parent instanceof ViewGroup)) return false;
            ViewGroup group = (ViewGroup) parent;
            int index = group.indexOfChild(view);
            return index >= 0 && index == group.getChildCount() - 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** `mine_tab_probe`：每个 `label|via` 只打一次（最多 32 条），避免长按刷屏。 */
    private static void probeOnce(String label, String via, boolean mine) {
        try {
            if (SEEN_LABELS.size() > 32) return;
            if (SEEN_LABELS.add(label + "|" + via)) {
                H.log("event=mine_tab_probe label=" + label + " via=" + via + " mine=" + mine);
            }
        } catch (Throwable ignored) { }
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