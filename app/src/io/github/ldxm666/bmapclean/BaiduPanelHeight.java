package io.github.ldxm666.bmapclean;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 百度首页面板高度配方 —— 「用多少滑多少」（第三条路）。
 *
 * <h2>要解决的问题</h2>
 * 首页抽屉（{@code front_container}）是 {@code match_parent}，引擎算出来的展开态顶边
 * {@code mMinOffset = max(0, mParentHeight - v.getHeight()) + extendHeight}，而
 * {@code extendHeight} 在整个 APK 里零写点 ⇒ 恒 0 ⇒ 展开位恒为 0（整屏）。
 * 于是内容只有 234px 高，面板却拉满 2038px —— 上滑之后中间那一大片就是空白。
 *
 * <h2>引擎的几何公式（{@code DrawerBehavior.onLayoutChild}，classes16.dex 实证）</h2>
 * <pre>
 * mParentHeight = coordinatorLayout.getHeight() - mHideHeight;
 * peek          = mPeekHeightAuto ? max(mPeekHeightMin, mParentHeight - w*9/16) : mPeekHeight;
 * mMinOffset    = max(0, mParentHeight - v.getHeight()) + extendHeight;   // 展开态顶边
 * mMaxOffset    = max(mParentHeight - peek, mMinOffset);                  // 收起态顶边
 * state 3 -> offsetTopAndBottom(v, mMinOffset)
 * state 4 -> offsetTopAndBottom(v, mMaxOffset)
 * state 5 -> offsetTopAndBottom(v, mParentHeight)
 * </pre>
 * 实测常数（1080×2400 / 420dpi，绑定 {@code origin.apk 8cd41106…}）：
 * {@code mParentHeight=1797}、{@code v.getHeight()=2038}、引擎 {@code peek=793}、
 * 原生 {@code mMaxOffset=1004}、原生 {@code mMinOffset=0}、内容底边 {@code content=234}。
 *
 * <h2>三条路，为什么选第三条</h2>
 * <table>
 *   <tr><th>做法</th><th>结果</th></tr>
 *   <tr><td>不夹（旧版）</td><td>安全，但展开位 0 → 空白 2038-234=1804px</td></tr>
 *   <tr><td>只夹展开上限</td><td>空白没了，但 {@code mPeekHeight} 还是 793；
 *       引擎任何一次 {@code setPeekHeight(793)} 都会把 {@code mMaxOffset} 直接写成
 *       {@code mParentHeight - 793 = 1004}，而 {@code mMinOffset=1563} ⇒
 *       <b>{@code mMaxOffset < mMinOffset}（区间为负）</b> ⇒ 面板在 1563/1004 之间来回跳
 *       （视觉上的"秒切"），并且 {@code clampViewPositionVertical} 拿到
 *       {@code min>max} 的非法区间。</td></tr>
 *   <tr><td><b>第三条路（本实现）</b></td><td>夹展开上限的<b>同时把收起位一起收</b>：
 *       {@code peek = min(引擎peek, content)} ⇒ {@code mMaxOffset = mParentHeight - peek}
 *       = {@code mMinOffset}，<b>区间恰为 0 而不是负</b> ⇒ 没内容可露时就停在内容底部、
 *       不可上滑（内容多时上滑距离 = {@code content - peek}，即"用多少滑多少"）。</td></tr>
 * </table>
 *
 * <h2>写哪几个字段</h2>
 * <pre>
 * base         = max(0, mParentHeight - v.getHeight())      // 实测 = 0（v 是 match_parent）
 * content      = 面板内真实内容的底边（逐叶子量，排除整高背景层）
 * targetMin    = mParentHeight - content                     // 展开态顶边
 * peek         = min(enginePeek, content)                    // ★ 收起位跟着一起收
 * targetMax    = mParentHeight - peek                        // 收起态顶边（>= targetMin）
 * extendHeight = targetMin - base
 * mMinOffset   = targetMin
 * mPeekHeight  = peek
 * mMaxOffset   = targetMax
 * </pre>
 * <b>不调 {@code offsetTopAndBottom}</b> —— 位置全部交给原生
 * {@code onLayoutChild} / {@code smoothSlideViewTo}，所以不会有"秒切"。
 * 字段在 {@code chain.proceed()} <b>之前</b>写好，原生这一次布局就用我们的值算并落位。
 *
 * <h2>过渡保护（无重影）</h2>
 * <ul>
 *   <li>{@code DRAGGING(1)} / {@code SETTLING(2)} / {@code HIDDEN(5)} 一律直接返回
 *       （藏起来时原生会把内容重新撑满，量出来是整屏高，照着写会把夹好的值重置回原生）；</li>
 *   <li>测高防抖：连续两次量到同一个 {@code content} 才认（实测同一 pid 相隔 7ms 会量出
 *       361/234，内容在纵向滑动动画中）；</li>
 *   <li>背景填充层 {@code aihome_panel_bg*} / {@code aihome_panel_top_shadow} 整棵排除
 *       （它们与面板等高，是纯色底不是内容）；</li>
 *   <li>{@code dispatchOnSlide} 在区间为 0 时会算出 {@code 0/0 = NaN}，会把 NaN 灌进
 *       {@code slideOffsetSubject} → {@code setTranslationY(NaN)}。这里挂一个守卫，
 *       退化区间直接跳过回调。</li>
 * </ul>
 *
 * <h2>热路径零开销</h2>
 * 稳态（数值没变）路径上：反射 {@code Field} 全部缓存、资源 id 全部缓存、
 * 视图树扫描不产生任何对象、<b>不打日志、不拼字符串</b>。
 * 所有诊断输出都 gate 在 {@link Cfg#debug()}（{@code debug_log}，默认关）之后。
 * 这是上一轮腾讯那边四个 ANR 的直接教训：高频路径里反射 + 拼 200 字符日志 = ANR。
 *
 * <p>失效安全：找不到类/字段、量不到内容、数值越界、{@code mHideable=false}（收 peek 会把
 * 面板钉死）、{@code mPeekHeightAuto=true}（写 peek 无效）——任何一步不确定都直接返回，
 * 保持百度原生行为。
 */
public final class BaiduPanelHeight {

    private BaiduPanelHeight() {}

    /** 首页抽屉容器 id 名（{@code res/3oU.xml} 实证）。 */
    private static final String ID_FRONT = "front_container";
    /** 面板根容器。 */
    private static final String ID_PANEL = "home_panel_container";
    /** 整块背景填充层（与面板等高，不是内容）。 */
    private static final String ID_BG = "aihome_panel_bg";
    /** 顶部投影，同理不是内容。 */
    private static final String ID_SHADOW = "aihome_panel_top_shadow";

    /** 内容底边小于这个值就当作没量到（避免把自己越夹越小）。 */
    private static final int MIN_CONTENT_PX = 120;

    /**
     * 取证期临时强制开日志（只影响本类，不牵连 {@link Cfg#FORCE_DEBUG} 的全模块开关）。
     * <b>交付版必须为 false</b>；此时诊断输出由 {@code debug_log} 开关控制，默认关。
     */
    static final boolean PROBE = false;

    private static boolean dbg() {
        return PROBE || Cfg.debug();
    }

    /* ── DrawerBehavior 状态常量：反射读，不硬编码 ───────────────────── */
    private static volatile int S_DRAGGING = 1;
    private static volatile int S_SETTLING = 2;
    private static volatile int S_EXPANDED = 3;
    private static volatile int S_COLLAPSED = 4;
    private static volatile int S_HIDDEN = 5;

    /**
     * 引擎最后一次 {@code setPeekHeight(int)} 的入参。
     *
     * <p>必须单独记：我们自己也会写 {@code mPeekHeight}，写完之后
     * {@code mParentHeight - mMaxOffset} 就不再等于引擎的 peek 了，没法反推。
     */
    private static volatile Object ENGINE_SHEET;
    private static volatile int ENGINE_PEEK;

    /** 全 APK 只有一个 {@code DrawerBehavior} 实例，单槽缓存即可（零分配）。 */
    private static volatile Ctx CTX;

    /** 单块面板的全部缓存：反射字段 + 资源 id + 测高防抖 + 上次写入值。 */
    static final class Ctx {
        Object sheet;
        View drawer;

        Field fState, fParent, fMin, fMax, fPeek, fAuto, fExtend, fHideable, fHide;
        Field fCallback, fViewRef;
        Method mOnStateChanged;

        int idFront, idPanel;
        /** 背景填充层的资源 id 列表（整棵排除用，纯 int 比较，零分配）。 */
        int[] bgIds;

        /* 测高防抖 */
        int lastContent;
        int hits;
        boolean settled;
        /** 是否已经催过一次布局（有界，每抽屉最多一次）。 */
        boolean nudged;

        /* 上次真正写进去的值（幂等判断，避免每次布局都写字段） */
        boolean wrote;
        int wExtend, wMin, wMax, wPeek, wParent, wContent;
    }

    public static void install(ClassLoader cl) {
        Class<?> behavior = H.cls(cl, "com.baidu.mapframework.drawer.design.DrawerBehavior");
        if (behavior == null) {
            H.log(Log.WARN, MainHook.TAG, "baidu_panel miss DrawerBehavior");
            return;
        }
        S_DRAGGING = staticInt(behavior, "STATE_DRAGGING", S_DRAGGING);
        S_SETTLING = staticInt(behavior, "STATE_SETTLING", S_SETTLING);
        S_EXPANDED = staticInt(behavior, "STATE_EXPANDED", S_EXPANDED);
        S_COLLAPSED = staticInt(behavior, "STATE_COLLAPSED", S_COLLAPSED);
        S_HIDDEN = staticInt(behavior, "STATE_HIDDEN", S_HIDDEN);
        BaiduPanelProbe.install(cl);

        // ① 引擎每一次改 peek 都记下来 —— 这是我们判断"引擎想要多高"的唯一可靠来源。
        //    同时兜住"引擎把 mMaxOffset 直接写成 mParentHeight - peek"造成的区间反转。
        H.hook(H.method(behavior, "setPeekHeight", 1), "baidu_panel_limit_peek", chain -> {
            Object self = chain.getThisObject();
            Object arg = chain.getArg(0);
            if (arg instanceof Integer) {
                ENGINE_SHEET = self;
                ENGINE_PEEK = (Integer) arg;
            }
            Object result = chain.proceed();
            repairRange(self);
            return result;
        });

        // ② 布局期：原生刚算完就落位，所以我们的值必须在 proceed() **之前**写好。
        //    proceed() 之后再量一次内容，供下一次布局使用（测高防抖在这一侧）。
        H.hook(H.method(behavior, "onLayoutChild", 3), "baidu_panel_limit_layout", chain -> {
            Object self = chain.getThisObject();
            Object coord = chain.getArg(0);
            Object child = chain.getArg(1);
            if (child instanceof View && coord instanceof View) {
                applyIfSettled(self, (View) coord, (View) child);       // ← 写字段（proceed 前）
            }
            Object result = chain.proceed();
            if (child instanceof View && coord instanceof View) {
                measure(self, (View) coord, (View) child);               // ← 量内容（proceed 后）
                if (dbg()) {
                    BaiduPanelProbe.afterLayout(self, (View) child, "native");
                }
            }
            return result;
        });

        // ③ 手势起点：此刻一定不在拖动中，再量一次并夹一次（拖动的 clamp 读的就是这两个字段）。
        H.hook(H.method(behavior, "onStartNestedScroll", 5), "baidu_panel_limit_start", chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            Object coord = chain.getArg(0);
            Object child = chain.getArg(1);
            if (child instanceof View && coord instanceof View) {
                measure(self, (View) coord, (View) child);
                applyIfSettled(self, (View) coord, (View) child);
            }
            return result;
        });

        // ④ NaN 守卫：区间为 0 时 dispatchOnSlide 会算 0/0，把 NaN 灌进
        //    slideOffsetSubject → setTranslationY(NaN)。退化区间直接跳过回调。
        H.hook(H.method(behavior, "dispatchOnSlide", 2), "baidu_panel_slide_guard", chain -> {
            Ctx c = CTX;
            if (c != null) {
                Object self = chain.getThisObject();
                if (c.sheet == self) {
                    int top = (Integer) chain.getArg(0);
                    int max = c.fMax.getInt(self);
                    int min = c.fMin.getInt(self);
                    if (top <= max && max <= min) return null;   // 0/0 -> 跳过，不产生 NaN
                }
            }
            return chain.proceed();
        });

        // ⑤ 钉死态（mMinOffset == mMaxOffset）下不报 EXPANDED(3)。
        //    此时 3(展开) 与 4(收起) 是**同一个位置**，但宿主会因为"展开"去重算地图边距 /
        //    切换内容布局 —— 真机实测：state=3 期间整屏闪白（地图层 + 面板内容都不绘制），
        //    state 回到 4 立刻恢复。区间为 0 时"展开"本来就是无意义的，直接落成 COLLAPSED。
        //    注意：这是状态语义归一，几何一个像素都不动。
        H.hook(H.method(behavior, "setStateInternal", 1), "baidu_panel_pin_state", chain -> {
            Ctx c = CTX;
            if (c != null) {
                Object self = chain.getThisObject();
                if (c.sheet == self) {
                    int want = (Integer) chain.getArg(0);
                    if (want == S_EXPANDED && c.fMin.getInt(self) == c.fMax.getInt(self)) {
                        if (c.fState.getInt(self) == S_COLLAPSED) return null;   // 已在收起位，无需变更
                        collapseInstead(c, self);
                        return null;
                    }
                }
            }
            return chain.proceed();
        });

        H.log(Log.INFO, MainHook.TAG, "baidu_panel states dragging=" + S_DRAGGING
                + " settling=" + S_SETTLING + " expanded=" + S_EXPANDED
                + " collapsed=" + S_COLLAPSED + " hidden=" + S_HIDDEN);
    }

    /** 等价于原生 {@code setStateInternal(STATE_COLLAPSED)}（改 mState + 通知回调）。 */
    private static void collapseInstead(Ctx c, Object sheet) throws Exception {
        c.fState.setInt(sheet, S_COLLAPSED);
        Object ref = c.fViewRef.get(sheet);
        View view = ref instanceof java.lang.ref.WeakReference
                ? (View) ((java.lang.ref.WeakReference<?>) ref).get() : null;
        Object callback = c.fCallback.get(sheet);
        if (view == null || callback == null || c.mOnStateChanged == null) return;
        c.mOnStateChanged.invoke(callback, view, S_COLLAPSED);
    }

    /* ── 引擎 setPeekHeight 之后：保证 mMaxOffset >= mMinOffset ────────── */

    private static void repairRange(Object sheet) {
        try {
            Ctx c = CTX;
            if (c == null || c.sheet != sheet) return;
            int min = c.fMin.getInt(sheet);
            int parent = c.fParent.getInt(sheet);
            int peek = c.fPeek.getInt(sheet);
            int want = parent - peek;
            if (want < min) want = min;
            if (c.fMax.getInt(sheet) != want) c.fMax.setInt(sheet, want);
        } catch (Throwable ignored) {
            // 失效安全：修不了就保持原生
        }
    }

    /* ── 测高（proceed 之后，内容已经落位） ──────────────────────────── */

    private static void measure(Object sheet, View coord, View drawer) {
        try {
            Ctx c = ctx(sheet, coord, drawer);
            if (c == null) return;
            int state = c.fState.getInt(sheet);
            if (state != S_EXPANDED && state != S_COLLAPSED) return;
            if (drawer.getId() != c.idFront && c.idFront != 0) return;

            int content = contentBottom(c, drawer);
            if (content < MIN_CONTENT_PX) return;

            if (content != c.lastContent) {
                c.lastContent = content;
                c.hits = 0;
                c.settled = false;
                return;
            }
            if (c.hits < 1) {
                c.hits++;
                c.settled = true;
                // 冷启动时抽屉只布局两次：第 1 次量到 content、第 2 次才判定"稳定"，
                // 而"写字段"要发生在第 3 次布局的 proceed 之前。没有第 3 次布局的话，
                // 面板会一直停在未夹紧的位置（用户看到的就是那片空白），直到第一次手势。
                // 这里补一次有界的 requestLayout（每个抽屉最多一次），把第 3 次布局催出来。
                if (!c.wrote && !c.nudged) {
                    c.nudged = true;
                    drawer.requestLayout();
                }
                return;
            }
            c.settled = true;
        } catch (Throwable t) {
            if (dbg()) H.log(Log.WARN, MainHook.TAG, "baidu_panel measure " + t);
        }
    }

    /* ── 夹紧（proceed 之前，原生这一次布局就用我们的值） ─────────────── */

    private static void applyIfSettled(Object sheet, View coord, View drawer) {
        try {
            Ctx c = CTX;
            if (c == null || c.sheet != sheet || !c.settled) return;
            if (c.drawer != drawer) return;
            if (c.idFront != 0 && drawer.getId() != c.idFront) return;

            int state = c.fState.getInt(sheet);
            if (state != S_EXPANDED && state != S_COLLAPSED) return;   // 1/2/5 一律不动
            if (!c.fHideable.getBoolean(sheet)) return;                // 不可隐藏 -> 收 peek 会把面板钉死
            if (c.fAuto.getBoolean(sheet)) return;                     // peek 由 auto 公式算，写 mPeekHeight 无效

            int parent = coord.getHeight() - c.fHide.getInt(sheet);
            if (parent <= 0) return;
            int vh = drawer.getHeight();
            if (vh <= 0) return;                                       // 还没量到高度，等下一次

            int enginePeek = ENGINE_PEEK;
            if (enginePeek <= 0 || ENGINE_SHEET != sheet) return;

            int content = c.lastContent;

            // 原生公式：mMinOffset = max(0, mParentHeight - v.getHeight()) + extendHeight
            // 实测 v=2038 > mParentHeight=1797 ⇒ base=0 ⇒ mMinOffset ≡ extendHeight。
            int base = parent - vh;
            if (base < 0) base = 0;
            int targetMin = parent - content;
            if (targetMin < base) targetMin = base;

            // ★ 第三条路：收起位跟着内容一起收，区间恰为 0（而不是负）。
            int peek = enginePeek < content ? enginePeek : content;
            int targetMax = parent - peek;
            if (targetMax < targetMin) targetMax = targetMin;

            int extend = targetMin - base;

            if (c.wrote && c.wExtend == extend && c.wMin == targetMin && c.wMax == targetMax
                    && c.wPeek == peek && c.wParent == parent && c.wContent == content) {
                return;                                                // 幂等：稳态零写入
            }
            c.wrote = true;
            c.wExtend = extend;
            c.wMin = targetMin;
            c.wMax = targetMax;
            c.wPeek = peek;
            c.wParent = parent;
            c.wContent = content;

            int oldExtend = c.fExtend.getInt(sheet);
            int oldMin = c.fMin.getInt(sheet);
            int oldMax = c.fMax.getInt(sheet);
            int oldPeek = c.fPeek.getInt(sheet);

            // 只写这四个 int 字段。位置完全交给原生 onLayoutChild / smoothSlideViewTo。
            c.fExtend.setInt(sheet, extend);
            c.fMin.setInt(sheet, targetMin);
            c.fPeek.setInt(sheet, peek);
            c.fMax.setInt(sheet, targetMax);

            if (dbg()) {
                H.log(Log.INFO, MainHook.TAG, "baidu_panel_fit state=" + state
                        + " parent=" + parent + " v=" + vh + " content=" + content
                        + " base=" + base + " enginePeek=" + enginePeek
                        + " extend " + oldExtend + "->" + extend
                        + " min " + oldMin + "->" + targetMin
                        + " max " + oldMax + "->" + targetMax
                        + " peek " + oldPeek + "->" + peek
                        + " range=" + (targetMax - targetMin));
            }
        } catch (Throwable t) {
            if (dbg()) H.log(Log.WARN, MainHook.TAG, "baidu_panel apply " + t);
        }
    }

    /* ── 缓存构建 ────────────────────────────────────────────────────── */

    private static Ctx ctx(Object sheet, View coord, View drawer) {
        Ctx c = CTX;
        if (c != null && c.sheet == sheet && c.drawer == drawer) return c;
        c = build(sheet, coord, drawer);
        return c;
    }

    private static Ctx build(Object sheet, View coord, View drawer) {
        try {
            Class<?> bc = sheet.getClass();
            Ctx c = new Ctx();
            c.sheet = sheet;
            c.drawer = drawer;
            c.fState = f(bc, "mState");
            c.fParent = f(bc, "mParentHeight");
            c.fMin = f(bc, "mMinOffset");
            c.fMax = f(bc, "mMaxOffset");
            c.fPeek = f(bc, "mPeekHeight");
            c.fAuto = f(bc, "mPeekHeightAuto");
            c.fExtend = f(bc, "extendHeight");
            c.fHideable = f(bc, "mHideable");
            c.fHide = f(bc, "mHideHeight");
            c.fCallback = f(bc, "mCallback");
            c.fViewRef = f(bc, "mViewRef");
            if (c.fState == null || c.fParent == null || c.fMin == null || c.fMax == null
                    || c.fPeek == null || c.fAuto == null || c.fExtend == null
                    || c.fHideable == null || c.fHide == null) {
                H.log(Log.WARN, MainHook.TAG, "baidu_panel miss field(s)");
                return null;
            }
            try {
                c.mOnStateChanged = Class.forName(
                                "com.baidu.mapframework.drawer.design.DrawerBehavior$BottomSheetCallback",
                                false, bc.getClassLoader())
                        .getMethod("onStateChanged", View.class, int.class);
                c.mOnStateChanged.setAccessible(true);
            } catch (Throwable ignored) {
                c.mOnStateChanged = null;
            }
            c.idFront = resId(drawer, ID_FRONT);
            c.idPanel = resId(drawer, ID_PANEL);
            View panel = c.idPanel != 0 ? drawer.findViewById(c.idPanel) : drawer;
            c.bgIds = resolveBgIds(panel == null ? drawer : panel);

            if (c.idFront != 0 && drawer.getId() != c.idFront) {
                // 命中的不是首页抽屉 —— 不碰（全 APK 只有一个 DrawerBehavior 实例，正常不会走到）。
                String name = entryName(drawer);
                if (!ID_FRONT.equals(name)) {
                    H.log(Log.WARN, MainHook.TAG, "baidu_panel skip drawer id=" + drawer.getId());
                    return null;
                }
            }

            CTX = c;
            H.log(Log.INFO, MainHook.TAG, "baidu_panel ctx idFront=" + c.idFront
                    + " idPanel=" + c.idPanel + " bgIds=" + c.bgIds.length
                    + " frontName=" + entryName(drawer));
            return c;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "baidu_panel build " + t);
            return null;
        }
    }

    /**
     * 背景填充层的资源 id 集合。只在建缓存时算一次，之后 {@code isBackground} 就是纯 int 比较。
     * 排除它们的理由：{@code aihome_panel_bg} / {@code aihome_panel_bg_bottom} 与面板等高
     * （实测 2038），是整块纯色底 —— 第一版探针被 {@code aihome_panel_bg_bottom}(t=211 h=1827)
     * 骗到量出 content=2038，夹紧量恒为 0。
     */
    private static int[] resolveBgIds(View root) {
        int[] tmp = new int[16];
        int n = 0;
        String[] known = {ID_BG, ID_BG + "_top", ID_BG + "_bottom", ID_SHADOW};
        for (String name : known) {
            int id = resId(root, name);
            if (id != 0 && !contains(tmp, n, id)) tmp[n++] = id;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            int cnt = g.getChildCount();
            for (int i = 0; i < cnt && n < tmp.length; i++) {
                View child = g.getChildAt(i);
                if (child == null) continue;
                String name = entryName(child);
                if (name == null) continue;
                if (name.startsWith(ID_BG) || name.equals(ID_SHADOW)) {
                    int id = child.getId();
                    if (!contains(tmp, n, id)) tmp[n++] = id;
                }
            }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static boolean contains(int[] arr, int len, int value) {
        for (int i = 0; i < len; i++) if (arr[i] == value) return true;
        return false;
    }

    /* ── 内容底边测量（零分配：无对象、无字符串、无反射查找） ──────────── */

    private static int contentBottom(Ctx c, View drawer) {
        View root = c.idPanel != 0 ? drawer.findViewById(c.idPanel) : null;
        if (root == null) root = drawer;
        int offset = topRelativeTo(root, drawer);
        if (offset < 0) {
            root = drawer;
            offset = 0;
        }
        return scan(c, root, offset, 0);
    }

    private static int scan(Ctx c, View node, int offset, int depth) {
        if (node == null || depth > 16) return 0;
        if (node.getVisibility() != View.VISIBLE) return 0;
        if (isBackground(c, node.getId())) return 0;          // 背景填充层整棵跳过
        int base = offset + node.getTop();
        int best = 0;
        boolean anyChild = false;
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = group.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                anyChild = true;
                int bottom = scan(c, child, base, depth + 1);
                if (bottom > best) best = bottom;
            }
        }
        if (!anyChild && node.getWidth() > 0 && node.getHeight() > 0) {
            int bottom = base + node.getHeight();
            if (bottom > best) best = bottom;
        }
        return best;
    }

    private static boolean isBackground(Ctx c, int id) {
        int[] ids = c.bgIds;
        for (int i = 0; i < ids.length; i++) if (ids[i] == id) return true;
        return false;
    }

    /** {@code v} 顶边相对祖先 {@code ancestor} 顶边的偏移；不是后代返回 -1。 */
    private static int topRelativeTo(View v, View ancestor) {
        int sum = 0;
        View cur = v;
        for (int guard = 0; cur != null && guard < 64; guard++) {
            if (cur == ancestor) return sum;
            sum += cur.getTop();
            Object parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return -1;
    }

    /* ── 反射 / 资源小工具（只在建缓存与装机时调用） ───────────────────── */

    private static int resId(View v, String name) {
        try {
            Resources r = v.getResources();
            int id = r.getIdentifier(name, "id", Anchors.PKG);
            if (id == 0) id = r.getIdentifier(name, "id", null);
            return id;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String entryName(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field f(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field field = k.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    private static int staticInt(Class<?> c, String name, int fallback) {
        try {
            return c.getDeclaredField(name).getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
