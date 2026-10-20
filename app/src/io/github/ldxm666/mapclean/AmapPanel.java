package io.github.ldxm666.mapclean;

import android.util.Log;

import io.github.ldxm666.mapadkiller.Config;
import io.github.libxposed.service.XposedService;

/**
 * 高德地图 side of the floating panel: the switch catalogue and the config plumbing.
 *
 * <p>Moved out of {@link FloatingPanel} / {@link FloatingConfig} so that each vendor's panel
 * lives in exactly one file. {@code tools/build_variant.py} drops this file and substitutes a
 * no-op stub when it builds the 百度 免 root variant (and vice versa for {@link BaiduPanel}),
 * which is what keeps a shipped APK free of the map it will never run inside.
 *
 * <p>Everything here is 高德-specific: the keys are 高德's, the group is
 * {@code amap_enhancer_config}, and {@link #apply} pushes a fresh write into the running map.
 */
final class AmapPanel {

    /** Write-path diagnostics: a switch that refuses to flip must say why, in one line. */
    private static final String TAG_WRITE = "MapCleanCfg";

    /** Shown above the tool catalogue; see the 5-cell explanation below. */
    private static final String TOOL_NOTE =
            "首页工具栏为 5 格：优先保留原生顺序，空位按「已开启」自动补位；超出的工具仍在「更多工具」页里。";

    private AmapPanel() {}

    static FloatingPanel.Group[] groups() {
        return new FloatingPanel.Group[]{
            new FloatingPanel.Group("首页", new FloatingPanel.Item[]{
                new FloatingPanel.Item(Config.K_TOOLS_VISIBLE, "首页工具宫格",
                        "关闭后首页不再显示驾车 / 公交等工具入口。"),
                new FloatingPanel.Item(Config.K_TOOLS_ALLOWLIST, "仅显示已开启的工具",
                        "开启后未设置和新增的推广工具一律不进入首页。"),
                // 「更多工具」 is the app's own fixed cell, so it is not part of the tool
                // catalogue's groups; keeping it here gives it the one obviously-placed
                // switch it never had (it used to be buried at the end of 工具 → 其他).
                new FloatingPanel.Item(moreToolsKey(), "更多工具入口",
                        "首页那格「更多工具」。关掉后工具栏只剩你开启的工具。"),
                new FloatingPanel.Item(Config.K_HOME_CHIPS, "设置家 / 设置单位 / 常去地点",
                        "控制首页那排回家与公司的快捷入口；改动立即生效。"),
                new FloatingPanel.Item(Config.K_HOTWORD_OFF, "关闭搜索框热词轮播",
                        "把搜索框文字钉死为默认提示词，去掉运营广告词。"),
            }, null, POWER_CHIP),
            // 「省电」放在 chip 行**第 2 位**：第 1 位留给默认选中的「首页」，第 2 位保证任何
            // 屏宽都一眼可见。用户两次反馈「模块里没有省电开关入口」—— 藏在第 N 个 chip 里、
            // 或者需要横向滚动才看得到，就等于没有。
            new FloatingPanel.Group(POWER_CHIP, powerSections(), POWER_NOTE,
                    new FloatingPanel.Action() {
                        @Override public String label() { return "一键省电（全部开启拦截）"; }
                        @Override public int run() { return powerAllOn(); }
                    }),
            new FloatingPanel.Group("工具", toolSections(), TOOL_NOTE),
            new FloatingPanel.Group("内容", new FloatingPanel.Item[]{
                new FloatingPanel.Item(Config.K_FEED_WEATHER, "天气卡片"),
                new FloatingPanel.Item(Config.K_FEED_BOARD, "扫街榜 / 周边精选榜单"),
                new FloatingPanel.Item(Config.K_FEED_CONTENT, "攻略 / 内容流卡片"),
                new FloatingPanel.Item(Config.K_FEED_FESTIVAL, "出行节 / 促销活动接口"),
                new FloatingPanel.Item(Config.K_RIDE_CARD_OFF, "关闭「去XXX」打车浮窗",
                        "拦截首页打车运营卡。"),
            }),
            new FloatingPanel.Group("地图", new FloatingPanel.Item[]{
                // 真正的实现在原生层：libmapclean_labels.so 改写 map_assets/style_*X_Main
                // 样式 protobuf，只把兴趣点要素置不可见，道路 / 水域 / 地名全部保留。
                new FloatingPanel.Item(Config.K_MAP_POI, "显示地图兴趣点图标和名称",
                        "统一控制酒店、景点、门店等兴趣点的图标和名称；道路、街道、城市与地名保留。"),
                new FloatingPanel.Item(Config.K_MAP_PROMO, "地图右侧运营挂件",
                        "扫街榜和活动入口。"),
                new FloatingPanel.Item(Config.K_PLACE_RECOMMEND, "地点详情「发现好去处」",
                        "长按选点详情里的照片推荐流。"),
            }),
            new FloatingPanel.Group("我的", new FloatingPanel.Item[]{
                new FloatingPanel.Item(Config.K_MY_ORDER_ROW, "订单 / 收藏 / 待评价"),
                new FloatingPanel.Item(Config.K_MY_SERVICE_ROW, "车辆服务 / 高德运动"),
                new FloatingPanel.Item(Config.K_MY_TASK, "达人任务卡片"),
                new FloatingPanel.Item(Config.K_MY_PROMO_ROW, "热门活动卡"),
                new FloatingPanel.Item(Config.K_MY_GUESS, "猜你喜欢"),
                new FloatingPanel.Item(Config.K_MY_FRIENDS, "好友动态"),
            }),
            // 省电 —— 已前移到 chip 行第 2 位（见文件顶部 POWER_CHIP），这里不再重复注册。
            new FloatingPanel.Group("底栏", new FloatingPanel.Item[]{
                new FloatingPanel.Item(Config.K_TAB_PREFIX + "探索", "显示标签「探索」"),
                new FloatingPanel.Item(Config.K_TAB_PREFIX + "长按说话", "显示标签「长按说话」"),
                new FloatingPanel.Item(Config.K_TAB_PREFIX + "打车", "显示标签「打车」"),
                new FloatingPanel.Item(Config.K_TAB_PREFIX + "我的", "显示标签「我的」"),
            }),
            new FloatingPanel.Group("其他", new FloatingPanel.Item[]{
                // The 悬浮窗 master switch. Listed here as well as on the 我的 page's 4th icon
                // so the sheet itself can turn the orb off; the icon is the way back, because
                // in the 免 root build the sheet is unreachable once the orb is gone.
                new FloatingPanel.Item(FloatingConfig.K_FLOAT_ENABLED, "悬浮窗入口（悬浮球）",
                        "关掉后悬浮球和这个面板都不再出现。需要重新打开时：长按底部「我的」标签约 1 秒，面板会再次弹出，把这一项拨回开即可。"),
                new FloatingPanel.Item(Config.K_DEBUG_LOG, "调试日志",
                        "把协议适配错误输出到 logcat，排障用。"),
            // `true` = 这一页底部渲染「当前版本 / 检查更新」两行。免 root 内置版没有
            // MainActivity，悬浮面板是它唯一的设置界面，所以版本与更新入口必须在这里。
            }, null, true),
        };
    }

    /**
     * 省电页的 chip 文字。
     *
     * <p>两个字是刻意的：chip 行是横向滚动的，组名越长，它越容易被裁在屏幕边缘 —— 用户已经
     * 两次因为「看不到入口」而报障。完整名字「省电 / 后台」写在这一页的说明第一句里。
     */
    private static final String POWER_CHIP = "省电";

    /**
     * 省电页的说明：把最容易误读的语义放在最上面，末尾再加安装期门控的生效条件。
     *
     * <p>这一页的开关「打开 = 该行为已被拦截」而不是「打开 = 启用功能」，和别的页正好相反，
     * 不写清楚用户一定会以为自己关掉了省电。
     *
     * <p>末尾那句是必需的：这批拦截已改成**安装期门控**（开关为关时根本不装 hook），
     * 所以改完必须强停并重开高德才会重新走安装流程 —— 否则用户会以为开关没生效。
     * 每行不再重复这句，避免 11 行都在说同一件事。
     */
    private static final String POWER_NOTE =
            "省电 / 后台。这一页的开关：开 = 该行为已被拦截（默认全开）；关 = 恢复高德原生行为。"
            + "关掉推送长连接后，行程提醒 / 消息 / 家人地图会恢复，但 ACCS 长连接会重新占用 CPU。"
            + "⚠ 本页开关改动后需强停并重开高德才生效（关闭时会彻底不加载对应实现，零性能开销）。";

    /** Keys written by 「一键省电」: every gate that only removes background work. */
    private static final String[] POWER_ALL_KEYS = {
            Config.K_PUSH_HARD_OFF,
            Config.K_PUSH_OFF,
            Config.K_PUSH_KILL_OFF,
            Config.K_SUBPROC_JOB_OFF,
            Config.K_SUBPROC_WIDGET_OFF,
            Config.K_SUBPROC_LOTUSPOOL_OFF,
            Config.K_TRACK_OFF,
            Config.K_ALIMAMA_SPLASH_OFF,
            Config.K_ALIMAMA_LOG_OFF,
    };

    /**
     * 省电 / 后台 的四段开关。
     *
     * <p>只在高德侧注册：{@code push_*} / {@code subproc_*} / {@code track_off} /
     * {@code alimama_*} 的实现（{@code AmapJob}/{@code AmapTrack}/{@code AmapAlimama}/
     * {@code AmapSubprocess}）只存在于高德钩子里，放进百度/腾讯面板会变成点了没反应的空开关。
     *
     * <p>{@code labels_on} 是唯一反义的一项：{@code true} = 启用第三方标注过滤，且它的消费方
     * （{@code AmapPoiLayer.install}）用的是 {@code Config.selected()} 严格读 ⇒ 默认 false，
     * 所以它带 {@code strict} 标记，否则开关会显示成「开」而过滤其实是关的。
     */
    static Object[][] powerSections() {
        return new Object[][]{
            {"推送", new FloatingPanel.Item[]{
                new FloatingPanel.Item(Config.K_PUSH_HARD_OFF,
                        "关闭推送长连接（ACCS，省电最多）",
                        "关掉后高德推送（行程提醒 / 活动 / 消息 / 家人地图）会恢复，"
                        + "但 ACCS 长连接会重新占用 CPU。"),
                new FloatingPanel.Item(Config.K_PUSH_OFF,
                        "关闭推送消息处理",
                        "不建长连接、不处理推送消息，但保留服务壳；比上面那条温和。"),
                new FloatingPanel.Item(Config.K_PUSH_KILL_OFF,
                        "关闭推送服务壳（激进）",
                        "推送服务一创建就 stopSelf。可能影响推送相关功能，排查用。"),
            }},
            {"后台任务", new FloatingPanel.Item[]{
                new FloatingPanel.Item(Config.K_SUBPROC_JOB_OFF,
                        "关闭周期任务（WorkManager / JobScheduler）",
                        "关掉后高德的周期后台任务会恢复（更耗电）。"),
                new FloatingPanel.Item(Config.K_SUBPROC_OFF,
                        "关闭子进程拦截（总闸，排查用）",
                        "一次性放行所有子进程拦截；怀疑某功能被误伤时先关这个。"),
                new FloatingPanel.Item(Config.K_SUBPROC_WIDGET_OFF,
                        "关闭桌面小组件刷新",
                        "关掉后桌面小组件会恢复刷新（更耗电）。"),
                new FloatingPanel.Item(Config.K_SUBPROC_LOTUSPOOL_OFF,
                        "关闭云控调度池（LotusPool）",
                        "关掉后高德云控下发的周期任务会恢复。"),
            }},
            {"埋点与广告", new FloatingPanel.Item[]{
                new FloatingPanel.Item(Config.K_TRACK_OFF,
                        "关闭阿里埋点 Analytics",
                        "关掉后阿里 UT 埋点会恢复上报。真机出现无法归因的异常时，第一个关它。"),
                new FloatingPanel.Item(Config.K_ALIMAMA_SPLASH_OFF,
                        "关闭开屏广告请求",
                        "关掉后开屏广告会恢复请求（有广告、也耗流量）。"),
                new FloatingPanel.Item(Config.K_ALIMAMA_LOG_OFF,
                        "关闭广告曝光上报",
                        "只关上报，不影响广告本身是否展示。"),
            }},
            {"地图", new FloatingPanel.Item[]{
                new FloatingPanel.Item(Config.K_LABELS_ON,
                        "启用第三方标注过滤",
                        "默认关闭；开启会恢复第三方标注过滤，但可能影响地图帧率。",
                        true),
            }},
        };
    }

    /**
     * 「一键省电」：把 9 个省电键一次性写成 true。
     *
     * <p>一次 {@code edit()/commit()} 而不是 9 次 {@link #write}：后者每个键都会跑一遍
     * {@link #apply}（{@code AmapNativeHome.onConfigChanged} + {@code NativeLabelAssets.refresh}），
     * 而这两个动作对这 9 个键毫无意义 —— 它们全是 hook 侧的闸门，不改视图树。
     *
     * @return 写入成功的键数；0 表示没有任何可写路径（服务未连接且 prefs 不可用）。
     */
    static int powerAllOn() {
        XposedService service = null;
        try { service = io.github.ldxm666.mapadkiller.App.svc(); } catch (Throwable ignored) {}
        if (service != null) {
            try {
                android.content.SharedPreferences.Editor editor =
                        service.getRemotePreferences(Config.PREF_GROUP).edit();
                for (String key : POWER_ALL_KEYS) editor.putBoolean(key, true);
                if (editor.commit()) {
                    markWrite(true, null);
                    invalidate();
                    return POWER_ALL_KEYS.length;
                }
            } catch (Throwable ignored) {}
        }
        try {
            android.content.SharedPreferences.Editor editor = Config.edit();
            if (editor != null) {
                for (String key : POWER_ALL_KEYS) editor.putBoolean(key, true);
                if (editor.commit()) {
                    markWrite(true, null);
                    invalidate();
                    return POWER_ALL_KEYS.length;
                }
            }
        } catch (Throwable ignored) {}
        markWrite(false, R_COMMIT_FALSE);
        return 0;
    }

    /**
     * Every entry of 高德's "More Tools" catalogue, grouped the way the app groups it.
     *
     * <p>Keys come from {@link io.github.ldxm666.mapadkiller.AmapToolCatalog#key}, so a switch
     * here writes exactly the key the hook reads -- there is no parallel key scheme to drift
     * out of sync with the standalone settings page.
     *
     * <p>Within a section the enabled switches are listed first: the catalogue runs past a
     * hundred entries, and the handful that are actually on are what the user comes back to
     * adjust. The split is stable, so a row does not jump around while the sheet is open.
     */
    static Object[][] toolSections() {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        for (String group : io.github.ldxm666.mapadkiller.AmapToolCatalog.GROUPS) {
            java.util.List<FloatingPanel.Item> on = new java.util.ArrayList<FloatingPanel.Item>();
            java.util.List<FloatingPanel.Item> off = new java.util.ArrayList<FloatingPanel.Item>();
            for (String[] entry : io.github.ldxm666.mapadkiller.AmapToolCatalog.ENTRIES) {
                if (!group.equals(entry[2])) continue;
                // 「更多工具」 lives in the 首页 group instead: it is the app's fixed cell, not
                // one of the catalogue's promotional tools, and listing it here as well would
                // give the same key two switches.
                if (io.github.ldxm666.mapadkiller.AmapData.MORE_TOOLS.equals(entry[0])) continue;
                String key = io.github.ldxm666.mapadkiller.AmapToolCatalog.key(entry[0]);
                if (key == null) continue;
                FloatingPanel.Item item = new FloatingPanel.Item(key, entry[1], "工具 ID " + entry[0]);
                (Config.visible(key) ? on : off).add(item);
            }
            if (on.isEmpty() && off.isEmpty()) continue;
            on.addAll(off);
            out.add(new Object[]{group, on.toArray(new FloatingPanel.Item[0])});
        }
        return out.toArray(new Object[0][]);
    }

    /** The key of the app's fixed 「更多工具」 cell, as the hook side spells it. */
    static String moreToolsKey() {
        String key = io.github.ldxm666.mapadkiller.AmapToolCatalog.key(
                io.github.ldxm666.mapadkiller.AmapData.MORE_TOOLS);
        return key == null ? Config.K_TOOL_PREFIX + "更多工具" : key;
    }

    /**
     * Which write path is currently usable.
     *
     * <p>2 = the framework service ({@code WRITE_SVC}); 1 = a local in-process backend
     * ({@code WRITE_PREFS}); 0 = neither.
     */
    static int writePath() {
        switch (effectiveBackend()) {
            case BACKEND_REMOTE: return 2;
            case BACKEND_LOCAL: return 1;
            default: return 0;
        }
    }

    /* ------------------------------------------------ 配置后端（对齐 amap-core2） */

    /** No backend at all: nothing can be read or written (mirrors {@code Config.BACKEND_NONE}). */
    static final int BACKEND_NONE = Config.BACKEND_NONE;
    /** Local SharedPreferences backend: works in-process, no framework channel needed. */
    static final int BACKEND_LOCAL = Config.BACKEND_LOCAL;
    /** Framework remote-preferences backend (the service is bound). */
    static final int BACKEND_REMOTE = Config.BACKEND_REMOTE;

    /**
     * {@code Config.backend()} -- {@code amap-core2} 的最终 API（L288）。
     *
     * <p>早前这里用反射，是因为那个接口还没落地、名字也没定；现在它是正式接口，直接调。
     */
    private static int configBackend() {
        try {
            return Config.backend();
        } catch (Throwable t) {
            return BACKEND_NONE;
        }
    }

    /** -1 = not probed, 0 = refused, 1 = the probe write landed. One probe per process. */
    private static volatile int probeState = -1;

    /** Harmless key used by the probe; written and removed immediately. */
    private static final String K_PROBE = "_probe";

    /**
     * Prove writability with one real write instead of trusting a static verdict.
     *
     * <p>Tries both channels (service, then in-process preferences), removes the key again, and
     * logs exactly one line: {@code event=cfg_probe ok|fail backend=… reason=…}. This is what
     * keeps the pill honest when {@code Config.backend()} answers NONE on a device where a
     * write would actually succeed.
     */
    static boolean probeWritable() {
        int known = probeState;
        if (known >= 0) return known == 1;
        boolean ok = false;
        String reason = "no_channel";
        XposedService service = null;
        try { service = io.github.ldxm666.mapadkiller.App.svc(); } catch (Throwable ignored) {}
        if (service != null) {
            try {
                android.content.SharedPreferences.Editor e =
                        service.getRemotePreferences(Config.PREF_GROUP).edit();
                e.putLong(K_PROBE, System.currentTimeMillis());
                if (e.commit()) {
                    ok = true;
                    try {
                        service.getRemotePreferences(Config.PREF_GROUP)
                                .edit().remove(K_PROBE).commit();
                    } catch (Throwable ignored) {}
                } else {
                    reason = "svc_commit_false";
                }
            } catch (Throwable t) {
                reason = "svc_" + t.getClass().getSimpleName();
            }
        } else {
            reason = "svc_null";
        }
        if (!ok) {
            try {
                // Config.edit() is null when there is no backend at all -- that is the NONE
                // case, not an error, so it must not throw.
                android.content.SharedPreferences.Editor e = Config.edit();
                if (e == null) {
                    reason = "no_backend";
                } else {
                    e.putLong(K_PROBE, System.currentTimeMillis());
                    if (e.commit()) {
                        ok = true;
                        try {
                            android.content.SharedPreferences.Editor r = Config.edit();
                            if (r != null) r.remove(K_PROBE).commit();   // 不留脏键
                        } catch (Throwable ignored) {}
                    } else {
                        reason = "prefs_commit_false";
                    }
                }
            } catch (Throwable t) {
                reason = "prefs_" + t.getClass().getSimpleName();
            }
        }
        probeState = ok ? 1 : 0;
        int declared = configBackend();
        Log.i(TAG_WRITE, "event=cfg_probe " + (ok ? "ok" : "fail")
                + " backend=" + (declared < 0 ? "unknown" : String.valueOf(declared))
                + (ok ? "" : " reason=" + reason));
        return ok;
    }

    /**
     * The backend to report, after verification.
     *
     * <p>NONE is never taken at face value: it is checked with one real write, and a successful
     * write upgrades the answer to LOCAL. A stale NONE is exactly what told users 「只读」 while
     * the sheet was sitting inside the target app.
     */
    static int effectiveBackend() {
        int declared = configBackend();
        if (declared == BACKEND_NONE) return probeWritable() ? BACKEND_LOCAL : BACKEND_NONE;
        return declared;
    }

    /* ------------------------------------------------ 最近一次写入结果（药丸着色用） */

    /** Nothing has been written yet in this process. */
    static final int W_UNTRIED = 0;
    /** The last write landed. */
    static final int W_OK = 1;
    /** The last write was refused (permissions / IO / read-only proxy). */
    static final int W_REFUSED = 2;

    private static volatile int lastWrite = W_UNTRIED;
    private static volatile String lastWriteReason;

    /** Reason codes, kept short so they fit a log line and a toast. */
    static final String R_SVC_NULL = "svc_null";
    static final String R_PREFS_NULL = "prefs_null";
    static final String R_COMMIT_FALSE = "commit_false";
    static final String R_IO = "io";
    /** No backend at all ({@code Config.BACKEND_NONE}): nothing to write through. */
    static final String R_NO_BACKEND = "no_backend";

    private static void markWrite(boolean ok, String reason) {
        lastWrite = ok ? W_OK : W_REFUSED;
        lastWriteReason = ok ? null : reason;
        // A successful write proves a backend exists -- upgrade a stale "no channel" verdict
        // so the pill cannot keep saying 只读 after the channel came back.
        if (ok) probeState = 1;
    }

    /** {@link #W_UNTRIED} / {@link #W_OK} / {@link #W_REFUSED}. */
    static int lastWriteState() {
        return lastWrite;
    }

    /** {@link #R_SVC_NULL} / {@link #R_PREFS_NULL} / {@link #R_COMMIT_FALSE} / {@link #R_IO}. */
    static String lastWriteReason() {
        return lastWriteReason;
    }

    /**
     * Kept as a thin alias for older call sites: the real check is now
     * {@link #probeWritable()}, which also tries the service channel and uses the {@code _probe}
     * key the lead specified. Two probes writing two different keys was just clutter.
     */
    static boolean prefsWritable() {
        return probeWritable();
    }

    /** True when a writable path is currently available. */
    static boolean writable() {
        return writePath() > 0;
    }

    /** {@code ok} / {@code null}, for the write-failure logs. */
    private static String svcState(XposedService service) {
        return service != null ? "ok" : "null";
    }

    /** {@code ok} / {@code null} for the module instance the prefs fallback needs. */
    private static String moduleState() {
        try {
            return io.github.ldxm666.mapadkiller.H.module == null ? "null" : "ok";
        } catch (Throwable t) {
            return "null";
        }
    }

    static boolean read(String key) {
        return Config.visible(key);
    }

    /**
     * The 地图兴趣点 switch, read the way the hooks read it.
     *
     * <p>Used by {@link NativeLabelAssets} to decide the mask it hands the native filter. The
     * key differs per vendor, which is why this is a per-panel method rather than a constant.
     */
    static boolean mapPoiVisible() {
        return Config.visible(Config.K_MAP_POI);
    }

    /**
     * Persist one switch. Uses {@code commit()} rather than {@code apply()}: the user may
     * leave the map immediately after flipping a switch, and an async write would be lost.
     *
     * <p>Two paths, in this order, and <b>both are tried</b>:
     * <ol>
     *   <li>the framework service ({@code App.svc()}) -- writable in every build;</li>
     *   <li>{@code Config.put(...)} -- the local backend {@code amap-core2} added, which needs no
     *       framework channel at all and is therefore the path that works when the remote
     *       preferences never arrive (returns false when {@code Config.BACKEND_NONE}).</li>
     * </ol>
     * Every branch logs unconditionally: a switch that refuses to flip is a user-visible
     * failure, and 「服务未连接」 alone cannot say which of the two paths died.
     */
    static boolean write(String key, boolean value) {
        XposedService service = null;
        try { service = io.github.ldxm666.mapadkiller.App.svc(); } catch (Throwable ignored) {}
        if (service != null) {
            try {
                if (service.getRemotePreferences(Config.PREF_GROUP)
                        .edit().putBoolean(key, value).commit()) {
                    markWrite(true, null);
                    Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=svc");
                    apply(key);
                    return true;
                }
                Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=ok module="
                        + moduleState() + " stage=svc reason=" + R_COMMIT_FALSE);
            } catch (Throwable t) {
                Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=ok module="
                        + moduleState() + " stage=svc reason=" + R_IO
                        + " err=" + t.getClass().getSimpleName());
            }
        } else {
            Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=null module="
                    + moduleState() + " stage=svc reason=" + R_SVC_NULL);
        }
        try {
            if (Config.put(key, value)) {
                markWrite(true, null);
                Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=prefs");
                apply(key);
                return true;
            }
            String reason = configBackend() == BACKEND_NONE ? R_NO_BACKEND : R_COMMIT_FALSE;
            markWrite(false, reason);
            Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=" + svcState(service)
                    + " module=" + moduleState() + " stage=prefs reason=" + reason);
        } catch (Throwable t) {
            String reason = (t instanceof NullPointerException) ? R_PREFS_NULL : R_IO;
            markWrite(false, reason);
            Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=" + svcState(service)
                    + " module=" + moduleState() + " stage=prefs reason=" + reason
                    + " err=" + t.getClass().getSimpleName());
        }
        return false;
    }

    /**
     * Read one switch the way its <b>consumer</b> reads it when "on" means the literal stored
     * value rather than "the behaviour is suppressed".
     *
     * <p>Only {@code labels_on} uses this today: {@code AmapPoiLayer.install} gates on
     * {@code Config.selected(K_LABELS_ON)} (strict {@code Boolean.TRUE}), so its default is
     * false. Hydrating that row through {@link #read(String)} would answer true (the
     * {@code defaultVisible} fallback) and the switch would lie about the filter being on.
     */
    static boolean readStrict(String key) {
        try {
            return Config.selected(key);
        } catch (Throwable t) {
            return false;
        }
    }

    static void invalidate() {
        try { Config.invalidate(); } catch (Throwable ignored) {}
    }

    /**
     * Read one <b>int</b>-valued key (the orb's appearance settings).
     *
     * <p>Separate from {@link #read(String)} because that one is the switch channel: it asks
     * {@code Config.visible()}, which resolves a missing key through {@code defaultVisible}
     * and returns a boolean. A slider value has no business in that table.
     *
     * <p>Reads the live {@link Config#prefs()} rather than the 2 s snapshot, so a value the
     * user just released is visible to the very next call. Any failure (module instance not
     * ready, group missing) returns {@code fallback} -- the caller is the orb constructor and
     * must never be handed an exception.
     */
    static int readInt(String key, int fallback) {
        try {
            // Config.getInt 自己处理"后端不可用"（返回 fallback，不抛），比直接摸 prefs 稳。
            return Config.getInt(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Persist one int-valued key, same write order as {@link #write(String, boolean)}:
     * the embedded service first (writable in the integrated build), then the hook-side
     * preferences.
     *
     * <p>Deliberately does <b>not</b> call {@link #apply(String)}: no hook reads these keys,
     * so {@code AmapNativeHome.onConfigChanged} + {@code NativeLabelAssets.refresh} would be
     * pure waste on every slider release.
     */
    static boolean writeInt(String key, int value) {
        XposedService service = null;
        try { service = io.github.ldxm666.mapadkiller.App.svc(); } catch (Throwable ignored) {}
        if (service != null) {
            try {
                if (service.getRemotePreferences(Config.PREF_GROUP)
                        .edit().putInt(key, value).commit()) {
                    markWrite(true, null);
                    Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=svc");
                    invalidate();
                    return true;
                }
                Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=ok module="
                        + moduleState() + " stage=svc reason=" + R_COMMIT_FALSE);
            } catch (Throwable t) {
                Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=ok module="
                        + moduleState() + " stage=svc reason=" + R_IO
                        + " err=" + t.getClass().getSimpleName());
            }
        } else {
            Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=null module="
                    + moduleState() + " stage=svc reason=" + R_SVC_NULL);
        }
        try {
            if (Config.put(key, value)) {
                markWrite(true, null);
                Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=prefs");
                invalidate();
                return true;
            }
            String reason = configBackend() == BACKEND_NONE ? R_NO_BACKEND : R_COMMIT_FALSE;
            markWrite(false, reason);
            Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=" + svcState(service)
                    + " module=" + moduleState() + " stage=prefs reason=" + reason);
        } catch (Throwable t) {
            String reason = (t instanceof NullPointerException) ? R_PREFS_NULL : R_IO;
            markWrite(false, reason);
            Log.w(TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=" + svcState(service)
                    + " module=" + moduleState() + " stage=prefs reason=" + reason
                    + " err=" + t.getClass().getSimpleName());
        }
        return false;
    }

    /**
     * Push a freshly written switch into the running map.
     *
     * <p>The panel and the hooks share one process in the integrated build, so a toggle can be
     * applied to the view tree and to the native filter immediately. Without this the change
     * only became visible the next time the home page happened to be rebuilt -- which is what
     * made 「设置家」 and the tool switches look dead even though the write had landed.
     * Every step is best-effort: a failure here must never make the write look unsuccessful.
     */
    static void apply(String key) {
        invalidate();
        try {
            io.github.ldxm666.mapadkiller.AmapNativeHome.onConfigChanged(key);
        } catch (Throwable ignored) { }
        try {
            NativeLabelAssets.refresh();
        } catch (Throwable ignored) { }
    }
}
