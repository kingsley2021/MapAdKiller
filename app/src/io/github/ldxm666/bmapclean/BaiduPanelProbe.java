package io.github.ldxm666.bmapclean;

import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * PROBE-ONLY 取证探针（交付前 {@link #ENABLED} 置 false 即完全静默）。
 *
 * <p>目的：把 {@code DrawerBehavior} 的坐标系钉死。前一份报告只有相对值
 * （{@code t=1004 h=2038}），无法判断"面板可见顶边"和"内容底边"在屏幕上的真实位置。
 * 这里全部用 {@code getLocationOnScreen()} 取绝对坐标，并逐叶子扫描出**屏幕坐标系**下
 * 的内容上/下边界，绕开所有相对坐标猜测。
 *
 * <p>输出格式（单行，便于脚本解析）：
 * <pre>
 * GEO state=.. parent=.. minOff=.. maxOff=.. extend=.. peek=..(auto=..)
 *     drawerTop=.. drawerScrTop=.. drawerScrBottom=..
 *     cTop=.. cBottom=.. cScreenTop=.. cScreenBottom=..
 *     winH=.. dispH=.. gridScr=.. tabsScr=.. listH=.. listVis=..
 * </pre>
 */
public final class BaiduPanelProbe {

    /** 取证期 true；交付前置 false。 */
    public static final boolean ENABLED = true;

    private static final String TAG = "baidu_panel_geo";
    private static final int MAX_LINES = 300;
    private static volatile int lines;
    private static volatile boolean installed;
    private static volatile String lastKey = "";

    /** 记录"最近一次可见状态"下的内容边界，供幂等判断与交叉核对。 */
    public static volatile int lastContentTop = -1;
    public static volatile int lastContentBottom = -1;

    private BaiduPanelProbe() {}

    public static void install(ClassLoader cl) {
        if (!ENABLED || installed) return;
        installed = true;
        H.log(Log.INFO, MainHook.TAG, TAG + " probe installed");
    }

    /** 每次 {@code onLayoutChild} 之后、{@code BaiduPanelHeight} 写字段之前调用。 */
    public static void afterLayout(Object sheet, View drawer, String phase) {
        if (!ENABLED) return;
        try {
            int state = getInt(sheet, "mState");
            int parent = getInt(sheet, "mParentHeight");
            int minOff = getInt(sheet, "mMinOffset");
            int maxOff = getInt(sheet, "mMaxOffset");
            int extend = getInt(sheet, "extendHeight");
            int peek = getInt(sheet, "mPeekHeight");
            boolean auto = getBool(sheet, "mPeekHeightAuto");

            int[] bounds = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE};
            int[] all = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE};
            int[] raw = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE};
            int[] dr = new int[2];
            drawer.getLocationOnScreen(dr);
            scanScreen(drawer, bounds, 0);
            scanVisibleAll(drawer, all, 0);
            scanUnclipped(drawer, raw, 0, drawer.getHeight());

            int cTop = bounds[0] == Integer.MAX_VALUE ? -1 : bounds[0] - dr[1];
            int cBot = bounds[1] == Integer.MIN_VALUE ? -1 : bounds[1] - dr[1];
            int aTop = all[0] == Integer.MAX_VALUE ? -1 : all[0] - dr[1];
            int aBot = all[1] == Integer.MIN_VALUE ? -1 : all[1] - dr[1];
            int rTop = raw[0] == Integer.MAX_VALUE ? -1 : raw[0] - dr[1];
            int rBot = raw[1] == Integer.MIN_VALUE ? -1 : raw[1] - dr[1];

            View grid = findById(drawer, "component_container3");
            View list = findById(drawer, "component_list_new");
            View ufo = findById(drawer, "ufo_root");

            String key = state + ":" + parent + ":" + minOff + ":" + maxOff + ":"
                    + cTop + ":" + cBot + ":" + aTop + ":" + aBot + ":" + dr[1] + ":" + drawer.getTop();
            if (key.equals(lastKey)) return;
            lastKey = key;
            if (lines++ > MAX_LINES) return;

            lastContentTop = cTop;
            lastContentBottom = cBot;

            H.log(Log.INFO, MainHook.TAG, TAG
                    + " phase=" + phase
                    + " state=" + state
                    + " parent=" + parent
                    + " minOff=" + minOff
                    + " maxOff=" + maxOff
                    + " extend=" + extend
                    + " peek=" + peek + "(auto=" + auto + ")"
                    + " drawerTop=" + drawer.getTop()
                    + " drawerScr=" + dr[1] + ".." + (dr[1] + drawer.getHeight())
                    + " leafScr=" + bounds[0] + ".." + bounds[1]
                    + " leafRel=" + cTop + ".." + cBot
                    + " allScr=" + all[0] + ".." + all[1]
                    + " allRel=" + aTop + ".." + aBot
                    + " rawScr=" + raw[0] + ".." + raw[1]
                    + " rawRel=" + rTop + ".." + rBot
                    + " winH=" + winH(drawer)
                    + " dispH=" + dispH(drawer)
                    + " listH=" + h(list) + " listVis=" + vis(list)
                    + " grid=" + scr(grid)
                    + " ufo=" + scr(ufo));
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, TAG + " fail " + t);
        }
    }

    /**
     * 逐叶子扫描**屏幕坐标**下可见内容的上下边界。
     * 排除整高背景层（{@code aihome_panel_bg*} / {@code aihome_panel_top_shadow}）——
     * 它们是纯色底，不是内容。
     */
    static void scanScreen(View node, int[] out, int depth) {
        if (node == null || depth > 16) return;
        if (node.getVisibility() != View.VISIBLE) return;
        if (isBackground(node)) return;
        int[] loc = new int[2];
        node.getLocationOnScreen(loc);
        int top = loc[1];
        int bottom = top + node.getHeight();
        boolean anyChild = false;
        if (node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) continue;
                anyChild = true;
                scanScreen(c, out, depth + 1);
            }
        }
        if (!anyChild && node.getWidth() > 0 && node.getHeight() > 0) {
            if (top < out[0]) out[0] = top;
            if (bottom > out[1]) out[1] = bottom;
        }
    }

    /**
     * 逐**叶子**扫描可见内容的屏幕矩形，用 {@code getGlobalVisibleRect} —— 它同时反映
     * 真实可见性和裁剪，比 {@code getVisibility()} 可靠（实测 layout 期
     * {@code component_list_new} 的 visibility 还是 4/INVISIBLE，但内容已经在渲染）。
     *
     * <p>排除整高背景层（纯色底，不是内容）。
     */
    public static void scanVisible(View node, int[] out, int depth) {
        if (node == null || depth > 16) return;
        if (isBackground(node)) return;
        boolean anyChild = false;
        if (node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null) continue;
                anyChild = true;
                scanVisible(c, out, depth + 1);
            }
        }
        if (anyChild) return;
        if (node.getWidth() <= 0 || node.getHeight() <= 0) return;
        Rect r = new Rect();
        boolean ok;
        try { ok = node.getGlobalVisibleRect(r); } catch (Throwable t) { ok = false; }
        if (!ok || r.isEmpty()) return;
        if (r.top < out[0]) out[0] = r.top;
        if (r.bottom > out[1]) out[1] = r.bottom;
    }

    /** 逐**节点**（不只叶子）取 {@code getGlobalVisibleRect} 的并集，用于找内容整体边界。 */
    public static void scanVisibleAll(View node, int[] out, int depth) {
        if (node == null || depth > 16) return;
        if (isBackground(node)) return;
        Rect r = new Rect();
        boolean ok;
        try { ok = node.getGlobalVisibleRect(r); } catch (Throwable t) { ok = false; }
        if (ok && !r.isEmpty()) {
            if (r.top < out[0]) out[0] = r.top;
            if (r.bottom > out[1]) out[1] = r.bottom;
        }
        if (node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0; i < g.getChildCount(); i++) scanVisibleAll(g.getChildAt(i), out, depth + 1);
        }
    }

    /**
     * 逐**叶子**取「未被裁剪」的屏幕矩形（{@code getLocationOnScreen + getHeight}），
     * 不看 visibility 也不看裁剪 —— 用于判断内容真实延展范围。
     *
     * <p>排除：整高背景层、以及高度等于面板高度的整高包装（它们不是内容）。
     */
    public static void scanUnclipped(View node, int[] out, int depth, int panelH) {
        if (node == null || depth > 16) return;
        if (isBackground(node)) return;
        boolean anyChild = false;
        if (node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null) continue;
                anyChild = true;
                scanUnclipped(c, out, depth + 1, panelH);
            }
        }
        if (anyChild) return;
        if (node.getWidth() <= 0 || node.getHeight() <= 0) return;
        if (panelH > 0 && node.getHeight() >= panelH) return;   // 整高包装
        int[] loc = new int[2];
        try { node.getLocationOnScreen(loc); } catch (Throwable t) { return; }
        int top = loc[1];
        int bottom = top + node.getHeight();
        if (top < out[0]) out[0] = top;
        if (bottom > out[1]) out[1] = bottom;
    }

    private static boolean isBackground(View v) {
        String n = entryName(v);
        if (n == null) return false;
        return n.equals("aihome_panel_bg") || n.startsWith("aihome_panel_bg_")
                || n.equals("aihome_panel_top_shadow");
    }

    private static String scr(View v) {
        if (v == null) return "null";
        int[] l = new int[2];
        try { v.getLocationOnScreen(l); } catch (Throwable ignored) {}
        return l[1] + ".." + (l[1] + v.getHeight()) + "/h" + v.getHeight() + "/v" + v.getVisibility();
    }

    private static String h(View v) { return v == null ? "null" : String.valueOf(v.getHeight()); }
    private static String vis(View v) { return v == null ? "null" : String.valueOf(v.getVisibility()); }

    private static int winH(View v) {
        try { return v.getRootView().getHeight(); } catch (Throwable t) { return -1; }
    }

    private static int dispH(View v) {
        try { return v.getResources().getDisplayMetrics().heightPixels; } catch (Throwable t) { return -1; }
    }

    static View findById(View root, String name) {
        try {
            int id = root.getResources().getIdentifier(name, "id", Anchors.PKG);
            if (id == 0) id = root.getResources().getIdentifier(name, "id", null);
            if (id == 0) return null;
            return root.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String entryName(View v) {
        try { return v.getResources().getResourceEntryName(v.getId()); } catch (Throwable t) { return null; }
    }

    static Field field(Object t, String n) throws NoSuchFieldException {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(n); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(n);
    }

    static int getInt(Object t, String n) throws Exception { return field(t, n).getInt(t); }
    static boolean getBool(Object t, String n) throws Exception { return field(t, n).getBoolean(t); }

    /** 供外部 dump 内容树（取证用）。 */
    public static List<String> describe(View v, int depth) {
        List<String> out = new ArrayList<String>();
        describe(v, depth, out, 0);
        return out;
    }

    private static void describe(View v, int depth, List<String> out, int d) {
        if (v == null || d > 10 || out.size() > 120) return;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d; i++) sb.append("  ");
        String name = entryName(v);
        sb.append(name != null ? name : v.getClass().getSimpleName())
          .append(" v=").append(v.getVisibility())
          .append(" t=").append(v.getTop())
          .append(" scr=").append(l[1]).append("..").append(l[1] + v.getHeight())
          .append(" h=").append(v.getHeight());
        out.add(sb.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) describe(g.getChildAt(i), depth, out, d + 1);
        }
    }
}
