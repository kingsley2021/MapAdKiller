package io.github.ldxm666.mapclean;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 百度内置版的<b>找回入口</b>：长按底部「我的」那一格约 750ms ⇒ 弹出模块自己的设置面板。
 *
 * <h3>为什么需要它</h3>
 * 百度内置版把 {@code MainActivity}（模块自己的设置页）裁掉了，也没有 tab 入口 —— 用户一旦把
 * 「显示悬浮球」关掉就<b>再也回不来</b>（实测踩过，上一轮只能把那一行整行删掉，功能残缺）。
 * 这个类就是那条找回路径，与高德 {@code AmapMineEntry} 同一套已验证的范式：
 * <b>不注入 View、不扫视图树、不依赖 Activity</b>。
 *
 * <h3>锚点（dex-scout2 侦察，classes11，全部非混淆）</h3>
 * <table>
 *   <tr><td>{@code TabPresenter.doSubscribe()V}</td><td>拿 presenter 实例（{@code chain.getThisObject()}）</td></tr>
 *   <tr><td>{@code TabPresenter$Tab}（Enum）</td><td>常量 {@code NEARBY/ROUTE/USER/THIRD/FOURTH}，
 *       <b>{@code USER} = 我的</b> —— 判据只用枚举，<b>绝不用文案</b>（高德踩过「未登录」坑）</td></tr>
 *   <tr><td>{@code getIcon(Tab)ImageView}</td><td>拿「我的」那一格的 View（退 {@code getTextView} →
 *       再退 {@code getIconBg} → 最后 {@code getAnimView}）</td></tr>
 *   <tr><td>{@code clickTab(Tab)V}</td><td>兜底：listener 没挂上时长按仍能吞掉这次点击</td></tr>
 * </table>
 *
 * <h3>为什么不用 dispatchTabTouchEvent / 不 hook View.onTouchEvent</h3>
 * 百度<b>没有</b>高德那种单点触摸分发钩子（底部 5 格是普通 View + {@code OnClickListener}）；
 * 而 hook {@code View.onTouchEvent} 是 framework 方法，会命中整个 App 的<b>所有</b> View ——
 * 地图这种重度视图树会直接掉帧。所以正确做法是：**只给自己关心的那一格挂 OnTouchListener**，
 * 成本 O(1)，且天然只覆盖「我的」。
 */
public final class BaiduMineEntry {

    private static final String TAG = "MapClean";

    /* ------------------------------------------------------------------ 锚点 */

    /** presenter 类（锚点：非混淆）。 */
    private static final String PRESENTER_CLASS =
            "com.baidu.baidumaps.aihome.panel.presenter.TabPresenter";
    /** tab 枚举类的兜底全名（正常情况下从 {@code getIcon} 的参数类型反推，不依赖这个字符串）。 */
    private static final String TAB_ENUM_HINT =
            "com.baidu.baidumaps.aihome.panel.presenter.TabPresenter$Tab";
    /** 拿 presenter 实例的方法（0 参）。 */
    private static final String M_DO_SUBSCRIBE = "doSubscribe";
    /** 兜底吞点击的方法：{@code clickTab(Tab)V}。 */
    private static final String M_CLICK_TAB = "clickTab";
    /** 「我的」那一格 View 的取法，按优先级排列。 */
    private static final String[] M_CELL_GETTERS = { "getIcon", "getTextView", "getIconBg", "getAnimView" };
    /** 「我的」在枚举里的常量名 —— **唯一判据**。 */
    private static final String CONST_USER = "USER";

    /* ------------------------------------------------------------------ 常量 */

    /** 按住多久算长按。与高德一致（实测不会和系统长按冲突）。 */
    private static final long LONG_PRESS_MS = 750L;
    /** 长按之后多久内的 clickTab 算「这一次点击是长按跟来的」。 */
    private static final long SWALLOW_MS = 800L;
    /** 手指移动超过这个距离就取消长按（dp）。 */
    private static final float MOVE_SLOP_DP = 6f;

    /* ------------------------------------------------------------------ 状态 */

    private static volatile ClassLoader hostLoader;
    private static volatile WeakReference<Object> PRESENTER = new WeakReference<Object>(null);
    private static volatile Class<?> tabEnum;
    private static volatile Object userTab;
    /** 已经挂过 listener 的那一格（同一个实例不重复挂）。 */
    private static volatile WeakReference<View> ATTACHED_CELL = new WeakReference<View>(null);

    private static volatile long lastDownAt;
    private static volatile long swallowAt;
    private static volatile float downX, downY;
    private static volatile boolean moved;

    private static volatile WeakReference<ViewGroup> SELF_HOST = new WeakReference<ViewGroup>(null);
    private static volatile WeakReference<FloatingPanel> SELF_PANEL = new WeakReference<FloatingPanel>(null);

    private BaiduMineEntry() {}

    /**
     * 安装找回入口。由 {@code mapclean.MainHook.onPackageReady} 调用。
     *
     * <p>三道门控，**任何一道不满足都一条 hook 都不装**（不是装上再判断）：
     * ① 模块模式（root/LSPosed）—— 用户明确要求长按入口只给免 root 内置版；
     * ② 只在百度进程 —— 高德/腾讯进程绝不能装；
     * ③ 锚点解析失败 —— 跳过并打 {@code anchor_missing}，绝不盲猜。
     */
    public static void install(XposedModule module, String pkg, ClassLoader cl) {
        if (module == null || cl == null) return;
        if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) {
            Log.i(TAG, "event=baidu_mine_entry_skipped reason=module_mode");
            return;
        }
        Vendor.host(pkg);
        if (!Vendor.baidu()) {
            Log.i(TAG, "event=baidu_mine_entry_skipped reason=not_baidu host=" + Vendor.host());
            return;
        }
        hostLoader = cl;
        try {
            Class<?> presenter;
            try {
                presenter = cl.loadClass(PRESENTER_CLASS);
            } catch (Throwable t) {
                Log.i(TAG, "event=baidu_mine_entry_anchor_missing what=presenter class="
                        + PRESENTER_CLASS);
                return;
            }
            if (!resolveTabEnum(presenter)) {
                Log.i(TAG, "event=baidu_mine_entry_anchor_missing what=tab_enum");
                return;
            }
            int n = 0;
            n += hookAll(module, presenter, M_DO_SUBSCRIBE, "baidu_mine_entry_subscribe", SUBSCRIBE);
            n += hookAll(module, presenter, M_CLICK_TAB, "baidu_mine_entry_clicktab", CLICK_TAB);
            n += installResumeHook(module);
            Log.i(TAG, "event=baidu_mine_entry_installed hooks=" + n
                    + " user_tab=" + (userTab == null ? "null" : userTab.toString()));
        } catch (Throwable t) {
            Log.w(TAG, "event=baidu_mine_entry_error err=" + t.getClass().getSimpleName(), t);
        }
    }

    /** 从 {@code getIcon} 的参数类型反推枚举类，并取出 {@code USER} 常量。 */
    private static boolean resolveTabEnum(Class<?> presenter) {
        Class<?> found = null;
        try {
            for (Class<?> k = presenter; k != null && k != Object.class; k = k.getSuperclass()) {
                Method[] methods = k.getDeclaredMethods();
                for (int i = 0; i < methods.length; i++) {
                    Method m = methods[i];
                    if (!"getIcon".equals(m.getName())) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 1 && ps[0].isEnum()) { found = ps[0]; break; }
                }
                if (found != null) break;
            }
        } catch (Throwable ignored) { }
        if (found == null) {
            try { found = hostLoader.loadClass(TAB_ENUM_HINT); } catch (Throwable t) { found = null; }
        }
        if (found == null) return false;
        try {
            Object constant = Enum.valueOf((Class) found, CONST_USER);
            tabEnum = found;
            userTab = constant;
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "event=baidu_mine_entry_anchor_missing what=USER_constant err="
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    /* ------------------------------------------------------------------ 钩子 */

    /** ① 拿 presenter：{@code doSubscribe()} 里 {@code chain.getThisObject()} 就是它。 */
    private static final XposedInterface.Hooker SUBSCRIBE = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                capturePresenter(chain.getThisObject());
            } catch (Throwable ignored) { }
            return result;
        }
    };

    /** ⑤ 兜底吞点击：listener 没挂上（格子被重建）时长按仍不切页。 */
    private static final XposedInterface.Hooker CLICK_TAB = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object tab = tabArg(chain);
                long at = lastDownAt;
                if (tab != null && tab == userTab) {
                    if (at != 0L && android.os.SystemClock.uptimeMillis() - at >= LONG_PRESS_MS) {
                        lastDownAt = 0L;
                        swallowAt = android.os.SystemClock.uptimeMillis();
                        openPanel(cellOrNull());
                        Log.i(TAG, "event=baidu_mine_entry_click_swallowed via=clickTab");
                        return null;      // 不 proceed ⇒ 这一次不切页
                    }
                } else if (tab != null && at != 0L) {
                    // 负向判据：按下过、但点的不是「我的」。不去重（低频）。
                    Log.i(TAG, "event=baidu_mine_tab_miss tab=" + tab + " via=enum");
                }
            } catch (Throwable ignored) { }
            return chain.proceed();
        }
    };

    private static void capturePresenter(Object self) {
        if (self == null) return;
        if (!PRESENTER_CLASS.equals(self.getClass().getName())) {
            // 只认 presenter 本身（父类 MVVMPresenter 也可能被同名方法命中，但那是别的类）。
            Class<?> c = self.getClass();
            boolean ok = false;
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                if (PRESENTER_CLASS.equals(k.getName())) { ok = true; break; }
            }
            if (!ok) return;
        }
        PRESENTER = new WeakReference<Object>(self);
        Log.i(TAG, "event=baidu_mine_entry_presenter captured=" + self.getClass().getSimpleName());
        // doSubscribe 可能在格子建好之前就调了：稍等一帧再挂，避免拿到 null。
        postAttach(300L);
    }

    /** 在下一帧/延时后重挂 listener（必须在主线程）。 */
    private static void postAttach(long delay) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override public void run() { attach(); }
                    }, delay);
        } catch (Throwable ignored) { }
    }

    /**
     * 把 {@link #TOUCH} 挂到「我的」那一格的 View 上（幂等：同一个 View 实例只挂一次）。
     *
     * <p>为什么每次 resume 都要重挂：底部栏的格子可能被重建（主题切换 / 页面重建），
     * 新实例上没有任何 listener。
     */
    private static void attach() {
        try {
            Object presenter = PRESENTER.get();
            if (presenter == null) return;
            View cell = cellOf(presenter);
            if (cell == null) {
                Log.i(TAG, "event=baidu_mine_entry_anchor_missing what=cell");
                return;
            }
            if (ATTACHED_CELL.get() == cell) return;
            cell.setOnTouchListener(TOUCH);
            ATTACHED_CELL = new WeakReference<View>(cell);
            Log.i(TAG, "event=baidu_mine_entry_attached cell=" + cell.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.w(TAG, "event=baidu_mine_entry_attach_error err=" + t.getClass().getSimpleName(), t);
        }
    }

    /** 按优先级从 presenter 上取「我的」那一格的 View。 */
    private static View cellOf(Object presenter) {
        for (int i = 0; i < M_CELL_GETTERS.length; i++) {
            Method m = oneArgMethod(presenter.getClass(), M_CELL_GETTERS[i]);
            if (m == null) continue;
            try {
                m.setAccessible(true);
                Object r = m.invoke(presenter, userTab);
                if (r instanceof View) return (View) r;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static View cellOrNull() {
        try {
            Object p = PRESENTER.get();
            if (p != null) {
                View v = cellOf(p);
                if (v != null) return v;
            }
        } catch (Throwable ignored) { }
        return ATTACHED_CELL.get();
    }

    /** 在类及其父类里找一个「只接受 Tab 枚举」的方法。 */
    private static Method oneArgMethod(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] methods;
            try { methods = k.getDeclaredMethods(); } catch (Throwable t) { continue; }
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (!name.equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && tabEnum != null && ps[0].isAssignableFrom(tabEnum)) return m;
            }
        }
        return null;
    }

    /** 从 chain 参数里找 tab 枚举实例。 */
    private static Object tabArg(XposedInterface.Chain chain) {
        try {
            List<Object> args = chain.getArgs();
            if (args == null) return null;
            for (int i = 0; i < args.size() && i < 4; i++) {
                Object a = args.get(i);
                if (a != null && tabEnum != null && tabEnum.isInstance(a)) return a;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * ④ 自己挂在「我的」格子上的触摸监听。
     *
     * <p>三条要点（真机踩出来的，别改）：
     * <ul>
     *   <li>DOWN 返回 <b>false</b>：让 View 自己的 onTouchEvent 继续跑，这样后续 MOVE/UP 才送得到；</li>
     *   <li>长按命中时 UP 返回 <b>true</b>：消费掉事件 ⇒ 原生 onClick 不触发 ⇒ <b>页面不切走</b>；</li>
     *   <li>未命中一律返回 false：轻点必须照常切 tab。</li>
     * </ul>
     */
    private static final View.OnTouchListener TOUCH = new View.OnTouchListener() {
        @Override public boolean onTouch(View v, MotionEvent e) {
            try {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    lastDownAt = android.os.SystemClock.uptimeMillis();
                    downX = e.getRawX();
                    downY = e.getRawY();
                    moved = false;
                    return false;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    if (!moved) {
                        float slop = MOVE_SLOP_DP * v.getResources().getDisplayMetrics().density;
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (dx * dx + dy * dy > slop * slop) moved = true;
                    }
                    return false;
                }
                if (action == MotionEvent.ACTION_UP) {
                    long at = lastDownAt;
                    lastDownAt = 0L;
                    if (at == 0L || moved) return false;
                    long held = android.os.SystemClock.uptimeMillis() - at;
                    if (held < LONG_PRESS_MS) return false;      // 轻点 ⇒ 放行，照常切页
                    swallowAt = android.os.SystemClock.uptimeMillis();
                    Log.i(TAG, "event=baidu_mine_entry_long_press held=" + held
                            + " mine=true via=enum label=" + labelOf(v)
                            + " view=" + v.getClass().getSimpleName());
                    openPanel(v);
                    return true;                                  // 消费 ⇒ 不切页
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    lastDownAt = 0L;
                    moved = false;
                }
            } catch (Throwable ignored) {
                // 绝不影响宿主
            }
            return false;
        }
    };

    /** 重挂入口：Application.onCreate → 注册生命周期回调 → 每次 resume 重挂 listener。 */
    private static int installResumeHook(XposedModule module) {
        try {
            module.hook(Application.class.getDeclaredMethod("onCreate"))
                    .setId("baidu_mine_entry.lifecycle")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof Application) {
                                    ((Application) self).registerActivityLifecycleCallbacks(
                                            new android.app.Application.ActivityLifecycleCallbacks() {
                                                @Override public void onActivityCreated(
                                                        android.app.Activity a, android.os.Bundle b) { }
                                                @Override public void onActivityStarted(android.app.Activity a) { }
                                                @Override public void onActivityResumed(android.app.Activity a) {
                                                    postAttach(200L);   // 格子可能被重建
                                                }
                                                @Override public void onActivityPaused(android.app.Activity a) { }
                                                @Override public void onActivityStopped(android.app.Activity a) { }
                                                @Override public void onActivitySaveInstanceState(
                                                        android.app.Activity a, android.os.Bundle b) { }
                                                @Override public void onActivityDestroyed(android.app.Activity a) { }
                                            });
                                }
                            } catch (Throwable ignored) { }
                            return result;
                        }
                    });
            return 1;
        } catch (Throwable t) {
            Log.w(TAG, "event=baidu_mine_entry_lifecycle_error err="
                    + t.getClass().getSimpleName(), t);
            return 0;
        }
    }

    /** 按方法名挂全部重载（本类 + 父类），跳过抽象方法。 */
    private static int hookAll(XposedModule module, Class<?> c, String method,
                               String id, XposedInterface.Hooker hooker) {
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] methods;
            try { methods = k.getDeclaredMethods(); } catch (Throwable t) { continue; }
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (!m.getName().equals(method)) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m)
                            .setId(id + "#" + k.getSimpleName() + "#" + n)
                            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                            .intercept(hooker);
                    n++;
                } catch (Throwable t) {
                    Log.w(TAG, "event=baidu_mine_entry_hook_error id=" + id
                            + " err=" + t.getClass().getSimpleName());
                }
            }
        }
        return n;
    }

    /* ------------------------------------------------------------------ 面板 */

    /** 打开面板：先按「同一窗口」找浮层，失败再自托管。 */
    private static void openPanel(View anchor) {
        boolean near = false;
        try {
            near = FloatingController.openPanelNear(anchor);
        } catch (Throwable ignored) { }
        boolean self = false;
        if (!near) self = showOwnPanel(anchor);
        Log.i(TAG, "event=baidu_mine_entry_own_panel shown=" + (near || self)
                + " via=" + (near ? "near" : "self"));
    }

    /**
     * 自己托管一个 {@link FloatingPanel} 挂到锚点的**根视图**上。
     *
     * <p>只依赖 {@code getRootView()}（真正在显示的那个 DecorView，对象恒等），
     * 不依赖 Activity 归属 —— 高德 17.02 实测过「resume 的 Activity 和拥有窗口的不是同一个」。
     */
    private static boolean showOwnPanel(final View anchor) {
        if (anchor == null) return false;
        try {
            FloatingPanel open = SELF_PANEL.get();
            if (open != null && open.isOpen()) {
                open.close();
                removeOwnPanel();
                Log.i(TAG, "event=baidu_mine_entry_own_panel closed=true");
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
            // ★ 必须等这一帧 attach + layout 完再 open()：open() 内部先 card.setTranslationY(420dp)
            // 再动画回 0，同一帧调用会让卡片停在屏幕外（面板 VISIBLE、尺寸也对，但看不见）。
            host.post(new Runnable() {
                @Override public void run() {
                    try {
                        panel.open();
                        Log.i(TAG, "event=baidu_mine_entry_own_panel shown=true host="
                                + decor.getClass().getSimpleName()
                                + " size=" + decor.getWidth() + "x" + decor.getHeight());
                    } catch (Throwable t) {
                        Log.w(TAG, "event=baidu_mine_entry_own_panel_error err="
                                + t.getClass().getSimpleName(), t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "event=baidu_mine_entry_own_panel_error err="
                    + t.getClass().getSimpleName(), t);
            return false;
        }
    }

    private static void removeOwnPanel() {
        try {
            ViewGroup host = SELF_HOST.get();
            SELF_HOST = new WeakReference<ViewGroup>(null);
            SELF_PANEL = new WeakReference<FloatingPanel>(null);
            if (host == null) return;
            ViewParent parent = host.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(host);
            host.removeAllViews();
        } catch (Throwable ignored) { }
    }

    /** 该 View 自己或它的直接子 View 里的文本（只用于日志，不参与判定）。 */
    private static String labelOf(View root) {
        try {
            if (root instanceof android.widget.TextView) {
                CharSequence t = ((android.widget.TextView) root).getText();
                if (t != null && t.length() > 0) return t.toString().trim();
            }
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                int count = g.getChildCount();
                for (int i = 0; i < count && i < 8; i++) {
                    View child = g.getChildAt(i);
                    if (child instanceof android.widget.TextView) {
                        CharSequence t = ((android.widget.TextView) child).getText();
                        if (t != null && t.length() > 0) return t.toString().trim();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "";
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