package io.github.ldxm666.mapclean;

import io.github.ldxm666.bmapclean.App;
import io.github.ldxm666.bmapclean.Spec;
import io.github.libxposed.service.XposedService;

/**
 * 百度地图 side of the floating panel: the switch catalogue and the config plumbing.
 *
 * <h3>Why this file exists separately from {@link AmapPanel}</h3>
 * The orb renders one vendor's switches, never both. Keeping each vendor in its own file is
 * what lets the 免 root 内置 builds stay honest: {@code tools/build_variant.py} drops the other
 * vendor's package and replaces the matching panel class with a no-op stub, so the shipped
 * APK contains no code for a map it will never run inside.
 *
 * <h3>Catalogue source</h3>
 * The categories and rows come straight from {@link Spec#cats()}, which is the same catalogue
 * the standalone settings page renders and the same keys the hooks read. There is deliberately
 * no second copy of the switch list here.
 *
 * <h3>Reads and writes</h3>
 * The group is {@code bmapclean_config}. Writes go through {@link App}, which holds the
 * LSPosed service; in an integrated LSPatch build that service is the writable Binder the
 * patcher hands the host, and {@code mapclean.App.attachEmbedded} makes sure the 百度 holder
 * has it.
 */
final class BaiduPanel {

    private BaiduPanel() {}

    /** 百度's catalogue, mapped onto the panel's view model. */
    static PanelTypes.Group[] groups() {
        Spec.Cat[] cats = Spec.cats();
        // chip 顺序：悬浮窗 / 省电 / <百度分类…>（与高德一致）。
        //
        // ★ 「悬浮窗」组必须存在且**至少一行**：百度内置版把 MainActivity 裁掉了，没有 tab 入口，
        // 用户一旦关掉悬浮球就再也回不来 —— 上一轮就是因为「组里没有行 ⇒ 白页」而把整组删掉，
        // 结果变成功能残缺。现在这一组第一行就是开关，并写清「长按底部『我的』标签 1 秒」的
        // 找回路径（见 {@code mapclean/BaiduMineEntry}），所以不会再出现空组。
        PanelTypes.Group[] out = new PanelTypes.Group[cats.length + 2];
        out[0] = floatGroup();
        out[1] = powerGroup();
        for (int i = 0; i < cats.length; i++) {
            Spec.Cat cat = cats[i];
            PanelTypes.Item[] items = new PanelTypes.Item[cat.rows.length];
            for (int j = 0; j < cat.rows.length; j++) {
                Spec.Row row = cat.rows[j];
                items[j] = new PanelTypes.Item(row.key, row.title, row.note);
            }
            // The category's own subtitle ("改动后重启百度生效" and friends) is shown as the
            // group's note, the same slot the 高德 tool catalogue uses for its 5-cell warning.
            out[i + 2] = new PanelTypes.Group(cat.title, items, cat.subtitle);
        }
        return out;
    }

    /**
     * 「悬浮窗」组 —— 悬浮球开关 + **找回路径说明**。
     *
     * <p>⚠ 这一组**绝不能为空**（{@code Item[]} 长度为 0 会让整页变白，实测踩过）：
     * 即使以后精简，也必须至少保留「显示悬浮球」这一行。
     *
     * <p>为什么放第一组：用户关掉悬浮球后唯一的入口就是这里，而进入这里的方式是
     * 「长按底部『我的』标签约 1 秒」（{@link BaiduMineEntry}）。把开关和找回说明写在同一行，
     * 用户一眼就能看懂「关掉之后怎么回来」。
     */
    private static PanelTypes.Group floatGroup() {
        return new PanelTypes.Group("悬浮窗", new PanelTypes.Item[]{
                new PanelTypes.Item(FloatingConfig.K_FLOAT_ENABLED, "显示悬浮球",
                        "关掉后悬浮球和这个面板都会消失。要再打开：**长按底部「我的」标签约 1 秒**，"
                        + "面板会再次弹出，把这一项拨回开即可。")
        }, "只影响悬浮球本身，不动其它任何开关。");
    }

    /**
     * 「省电」组 —— 与高德 {@code AmapPanel.powerSections()} 对齐。
     *
     * <p>⚠ **改完必须强制停止并重开百度地图才生效**：这些开关都是**安装期门控**
     * （{@code Cfg.installGate}），关闭时对应实现根本不装进方法链 —— 这正是用户要的
     * 「关闭 = 不加载、不消耗性能」，代价就是需要重启宿主。
     */
    private static PanelTypes.Group powerGroup() {
        return new PanelTypes.Group("省电", new PanelTypes.Item[]{
                new PanelTypes.Item(io.github.ldxm666.bmapclean.Cfg.K_PUSH_HARD_OFF, "关闭推送长连接（硬档）",
                        "省电最明显。代价：百度推送消息（活动/提醒）收不到。"),
                new PanelTypes.Item(io.github.ldxm666.bmapclean.Cfg.K_PUSH_OFF, "关闭推送消息处理",
                        "不建连接、不处理推送消息，但不杀服务。"),
                new PanelTypes.Item(io.github.ldxm666.bmapclean.Cfg.K_PUSH_KILL_OFF, "关闭推送服务（激进档）",
                        "通道服务起来即退出。若发现耗电反而上升，先关这一项。"),
                new PanelTypes.Item(io.github.ldxm666.bmapclean.Cfg.K_BG_SERVICE_OFF, "关闭后台常驻服务",
                        "定位/云控/统计等自启动服务不再常驻。"),
                new PanelTypes.Item(io.github.ldxm666.bmapclean.Cfg.K_TIMER_OFF, "关闭后台周期任务",
                        "AlarmManager / JobScheduler / WorkManager 的定时唤醒不再重排。"),
                new PanelTypes.Item(io.github.ldxm666.bmapclean.Cfg.K_WIDGET_OFF, "关闭桌面小组件刷新",
                        "只拦 APPWIDGET_UPDATE，添加/删除组件仍正常。"),
                new PanelTypes.Item(io.github.ldxm666.bmapclean.Cfg.K_TRACK_OFF, "关闭第三方埋点",
                        "统计 SDK 不初始化，不上报。"),
        }, "改动后需**强制停止并重开百度地图**才生效（关闭时彻底不加载对应实现，零性能开销）。",
                new PanelTypes.Action() {
                    @Override public String label() { return "一键省电（全部开启拦截）"; }
                    @Override public int run() {
                        try { return io.github.ldxm666.bmapclean.Cfg.enableAllPower(); }
                        catch (Throwable t) { return 0; }
                    }
                });
    }

    /** True when a writable path is available inside the host process. */
    static boolean writable() {
        try {
            return App.svc() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean read(String key) {
        try {
            // 百度's catalogue carries its own defaults (a few rows default to off), and the
            // hook side uses the same table -- so the panel and the hooks cannot disagree.
            return App.read(key, Spec.defaultVisible(key));
        } catch (Throwable t) {
            return Spec.defaultVisible(key);
        }
    }

    /**
     * The 地图标注 switch, read the way 百度's hooks read it.
     *
     * <p>百度's own default for this key is off (see {@code Spec.defaultVisible}), and the
     * native side needs the same answer the hooks use or the panel would lie.
     */
    static boolean mapPoiVisible() {
        try {
            return io.github.ldxm666.bmapclean.Cfg.visible(
                    io.github.ldxm666.bmapclean.Spec.K_MAP_POI,
                    io.github.ldxm666.bmapclean.Spec.defaultVisible(
                            io.github.ldxm666.bmapclean.Spec.K_MAP_POI));
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean write(String key, boolean value) {
        try {
            return App.write(key, value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void invalidate() {
        // 百度's hook side re-reads the group on its own 2 s TTL (see bmapclean.Cfg), so there
        // is no snapshot to drop here.
    }

    /**
     * Nothing to push: 百度 applies its switches when the affected view is built, and the
     * map-label switch explicitly needs the app restarted (its row says so).
     */
    static void apply(String key) { }
}
