package io.github.ldxm666.mapclean;

import io.github.ldxm666.mapadkiller.App;
import io.github.ldxm666.mapadkiller.Config;
import io.github.ldxm666.mapadkiller.LearnedProvider;
import io.github.ldxm666.mapadkiller.MainHook;
import io.github.ldxm666.mapadkiller.SdkAutoBlock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Window;
import android.widget.EditText;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

/**
 * 设置页 — Liquid Glass 风格（设计语言参考 Kyant0/AndroidLiquidGlass）。
 *
 * 视觉体系（纯框架 API 复刻，无 AndroidX / Compose 依赖）：
 *  · 柔和径向光斑（blob）铺底，营造折射底色
 *  · 半透明玻璃卡片：渐变高光描边（LayerDrawable 双层圆角）模拟玻璃厚度
 *  · 分组抽屉：默认折叠、点击展开（LayoutTransition 平滑重排 + 淡入位移）
 *  · 底部悬浮玻璃操作栏（胶囊按钮），避开导航栏
 *  · 胶囊开关（自绘 thumb/track）、行级涟漪、深色模式全适配
 *
 * 配置写入 LSPosed RemotePreferences（App 侧经 libxposed/service，
 * Hook 侧经 XposedModule#getRemotePreferences，同名 group 双侧共享）。
 */
public final class MainActivity extends Activity {

    private TextView statusView;
    /** 版本行（绿/黄/灰）与最佳适配行 */
    private TextView verLine;
    /** 醒目的更新入口：默认「检查版本更新」，有新版时变成「立即更新 vX」 */
    private TextView updateBtn;
    private TextView adLine;
    private boolean updateDialogShown;
    /** 「已捕获广告 SDK」那一行；服务绑定后要重刷文案，否则一直显示 onCreate 时的空快照 */
    private TextView sdkRow;

    private ScrollView scroll;
    private LinearLayout list;          // 抽屉分组挂载点（LayoutTransition 目标）
    private FrameLayout page;
    private LinearLayout bottomBar;
    private FrameLayout.LayoutParams barLp;

    // ── v2.2.0 版式骨架：vertical root = hero(含 tabs) + pager(weight) + 浮层底栏 ──
    /** 活动内容根：FrameLayout 遮罩层，承载页面列 + 悬浮底栏 */
    private FrameLayout overlay;
    /** 页面列（垂直）：hero + pager */
    private LinearLayout column;
    private LinearLayout hero;
    /** 顶部右侧的框架状态药丸（已连接 / 作用域） */
    private LinearLayout navPill;
    /** hero 里的实时指标行容器 */
    private LinearLayout metricRow;

    // ── v2.0.0 分页：百度 / 高德 / 其他，左右滑动切换 ──────────────────────
    private Pager pager;
    private LinearLayout tabBar;
    private final java.util.List<TextView> tabChips = new java.util.ArrayList<>();
    private final java.util.List<android.widget.ScrollView> pages = new java.util.ArrayList<>();
    /** 百度那一页的开关（配置 group 与高德不同，要单独同步） */
    private final Map<String, GlassSwitch> bmapSwitches = new LinkedHashMap<>();
    /** 腾讯那一页的开关（第三个 group：tmapclean_config） */
    private final Map<String, GlassSwitch> tmapSwitches = new LinkedHashMap<>();

    /**
     * 设置页搜索：跨「百度 / 高德 / 腾讯 / 其他」四页过滤所有开关。
     *
     * <p>为什么值得单独一条：腾讯的工具目录在真机上有 90 多项（内置表 + 服务端下发合并），
     * 高德的「更多工具」有 100 多项。没有搜索时用户得在一个折叠抽屉里翻很久，
     * 而这恰恰是他唯一想用的功能。悬浮面板里早有同款（FloatingPanel.buildSearch），
     * 设置页一直缺，这一条补上。
     */
    private SwitchFinder finder;

    /** 展开状态快照：搜索时全部展开，退出搜索时按这个还原。 */
    private final Map<Sec, Boolean> expandSnapshot = new LinkedHashMap<>();

    // ── 搜索：页下标必须与 onCreate 里 pager.addView / addTab 的顺序一致 ──────────
    private static final int PAGE_BAIDU = 0, PAGE_AMAP = 1, PAGE_TMAP = 2, PAGE_OTHER = 3;

    /**
     * 把一个开关报给搜索索引。
     *
     * <p>调用点在开关工厂里而不是页面构建循环里：那样才能同时拿到“界面显示的标题”
     * 和“配置键”，而不必让调用方再传一遍。
     */
    private static void index(SwitchFinder f, String key, String title, String note, int page) {
        if (f == null || key == null) return;
        try { f.index(key, title, note, page); } catch (Throwable ignored) { }
    }

    private static final int GLASS_R = 22;   // 卡片圆角 dp
    private static final int HEAD_R = 18;    // 抽屉头/按钮圆角 dp

    private boolean dark;
    private String embeddedHost;

    private boolean embedded() { return embeddedHost != null; }
    private boolean embeddedAmap() { return EmbeddedSettingsPolicy.AMAP.equals(embeddedHost); }
    private String settingsTargetLabel() {
        return embedded() ? (embeddedAmap() ? "高德" : "百度") : "高德和百度";
    }
    private android.content.SharedPreferences uiPrefs() {
        return getSharedPreferences(embedded() ? EmbeddedSettingsPolicy.UI_PREFS : App.UI_PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (EmbeddedSettingsPolicy.isHostPackage(getPackageName())) {
            try {
                io.github.ldxm666.mapclean.App.attachEmbedded(getApplication());
                embeddedHost = getPackageName();
            } catch (Throwable error) {
                android.util.Log.w("MapCleanEmbedded", "settings connection unavailable", error);
                finish();
                return;
            }
        }
        dark = isDarkMode();
        // Two framework themes so AlertDialog follows day/night without a styles.xml.
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                      : android.R.style.Theme_Material_Light_NoActionBar);
        getWindow().setBackgroundDrawable(new ColorDrawable(cBg()));

        // Layout: ONE vertical flow -- hero+tabs (wrap) then pager (weight 1).
        // The decorative blooms live in a sibling overlay FrameLayout that is inserted
        // behind everything with a 0-height..MATCH_PARENT fill, instead of being a
        // weighted band (a weighted blobs band stole half the screen and pushed the
        // cards down, which is how the previous build ended up with a giant disc and
        // no visible hero).
        // Structure:
        //   frame (FrameLayout, the activity content)
        //     ├─ column (LinearLayout, vertical)  hero(+tabs) ─ pager(weight 1)
        //     └─ bottomBar (floating glass bar, bottom-centre)
        // The bar is an overlay so it never steals height from the pager, and the pager
        // gets a real weighted height instead of an absolute topMargin guess.
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(cBg());

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        overlay.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        hero.setPadding(pad, dp(8), pad, 0);
        buildHero(hero);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        hero.addView(tabBar, tlp);

        // 搜索条：tab 下方、pager 上方，四页共用一条。
        // “其他”页只有两三项，SwitchFinder.attach() 会把整条 GONE 掉，不占高度。
        finder = new SwitchFinder(this, dark);
        finder.setPageJumper(new SwitchFinder.PageJumper() {
            @Override public void jump(int page) {
                if (pager != null) pager.setPage(page, true);
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 42));
        flp.topMargin = dp(10);
        hero.addView(finder.bar(), flp);
        hero.addView(finder.resultsHost(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        column.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pager = new Pager(this);
        pager.setOnPageChanged(new Pager.OnPageChanged() {
            @Override public void onPageChanged(int index) { selectTab(index); }
        });
        column.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (embedded()) {
            pager.addView(embeddedAmap() ? buildAmapPage() : buildBaiduPage());
            addTab(embeddedAmap() ? "高德地图" : "百度地图");
        } else {
            pager.addView(buildBaiduPage());
            pager.addView(buildAmapPage());
            addTab("百度地图");
            addTab("高德地图");
        }
        pager.addView(buildTmapPage());
        addTab("腾讯地图");
        pager.addView(buildOtherPage());
        addTab("其他");

        buildBottomBar();
        overlay.addView(bottomBar, barLp);

        setContentView(overlay);

        // Decorative blooms: a full-bleed overlay behind the bar but above the page
        // background would block touches, so they go into the WINDOW background instead.
        applyWindowBloom();

        // Reserve the hero's real height inside the pager once it is measured, then
        // let each page scroll under the floating glass bar via its bottom padding.
        hero.post(new Runnable() {
            @Override public void run() {
                applyHeroInset();
                pager.post(new Runnable() {
                    @Override public void run() {
                        pager.setPage(0, false);
                        selectTab(0);
                        animateIn();
                    }
                });
            }
        });

        overlay.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                hero.setPadding(hero.getPaddingLeft(), dp(8) + top,
                        hero.getPaddingRight(), hero.getPaddingBottom());
                if (barLp != null) {
                    barLp.setMargins(dp(14), 0, dp(14), bottom + dp(14));
                    bottomBar.setLayoutParams(barLp);
                }
                for (int i = 0; i < pages.size(); i++) {
                    pages.get(i).setPadding(0, 0, 0, bottom + dp(92));
                }
                return insets;
            }
        });
    }

    // ── 状态心跳 ────────────────────────────────────────────────────────────
    // v2.2.0 修复：statusRefresher 此前**只被定义、从未 post**。于是 renderStatus()
    // 永不执行 —— 顶部三格指标永远停在「—」、状态药丸永远是个空横线，
    // 而 syncSwitches() 永不执行，所有开关停在构建期 setEnabled(false) 的灰胶囊上。
    // 这一个心跳同时驱动：指标文案 / 状态药丸 / 开关与真实配置对齐。
    @Override protected void onStart() {
        super.onStart();
        statusHandler.removeCallbacks(statusRefresher);
        statusHandler.post(statusRefresher);
    }

    @Override protected void onStop() {
        super.onStop();
        statusHandler.removeCallbacks(statusRefresher);
    }

    @Override protected void onDestroy() {
        statusHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Push the pager down by the measured hero height so the two never overlap. */
    private void applyHeroInset() {
        try {
            ViewGroup.LayoutParams raw = pager.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int want = hero.getHeight() + dp(4);
            if (lp.topMargin != want) {
                lp.topMargin = want;
                pager.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    /** Entrance choreography: hero first, then the tabs, then the live page. */
    private void animateIn() {
        UiKit.enter(hero, dp(10), 0L);
        if (tabBar != null) UiKit.enter(tabBar, dp(8), 70L);
        if (navPill != null) UiKit.enter(navPill, dp(12), 130L);
        final View current = pager.pageCount() > 0 ? pager.getChildAt(pager.page()) : null;
        if (current != null) UiKit.enter(current, dp(14), 40L);

        // Safety net: whatever happens above (cancelled animation, a mid-flight
        // relayout), make sure the page is visible shortly after.
        overlay.postDelayed(new Runnable() {
            @Override public void run() {
                UiKit.reveal(hero);
                UiKit.reveal(tabBar);
                if (current != null) UiKit.reveal(current);
            }
        }, 900L);
    }

    private boolean isDarkMode() {
        int m = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---------------------------------------------------------------- 调色板

    private int cBg()       { return dark ? 0xFF0E1013 : 0xFFF2F3F7; }
    private int cGlass()    { return dark ? 0xB01D2027 : 0xD9FFFFFF; }
    private int cRimHi()    { return dark ? 0x40FFFFFF : 0x70FFFFFF; }
    private int cRimLo()    { return dark ? 0x08FFFFFF : 0x12FFFFFF; }
    private int cTxP()      { return dark ? 0xFFF2F4F8 : 0xFF171B23; }
    private int cTxS()      { return dark ? 0xFF98A0AC : 0xFF7A8290; }
    private int cAccent()   { return dark ? 0xFF7AB1FF : 0xFF0A6CF5; }
    private int cRipple()   { return dark ? 0x267AB1FF : 0x140A6CF5; }
    private int cTrackOff() { return dark ? 0x2E8A94A4 : 0x2E8A94A4; }
    private int cDotOk()    { return dark ? 0xFF2BD88A : 0xFF12B76A; }
    private int cDotBad()   { return dark ? 0xFFFF6B70 : 0xFFE5484D; }

    private int[] blobColors() {
        return dark
                ? new int[]{0x263D6BFF, 0x1E19C37A, 0x227C5CFF}
                : new int[]{0x1E649BFF, 0x162BD9B8, 0x16A78BFA};
    }

    // ---------------------------------------------------------------- 背景光斑

    /**
     * Decorative bloom behind everything.
     *
     * Previous build added giant radial {@code View}s as a weighted band, which stole
     * half the screen and rendered as one enormous disc. Blooms now paint into a single
     * custom Drawable set as the WINDOW background, so they are truly behind the content
     * and cannot intercept touches or consume layout space.
     */
    /**
     * Decorative bloom behind everything.
     *
     * First attempt painted three huge radial gradients as the window background with
     * radii up to 0.62 of the screen diagonal; on a 1080x2400 panel that read as one
     * enormous green disc bleeding through the whole UI. Two lessons applied here:
     *   - the base colour is fully opaque first, so the blooms only tint, never flood;
     *   - alpha stays low (the caller passes faint ARGB) and radii are much smaller,
     *     confined to the top and bottom corners like a real background wash.
     */
    private void applyWindowBloom() {
        try {
            getWindow().setBackgroundDrawable(new BloomDrawable(blobColors(), cBg()));
        } catch (Throwable ignored) {
            getWindow().setBackgroundDrawable(new ColorDrawable(cBg()));
        }
    }

    /** Faint radial washes in the corners; never affects layout and never intercepts input. */
    private static final class BloomDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int[] colors;
        private final int base;
        private final float[] cx = {0.05f, 0.95f, 0.50f};
        private final float[] cy = {0.02f, 0.20f, 1.00f};
        private final float[] rr = {0.34f, 0.26f, 0.30f};

        BloomDrawable(int[] colors, int base) {
            this.colors = colors;
            this.base = base;
        }

        private final android.graphics.Shader[] shaders =
                new android.graphics.Shader[cx.length];
        private int cachedW = -1, cachedH = -1, cachedLeft = Integer.MIN_VALUE,
                cachedTop = Integer.MIN_VALUE;

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            // Opaque base first: blooms are a tint over it, not the colour itself.
            canvas.drawColor(base);
            float w = b.width(), h = b.height();
            float max = Math.max(w, h);
            // The window background keeps the same bounds for the whole session, so the
            // three radial shaders are built once instead of on every draw() pass -- the
            // old version allocated a fresh RadialGradient per bloom per frame.
            if (shaders[0] == null || w != cachedW || h != cachedH
                    || b.left != cachedLeft || b.top != cachedTop) {
                cachedW = (int) w;
                cachedH = (int) h;
                cachedLeft = b.left;
                cachedTop = b.top;
                for (int i = 0; i < colors.length && i < cx.length; i++) {
                    float x = b.left + w * cx[i];
                    float y = b.top + h * cy[i];
                    // Clamp radius hard: nothing bigger than a third of the screen.
                    float radius = Math.min(max * rr[i], max * 0.34f);
                    int solid = colors[i];
                    int clear = solid & 0x00FFFFFF;
                    shaders[i] = new android.graphics.RadialGradient(
                            x, y, radius, solid, clear, android.graphics.Shader.TileMode.CLAMP);
                }
            }
            for (int i = 0; i < shaders.length && i < cx.length; i++) {
                if (shaders[i] == null) continue;
                paint.setShader(shaders[i]);
                float x = b.left + w * cx[i];
                float y = b.top + h * cy[i];
                float radius = Math.min(max * rr[i], max * 0.34f);
                canvas.drawCircle(x, y, radius, paint);
            }
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    // ---------------------------------------------------------------- 玻璃材质

    /** 玻璃面板：底层高光 rim + 内层半透明体，双层圆角模拟玻璃厚度 */
    private Drawable glass(float radiusDp) {
        return UiKit.glass(cGlass(), cRimHi(), cRimLo(), dp(radiusDp), Math.max(1, dp(1)));
    }

    /** 旧签名保留：直接委托给 UiKit，保证已有调用点仍可编译 */
    private Drawable ripple(Drawable content, float radiusDp) {
        return UiKit.ripple(cRipple(), content, dp(radiusDp));
    }

    private GradientDrawable capsule(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(color);
        g.setSize(dp(46), dp(28));
        return g;
    }

    /** 自绘胶囊开关：轨道染青、thumb 带骨至挤压，抛出带弹道感 */
    private GlassSwitch makeSwitch() {
        GlassSwitch sw = new GlassSwitch(this);
        sw.setTrackColors(cTrackOff(), cAccent());
        sw.setThumbColors(0xFFFFFFFF, 0xFFFFFFFF);
        // 不再用 setAlpha 假装「未就绪」：开关从第一帧起就画真实状态、并且可点。
        // 服务未绑定时点击会走写入失败分支给出 Toast，而不是变成一颗点不动的灰胶囊。
        return sw;
    }

    // ---------------------------------------------------------------- 版本 / 更新

    private final UpdateChecker.Callback updateCb = new UpdateChecker.Callback() {
        @Override public void onResult(String latest, boolean outdated, String source) {
            renderVersionLines();
            if (outdated && latest != null && !updateDialogShown) {
                updateDialogShown = true;
                showUpdateDialog(latest);
            }
        }
    };

    /** 版本行配色：已是最新=绿、有新版本=黄、未获取=灰（点一下强制刷新） */
    private void renderVersionLines() {
        if (verLine == null) return;
        String latest = UpdateChecker.latest(this);
        boolean out = UpdateChecker.isOutdated(this);
        String txt;
        if (latest == null) {
            verLine.setTextColor(cTxS());
            txt = "当前版本 v" + Version.NAME + " · 线上版本未获取（点此重试）";
        } else if (out) {
            verLine.setTextColor(0xFFE0A200);      // 黄：有新版本
            txt = "当前版本 v" + Version.NAME + " · 有新版本 v" + latest + "（点此查看/刷新）";
        } else {
            verLine.setTextColor(cDotOk());        // 绿：已是最新
            txt = "当前版本 v" + Version.NAME + " · 已是最新";
        }
        verLine.setText(txt);
        if (updateBtn != null) {
            if (out && latest != null) {
                updateBtn.setText("⬆ 立即更新 v" + latest + "（镜像/GitHub/Telegram）");
                updateBtn.setTextColor(0xFFFFFFFF);
                updateBtn.setBackground(capsuleBg(0xFFE0A200, HEAD_R));
            } else if (latest == null) {
                updateBtn.setText("检查版本更新（点此重试）");
                updateBtn.setTextColor(0xFFFFFFFF);
                updateBtn.setBackground(capsuleBg(cAccent(), HEAD_R));
            } else {
                updateBtn.setText("✓ 已是最新 v" + Version.NAME + "（点此再检查）");
                updateBtn.setTextColor(0xFFFFFFFF);
                updateBtn.setBackground(capsuleBg(dark ? 0xFF1E7A4B : 0xFF12B76A, HEAD_R));
            }
        }
        if (adLine != null) adLine.setText(adaptSummary());
    }

    /** 最佳适配：模块 hook 点验证过的目标版本 vs 本机实际版本 */
    private String adaptSummary() {
        StringBuilder sb = new StringBuilder("已验证版本：");
        for (int i = 0; i < Version.TARGETS.length; i++) {
            String pkg = Version.TARGETS[i][0];
            if (embedded() && !embeddedHost.equals(pkg)) continue;
            String label = Version.TARGETS[i][1];
            String want = Version.TARGETS[i][2];
            if (sb.length() > "已验证版本：".length()) sb.append("　");
            sb.append(label).append(' ').append(want);
            String inst = installedVersion(pkg);
            if (inst == null) {
                sb.append("（未安装）");
            } else if (inst.equals(want) || inst.startsWith(want)) {
                sb.append("（本机 ").append(inst).append(" ✔）");
            } else {
                sb.append("（本机 ").append(inst).append("，逐项检测兼容）");
            }
        }
        return sb.toString();
    }

    private String installedVersion(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0);
            return pi == null ? null : pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 发现新版本：四个出口 —— **镜像下载**（给没代理的用户）/ GitHub 下载 / Telegram / 稍后更新。
     * 用两行按钮，避免三个按钮塞不下第四个选项。
     */
    private void showUpdateDialog(final String latest) {
        final AlertDialog dlg = new AlertDialog.Builder(this).create();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(glass(GLASS_R));
        int pad = dp(20);
        box.setPadding(pad, dp(18), pad, dp(14));

        box.addView(text("发现新版本 v" + latest, 18, Typeface.BOLD, cTxP()));

        String msg = "当前版本 v" + Version.NAME + "　→　最新版本 v" + latest
                + "\n\n【没有代理也能下载】点「镜像下载」走 gh-proxy 反代打开 Releases 页；"
                + "「GitHub 下载」是官方直链（需要能访问 GitHub）。"
                + "\n\n更新后建议强停三家地图 App 再重新打开。";
        TextView mv = text(msg, 13, Typeface.NORMAL, cTxS());
        mv.setLineSpacing(0, 1.28f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(10);
        box.addView(mv, mlp);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        r1.addView(dlgButton("镜像下载", true, new Runnable() {
            @Override public void run() { openUrl(mirrorUrl()); dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r1.addView(spacer(dp(10)));
        r1.addView(dlgButton("GitHub 下载", false, new Runnable() {
            @Override public void run() { openUrl(Version.RELEASES); dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r1, rlp);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.topMargin = dp(10);
        r2.addView(dlgButton("Telegram 群", false, new Runnable() {
            @Override public void run() { openUrl(Version.TG); dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r2.addView(spacer(dp(10)));
        r2.addView(dlgButton("稍后更新", false, new Runnable() {
            @Override public void run() { dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r2, rlp2);

        dlg.setView(box);
        dlg.show();
        try {
            android.view.Window w = dlg.getWindow();
            if (w != null) w.setBackgroundDrawable(new ColorDrawable(0));
        } catch (Throwable ignored) {}
    }

    /** 镜像下载页（按实测可用性排序，取第一个） */
    private String mirrorUrl() {
        String[] m = Version.DOWNLOAD_MIRRORS;
        return (m != null && m.length > 0) ? m[0] : Version.RELEASES;
    }

    /** 圆角纯色背景（更新按钮用） */
    private GradientDrawable capsuleBg(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView dlgButton(String label, boolean primary, final Runnable action) {
        TextView b = text(label, 14, Typeface.BOLD, primary ? 0xFFFFFFFF : cAccent());
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? (dark ? 0xFF2F6BFF : 0xFF0A6CF5) : (dark ? 0x2E7AB1FF : 0x1F0A6CF5));
        bg.setCornerRadius(dp(HEAD_R));
        b.setBackground(ripple(bg, HEAD_R));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }

    // ---------------------------------------------------------------- 区块构建

    private void buildHero(LinearLayout host) {
        // ── 玻璃状态卡：标题 + 右侧状态药丸 + 实时指标条 ──────────────────
        // Old build stacked five full-width text rows (740 px of a 2276 px screen) and
        // let the "verified versions" line wrap onto three lines. Everything that can be
        // a compact pill is one now, and the long version string is collapsed behind tap.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass(24));
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setElevation(0);
        UiKit.clipRounded(card, dp(24));

        // 首行：标题 与 状态药丸
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("MapAdKiller", 21, Typeface.BOLD, cTxP());
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        navPill = new LinearLayout(this);
        navPill.setOrientation(LinearLayout.HORIZONTAL);
        navPill.setGravity(Gravity.CENTER_VERTICAL);
        navPill.setPadding(dp(11), dp(6), dp(13), dp(6));
        navPill.setBackground(UiKit.ripple(cRipple(), UiKit.plate(pillBg(), dp(999)), dp(999)));
        navPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showStatusDetail(); }
        });
        UiKit.pressable(navPill, 0.94f);
        // Exact pixel size: WRAP_CONTENT on a bare View with only a GradientDrawable
        // resolves to nothing, so the drawable expands and floods the parent. That is
        // exactly how a 7dp status dot once rendered as a full-screen green disc.
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(6);
        dotLp.gravity = Gravity.CENTER_VERTICAL;
        navPill.addView(dot(cDotOk(), 7), dotLp);
        statusPillText = text("—", 12, Typeface.BOLD, cTxP());
        navPill.addView(statusPillText);
        titleRow.addView(navPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(titleRow);

        // 副标题一行：作用域摘要（过长时省略，详情进弹层）
        statusView = text("", 12, Typeface.NORMAL, cTxS());
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        card.addView(statusView, slp);

        // 指标条：三个等宽小格子（版本 / 作用域 / 广告 SDK）
        metricRow = new LinearLayout(this);
        metricRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(14);
        card.addView(metricRow, mlp);
        buildMetrics();

        host.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 版本提示行（放在卡片下方，单行、可点）
        verLine = text("", 12, Typeface.BOLD, cTxS());
        verLine.setPadding(dp(6), dp(10), dp(6), 0);
        verLine.setSingleLine(true);
        verLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        verLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "正在检查更新…", Toast.LENGTH_SHORT).show();
                UpdateChecker.check(MainActivity.this, true, updateCb);
            }
        });
        host.addView(verLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adLine = text("", 11, Typeface.NORMAL, cTxS());
        adLine.setLineSpacing(0, 1.2f);
        adLine.setPadding(dp(6), dp(4), dp(6), dp(2));
        adLine.setVisibility(View.GONE);        // 详细适配信息收进弹层，不再撑高首页
        host.addView(adLine);

        updateBtn = text("检查版本更新", 13, Typeface.BOLD, 0xFFFFFFFF);
        updateBtn.setGravity(Gravity.CENTER);
        updateBtn.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        updateBtn.setBackground(UiKit.ripple(cRipple(), UiKit.gradient(
                cAccentTop(), cAccentBottom(), dp(16)), dp(16)));
        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (UpdateChecker.isOutdated(MainActivity.this)) {
                    showUpdateDialog(UpdateChecker.latest(MainActivity.this));
                    return;
                }
                Toast.makeText(MainActivity.this, "正在检查更新…", Toast.LENGTH_SHORT).show();
                UpdateChecker.check(MainActivity.this, true, updateCb);
            }
        });
        UiKit.pressable(updateBtn, 0.97f);
        host.addView(updateBtn, blp);

        renderVersionLines();
    }

    private TextView statusPillText;

    /** 三格实时指标（等宽，各自可点） */
    private void buildMetrics() {
        if (metricRow == null) return;
        metricRow.removeAllViews();
        metricRow.addView(metric("版本", versionMetric, new Runnable() {
            @Override public void run() { verLine.performClick(); }
        }), metricLp(0));
        metricRow.addView(metric("作用域", scopeMetric, new Runnable() {
            @Override public void run() { showStatusDetail(); }
        }), metricLp(1));
        metricRow.addView(metric("广告 SDK", sdkMetric, new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        }), metricLp(2));
    }

    private LinearLayout.LayoutParams metricLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (index > 0) lp.leftMargin = dp(8);
        return lp;
    }

    private String versionMetric = "—";
    private String scopeMetric = "—";
    private String sdkMetric = "—";
    /** 作用域那一格的配色（绿=已勾选 / 红=未勾选 / 灰=读不到）。 */
    private int scopeMetricColor = 0xFF7A8290;
    /** 已捕获 SDK 数量的短缓存：心跳里不必每次都重建 + 排序整张清单。 */
    private int sdkCacheCount = -1;
    private long sdkCacheAt;

    /** 单格指标：值 + 标签，两行，玻璃底 */
    private View metric(String label, String value, final Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11), dp(9), dp(11), dp(9));
        box.setBackground(UiKit.ripple(cRipple(), UiKit.plate(metricBg(), dp(13)), dp(13)));
        box.setContentDescription(label + " " + value);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (action != null) action.run(); }
        });
        UiKit.pressable(box, 0.96f);
        TextView v = text(value, 14, Typeface.BOLD, cTxP());
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(v);
        TextView l = text(label, 10, Typeface.NORMAL, cTxS());
        l.setPadding(0, dp(2), 0, 0);
        box.addView(l);
        box.setTag(v);
        return box;
    }

    /** 刷新三格指标文案 + 配色 */
    private void refreshMetrics() {
        if (metricRow == null) return;
        versionMetric = "v" + Version.NAME;
        int sdk = sdkCount();
        sdkMetric = String.valueOf(sdk);
        for (int i = 0; i < metricRow.getChildCount(); i++) {
            View box = metricRow.getChildAt(i);
            Object tag = box.getTag();
            if (!(tag instanceof TextView)) continue;
            TextView tv = (TextView) tag;
            String value = i == 0 ? versionMetric : i == 1 ? scopeMetric : sdkMetric;
            // 三格各自配色：版本=强调色、作用域=绿/红/灰、SDK=有捕获才点亮。
            int color = i == 0 ? cAccent()
                    : i == 1 ? scopeMetricColor
                    : (sdk > 0 ? cAccent() : cTxS());
            if (!value.contentEquals(tv.getText())) tv.setText(value);
            tv.setTextColor(color);
            box.setContentDescription((i == 0 ? "版本 " : i == 1 ? "作用域 " : "广告 SDK ") + value);
        }
    }

    /** SDK 计数（1.5s 短缓存）：learnedListForApp() 会走服务读 + 排序，不该每拍都跑。 */
    private int sdkCount() {
        long now = android.os.SystemClock.uptimeMillis();
        if (sdkCacheCount >= 0 && now - sdkCacheAt < 1500L) return sdkCacheCount;
        int n = 0;
        try { n = SdkAutoBlock.learnedListForApp().size(); } catch (Throwable ignored) {}
        sdkCacheCount = n;
        sdkCacheAt = now;
        return n;
    }

    private int pillBg()  { return dark ? 0x1FFFFFFF : 0x14FFFFFF; }
    private int metricBg(){ return dark ? 0x14FFFFFF : 0x0D000000; }
    private int cAccentTop()    { return dark ? 0xFF4C8DFF : 0xFF3D82FF; }
    private int cAccentBottom() { return dark ? 0xFF2563EB : 0xFF0A5CE0; }

    /** 小圆点指示器（尺寸由调用方的 LayoutParams 决定，避免 WRAP_CONTENT 无限拉伸） */
    private View dot(int color, int sizeDp) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        return v;
    }



    /** 去广告卡片：始终开启、不折叠，作为页面第一块「仪表盘」 */
    private void buildForcedCard(LinearLayout root) {
        LinearLayout card = newCard();
        TextView head = text("高德 · 精确协议适配", 13, Typeface.BOLD, cAccent());
        head.setPadding(dp(4), dp(10), 0, dp(4));
        card.addView(head);
        addNoteRow(card, "按功能检测原生入口，不因版本号变化停用整个模块。入口不兼容时仅跳过该项；其余功能继续生效。");
        addNoteRow(card, "高德已移除运行时 dex 检索、文本扫描与坐标重排。下方 SDK 清单用于百度、腾讯地图的原有兼容功能。");
        sdkRow = addActionButton(card, sdkSummary(), new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        });
        root.addView(card, cardLp());
    }

    /**
     * 当前正在构建/正在被搜索的那一页的抽屉组。
     *
     * <p>不是 final：四页各建一次，每次换一块。正因为 {@link #secs} 只指当前页，
     * 搜索时遍历它才不会把另外三页的抽屉一起展开 —— 那会让 pager 里的每一页都被改坏。
     */
    private List<Sec> secs = new ArrayList<Sec>();

    private static final class Sec {
        LinearLayout group;
        LinearLayout card;
        TextView chev;
        View bar;
        boolean expanded;
    }

    /** 抽屉分组：玻璃药丸头 + 可折叠玻璃卡片（默认折叠） */
    private Sec newDrawer(String title) {
        final Sec s = new Sec();
        s.expanded = false;
        s.group = new LinearLayout(this);
        s.group.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(56));
        head.setPadding(dp(16), dp(13), dp(14), dp(13));
        head.setBackground(UiKit.ripple(cRipple(), glass(16), 16f));
        head.setElevation(0);
        UiKit.clipRounded(head, dp(16));
        UiKit.pressable(head, 0.985f);

        // 左侧色条：展开时点亮，给折叠状态一个视觉锤点
        s.bar = new View(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), dp(18));
        barLp.rightMargin = dp(11);
        s.bar.setBackground(UiKit.plate(cAccent(), dp(2)));
        s.bar.setAlpha(0.25f);
        head.addView(s.bar, barLp);

        TextView t = text(title, 14, Typeface.BOLD, cTxP());
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        s.chev = text("›", 17, Typeface.BOLD, cTxS());
        s.chev.setGravity(Gravity.CENTER);
        s.chev.setPadding(dp(6), 0, dp(6), 0);
        head.addView(s.chev, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(s); }
        });
        s.group.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        s.card = newCard();
        s.card.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = cardLp();
        clp.topMargin = dp(8);
        s.group.addView(s.card, clp);

        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(9);
        list.addView(s.group, glp);
        secs.add(s);
        return s;
    }

    /**
     * Expand / collapse a drawer.
     *
     * The old build toggled visibility and faded a fixed-height card, so the rows below
     * teleported. Here the card's measured height is animated from 0 to its real size
     * while alpha and a small drift ride along, so the list opens like a drawer instead
     * of snapping. The chevron rotates with a spring so the tap feels acknowledged.
     */
    private void toggle(final Sec s) {
        final boolean expand = !s.expanded;
        s.expanded = expand;
        // 抽屉高度本身就是逐帧动画；再叠一层 LayoutTransition.CHANGING 会在同一帧里
        // 把整列重新测量两遍（实测展开一次刷了 1300+ 帧）。动画期间先把它摘掉，
        // 结束后再装回去，保证其它布局变化仍然有平滑过渡。
        final ViewGroup host = s.group.getParent() instanceof ViewGroup
                ? (ViewGroup) s.group.getParent() : null;
        final android.animation.LayoutTransition saved =
                host == null ? null : host.getLayoutTransition();
        if (host != null && saved != null) host.setLayoutTransition(null);
        final Runnable restore = new Runnable() {
            @Override public void run() {
                if (host != null && saved != null) host.setLayoutTransition(saved);
            }
        };
        s.chev.animate().cancel();
        s.chev.animate().rotation(expand ? 90f : 0f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start();
        if (s.bar != null) {
            s.bar.animate().cancel();
            s.bar.animate().alpha(expand ? 1f : 0.25f)
                    .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.STANDARD).start();
        }

        if (expand) {
            s.card.setVisibility(View.VISIBLE);
            s.card.getLayoutParams().height = 0;
            s.card.setAlpha(0f);
            s.card.requestLayout();
            s.card.post(new Runnable() {
                @Override public void run() {
                    final int target = measureCard(s.card);
                    if (target <= 0) { revealNow(s); restore.run(); return; }
                    UiKit.animateInt(0, target, UiKit.DECELERATE, UiKit.DUR_SLOW,
                            new android.animation.ValueAnimator.AnimatorUpdateListener() {
                                @Override public void onAnimationUpdate(
                                        android.animation.ValueAnimator a) {
                                    if (!s.expanded) return;
                                    int h = (Integer) a.getAnimatedValue();
                                    s.card.getLayoutParams().height = h;
                                    s.card.requestLayout();
                                    s.card.setAlpha(Math.min(1f, h / (float) target));
                                }
                            });
                    s.card.postDelayed(restore, UiKit.DUR_SLOW + 60L);
                }
            });
        } else {
            final int from = s.card.getHeight();
            UiKit.animateInt(from, 0, UiKit.ACCELERATE, UiKit.DUR_BASE,
                    new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                            if (s.expanded) return;
                            int h = (Integer) a.getAnimatedValue();
                            s.card.getLayoutParams().height = h;
                            s.card.requestLayout();
                            s.card.setAlpha(from <= 0 ? 0f : Math.min(1f, h / (float) from));
                        }
                    });
            s.card.postDelayed(new Runnable() {
                @Override public void run() {
                    if (s.expanded) return;
                    s.card.setVisibility(View.GONE);
                    s.card.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    s.card.requestLayout();
                }
            }, UiKit.DUR_BASE + 20L);
            s.card.postDelayed(restore, UiKit.DUR_BASE + 60L);
        }
    }

    private void revealNow(Sec s) {
        s.card.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        s.card.requestLayout();
        s.card.setAlpha(0f);
        s.card.animate().alpha(1f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.DECELERATE).start();
    }

    /** Measure a card at wrap_content without disturbing its current layout. */
    private int measureCard(View card) {
        try {
            int width = card.getWidth();
            if (width <= 0) width = overlay == null ? 0 : overlay.getWidth() - dp(32);
            if (width <= 0) return 0;
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            return card.getMeasuredHeight();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void buildDrawers() {
        Sec sec;

        sec = newDrawer("高德 · 主页标签栏");
        for (String tab : Config.TABS) addSwitch(sec.card, "显示标签「" + tab + "」", Config.K_TAB_PREFIX + tab);

        sec = newDrawer("高德 · 地图标注");
        addNoteRow(sec.card, "统一控制地图上的文字标注：兴趣点图标、店名、道路名与城市名。"
                + "路网、水域与地形照常显示。修改后彻底关闭并重新打开高德生效。");
        addSwitch(sec.card, "显示地图标注", Config.K_MAP_POI);

        sec = newDrawer("高德 · 首页工具宫格");
        addNoteRow(sec.card, "按固定工具 ID 控制显示。关闭全部首页推荐时采用原生工具数据构建等宽宫格，自动补位；图标与点击动作同步保留。");
        addSwitch(sec.card, "显示首页工具宫格", Config.K_TOOLS_VISIBLE);
        addSwitch(sec.card, "反转开关：仅显示已打开的工具", Config.K_TOOLS_ALLOWLIST);
        addNoteRow(sec.card, "开启反转后，只保留你明确打开的工具；未设置、新增和未知工具全部隐藏。关闭反转可恢复原有默认值。修改后重开高德生效。");
        addNoteRow(sec.card, "以下目录取自高德「更多工具」，按出行、服务等分类。开启的工具会补入首页；新增推广工具默认关闭。同名工具按 ID 分别控制。");
        addSwitch(sec.card, "显示「更多工具」", Config.K_TOOL_PREFIX + "更多工具");
        addSwitch(sec.card, "显示「高德出行节」", Config.K_TOOL_PREFIX + "高德出行节");
        for (String group : io.github.ldxm666.mapadkiller.AmapToolCatalog.GROUPS) {
            sec = newDrawer("高德 · 工具：" + group);
            for (String[] tool : io.github.ldxm666.mapadkiller.AmapToolCatalog.ENTRIES) {
                if (!group.equals(tool[2])) continue;
                String name = tool[1];
                if ("145".equals(tool[0])) name += "（高德入口）";
                if ("502".equals(tool[0])) name += "（支付宝入口）";
                addSwitch(sec.card, "显示「" + name + "」", io.github.ldxm666.mapadkiller.AmapToolCatalog.key(tool[0]));
            }
        }

        sec = newDrawer("高德 · 首页推荐内容");
        addSwitch(sec.card, "扫街榜 / 周边精选榜单（首页及分页）", Config.K_FEED_BOARD);
        addSwitch(sec.card, "首页出行节 / 促销活动接口", Config.K_FEED_FESTIVAL);
        addSwitch(sec.card, "地图右侧运营挂件（扫街榜 / 活动入口）", Config.K_MAP_PROMO);
        addSwitch(sec.card, "天气卡片", Config.K_FEED_WEATHER);
        addSwitch(sec.card, "攻略 / 内容流卡片", Config.K_FEED_CONTENT);
        addSwitch(sec.card, "设置家 / 设置单位 / 常去地点", Config.K_HOME_CHIPS);
        addSwitch(sec.card, "关闭首页搜索框热词轮播（文字流动 / 运营广告词）",
                Config.K_HOTWORD_OFF);
        addNoteRow(sec.card, "首页那支搜索框里的文字会自己流动（预置词/运营词，有时是广告）。"
                + "打开本开关后文字被钉死成系统默认提示词「查找地点、公交、地铁」，"
                + "热词品牌图标一并摘掉；关掉开关即可恢复原样。");
        addSwitch(sec.card, "关闭首页「去XXX 打车」打车浮窗（打车运营卡）",
                Config.K_RIDE_CARD_OFF);
        addNoteRow(sec.card, "拦截首页 taxiQS 和 taxiQSMarket 数据卡。关闭全部推荐、天气和常去地点时，首页不创建推荐画布，避免刷新回弹及留白。修改后重开高德生效。");
        addNoteRow(sec.card, "适配高德 17.00.0.2005。旧版按文字区分的景区帖、距离帖等开关已合并为榜单和内容流。尚无精确入口的频道栏、快捷入口排不再提供开关。");

        sec = newDrawer("高德 · 地点详情");
        addSwitch(sec.card, "显示「发现好去处」照片推荐", Config.K_PLACE_RECOMMEND);
        addNoteRow(sec.card, "关闭后移除长按选点详情里的推荐标题和照片信息流，刷新和分页同样生效。保留地址、逛逛周边工具、收藏、分享、新增和路线按钮。重新打开地点详情生效。");

        sec = newDrawer("高德 · 「我的」页");
        addSwitch(sec.card, "订单 / 收藏 / 待评价 一栏", Config.K_MY_ORDER_ROW);
        addSwitch(sec.card, "车辆服务 / 高德运动 一栏", Config.K_MY_SERVICE_ROW);
        addSwitch(sec.card, "达人任务卡片", Config.K_MY_TASK);
        addSwitch(sec.card, "热门活动卡", Config.K_MY_PROMO_ROW);
        addSwitch(sec.card, "猜你喜欢", Config.K_MY_GUESS);
        addSwitch(sec.card, "好友动态", Config.K_MY_FRIENDS);
        addNoteRow(sec.card, "按 Mine 卡片类型与入口行数据处理。搜索页运营卡、资质页及其他未完成协议适配的区域暂不提供开关。");
        // 「其他 · 入口与调试」一组已挪到第 3 页（其他），见 buildOtherPage()

        // ── 高德 · 省电 / 后台（v2.2.2 新增）──────────────────────────────────────
        // 这批键以前只注册进了悬浮面板，模块版设置页一条都没有 —— 用户因此说「模块里没有
        // 省电开关」。label 一律写成「关闭…」：这些键的 true 表示「该行为已被拦截」，
        // 而 readState() 走 Config.defaultVisible 兜底 true ⇒ 默认显示为开。
        //
        // 刻意**不注册** labels_on：它是"启用第三方标注过滤"（反义，消费方用
        // Config.selected 严格读、默认 false），混进这一组会让「开 = 已拦截」的读法出错。
        sec = newDrawer("高德 · 省电 / 后台");
        addNoteRow(sec.card, "开 = 该行为已被拦截（默认全开）；关 = 恢复高德原生行为。"
                + "关掉推送长连接后，行程提醒 / 消息 / 家人地图会恢复，但 ACCS 长连接会重新占用 CPU。");
        addSwitch(sec.card, "关闭推送长连接（ACCS，省电最多）", Config.K_PUSH_HARD_OFF);
        addNoteRow(sec.card, "关掉后高德推送（行程提醒 / 活动 / 消息 / 家人地图）会恢复，"
                + "但 ACCS 长连接会重新占用 CPU。");
        addSwitch(sec.card, "关闭推送消息处理", Config.K_PUSH_OFF);
        addSwitch(sec.card, "关闭推送服务壳（激进）", Config.K_PUSH_KILL_OFF);
        addSwitch(sec.card, "关闭周期任务（WorkManager / JobScheduler）", Config.K_SUBPROC_JOB_OFF);
        addNoteRow(sec.card, "关掉后高德的周期后台任务会恢复（更耗电）。");
        addSwitch(sec.card, "关闭桌面小组件刷新", Config.K_SUBPROC_WIDGET_OFF);
        addSwitch(sec.card, "关闭云控调度池（LotusPool）", Config.K_SUBPROC_LOTUSPOOL_OFF);
        addSwitch(sec.card, "关闭阿里埋点 Analytics", Config.K_TRACK_OFF);
        addSwitch(sec.card, "关闭开屏广告请求", Config.K_ALIMAMA_SPLASH_OFF);
        addSwitch(sec.card, "关闭广告曝光上报", Config.K_ALIMAMA_LOG_OFF);
        addSwitch(sec.card, "关闭子进程拦截（总闸，排查用）", Config.K_SUBPROC_OFF);
        addNoteRow(sec.card, "一次性放行所有子进程拦截；怀疑某功能被误伤时先关这个。");
    }

    // ══════════════════════════════════════════════ v2.0.0 分页（百度 / 高德 / 其他）

    private ScrollView newPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        pages.add(sv);
        return sv;
    }

    private LinearLayout pageRoot(ScrollView sv) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(10), p, dp(96));
        sv.addView(root);
        return root;
    }

    /** 抽屉挂载点：CHANGING 过渡让折叠/展开时后续卡片平滑上移/下移 */
    private LinearLayout newMount() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        lt.setDuration(190);
        m.setLayoutTransition(lt);
        return m;
    }

    /** 页 0：百度地图 —— 分类来自 io.github.ldxm666.bmapclean.Spec（开关写 bmapclean_config） */
    private ScrollView buildBaiduPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        addBmapInfoCard(root);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        final List<Sec> pageSecs = new ArrayList<Sec>();
        secs = pageSecs;
        list = mount;
        expandSnapshot.clear();
        registerPageTarget(PAGE_BAIDU, pageSecs);
        try {
            io.github.ldxm666.bmapclean.Spec.Cat[] cats = io.github.ldxm666.bmapclean.Spec.cats();
            for (int i = 0; i < cats.length; i++) {
                final io.github.ldxm666.bmapclean.Spec.Cat cat = cats[i];
                Sec sec = newDrawer(cat.title);
                if (cat.subtitle != null && cat.subtitle.length() > 0) addNoteRow(sec.card, cat.subtitle);
                for (int j = 0; j < cat.rows.length; j++) {
                    io.github.ldxm666.bmapclean.Spec.Row row = cat.rows[j];
                    if (row.key == null) addNoteRow(sec.card, row.title);
                    else addBmapSwitch(sec.card, row.title, row.key, row.note);
                }
                if (i == 0) toggle(sec);        // 第一组默认展开
            }
        } finally {
            list = old;
        }
        addVersionFooter(root, "首页开关：强停百度地图后重开即可生效；"
                + "「我的」页开关：关掉后重开该页即生效（数据层/JS 层在页面加载时读配置）");
        return sv;
    }

    /** 页 1：高德地图 —— 沿用 MapAdKiller 的抽屉 */
    private ScrollView buildAmapPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        buildForcedCard(root);          // 去广告（始终开启，不折叠）
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        final List<Sec> pageSecs = new ArrayList<Sec>();
        secs = pageSecs;
        list = mount;
        registerPageTarget(PAGE_AMAP, pageSecs);
        try {
            buildDrawers();
        } finally {
            list = old;
        }
        addVersionFooter(root, "改动后请强停对应地图 App 并重新打开以生效；开关可随时单独开/关");
        return sv;
    }

    /**
     * 给一页装上搜索 Target。
     *
     * <p>在页面构建<b>之前</b>调用：{@link SwitchFinder#register} 会重建该页索引，
     * 而索引是由开关工厂在随后的建行过程中一条条填进去的。
     *
     * <p>{@code pageSecs} 必须显式传进来：四页共用同一个 Activity，搜索真正发生时
     * {@link #secs} 早已指向最后构建的那一页。捕获当页自己的那份列表，
     * 搜索才碰不到别的页的抽屉。
     */
    private void registerPageTarget(final int page, final List<Sec> pageSecs) {
        if (finder == null) return;
        finder.register(page, new SwitchFinder.Target() {
            @Override public boolean applyQuery(String query) {
                List<Sec> saved = secs;
                secs = pageSecs;
                try {
                    boolean any = applyQueryOnPage(page, query);
                    applySearch(true);
                    return any;
                } finally {
                    secs = saved;
                }
            }
            @Override public void resetQuery() {
                List<Sec> saved = secs;
                secs = pageSecs;
                try {
                    applySearch(false);
                    // 还原之后把每张卡片的行全部放出来，只显示命中项交由下次进入搜索时重算。
                    for (int i = 0; i < secs.size(); i++) {
                        Sec s = secs.get(i);
                        if (s == null || s.card == null) continue;
                        s.group.setVisibility(View.VISIBLE);
                        filterRows(s.card, "");
                    }
                } finally {
                    secs = saved;
                }
            }
        });
    }

    /** 页 2：腾讯地图 —— 分类来自 io.github.ldxm666.tmapclean.Spec（开关写 tmapclean_config） */
    private ScrollView buildTmapPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        addTmapInfoCard(root);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        final List<Sec> pageSecs = new ArrayList<Sec>();
        secs = pageSecs;
        list = mount;
        registerPageTarget(PAGE_TMAP, pageSecs);
        try {
            // 工具目录由 hook 侧从腾讯地图进程里学回来；进本页时先同步一次。
            io.github.ldxm666.mapclean.TmapPanel.syncLearnedTools();
            io.github.ldxm666.tmapclean.Spec.Cat[] cats = io.github.ldxm666.tmapclean.Spec.cats();
            for (int i = 0; i < cats.length; i++) {
                final io.github.ldxm666.tmapclean.Spec.Cat cat = cats[i];
                Sec sec = newDrawer(cat.title);
                if (cat.subtitle != null && cat.subtitle.length() > 0) addNoteRow(sec.card, cat.subtitle);
                for (int j = 0; j < cat.rows.length; j++) {
                    io.github.ldxm666.tmapclean.Spec.Row row = cat.rows[j];
                    if (row.key == null) addNoteRow(sec.card, row.title);
                    else addTmapSwitch(sec.card, row.title, row.key, row.note);
                }
                if (i == 0) toggle(sec);        // 第一组默认展开
            }
        } finally {
            list = old;
        }
        addVersionFooter(root, "适配腾讯地图 11.7.0。底栏 / 工具宫格 / 设置家在重新进入首页时生效；"
                + "「我的」页与「大家都在看」在重新进入对应页面时生效。"
                + "工具逐项开关需要先打开一次腾讯地图首页（模块会在真实进程里学到工具目录）。");
        return sv;
    }

    /** 页 3：其他 / 调试 */
    private ScrollView buildOtherPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        list = mount;
        try {
            Sec sec = newDrawer("入口与调试");
            if (embedded()) {
                addNoteRow(sec.card, "当前整合版设置仅控制本地图，配置保存在本地图的嵌入式模块服务中。修改后彻底退出并重开地图生效。");
                addNoteRow(sec.card, "返回可继续使用地图。更新整合版时，请下载对应地图的新版安装包。");
                if (embeddedAmap()) addSwitch(sec.card, "调试日志", Config.K_DEBUG_LOG);
                else addNoteRow(sec.card, "调试日志开关位于百度地图页的「诊断」分组。");
            } else {
                addLocalSwitch(sec.card, "隐藏桌面图标", App.K_HIDE_ICON);
                addNoteRow(sec.card, "隐藏后桌面图标消失；LSPosed 管理器里的「打开」入口不受影响（v1.1.0 起），"
                        + "快捷设置磁贴与 adb 命令也始终可用。");
                addNoteRow(sec.card, "高德工具列表隐藏后自动补位；旧版实验性坐标重排已移除。");
                addSwitch(sec.card, "调试日志（框架输出协议适配错误）", Config.K_DEBUG_LOG);
            }
            toggle(sec);
            Sec sec2 = newDrawer("去广告说明");
            addNoteRow(sec2.card, "高德使用版本专用协议适配；推荐内容由高德页的开关控制。百度、腾讯保留原有广告兼容规则和 SDK 清单。");
        } finally {
            list = old;
        }
        addVersionFooter(root, embedded() ? "MapAdKiller v" + Version.NAME + " · 免 root 整合版"
                : "MapAdKiller v" + Version.NAME + " · 高德 / 百度地图精简");
        return sv;
    }

    private void addTab(String label) {
        if (tabBar == null) return;
        final TextView chip = text(label, 13, Typeface.BOLD, cTxS());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(10), dp(10), dp(10));
        chip.setBackground(UiKit.rippleBare(cRipple(), dp(14)));
        chip.setClickable(true);
        UiKit.pressable(chip, 0.95f);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = tabChips.indexOf(v);
                if (i >= 0) { pager.setPage(i, true); selectTab(i); }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        tabChips.add(chip);
        tabBar.addView(chip, lp);
    }

    private void selectTab(int index) {
        if (tabBar == null) return;
        // 每页可搜的范围不同（“其他”页几乎没有），切页时同步决定搜索条去留。
        if (finder != null) {
            finder.dismissKeyboard();
            finder.attach(index);
        }
        for (int i = 0; i < tabChips.size(); i++) {
            final TextView c = tabChips.get(i);
            final boolean on = (i == index);
            // 段控样式：选中=染色胶囊 + 强调色文字；未选中=主文字色 + 0.72 不透明。
            // 之前未选中用 0.62 + 灰色文字，小屏上糊成一片“全是灰按钮”。
            c.setTextColor(on ? cAccent() : cTxP());
            c.setTypeface(Typeface.DEFAULT_BOLD, on ? Typeface.BOLD : Typeface.NORMAL);
            c.animate().cancel();
            c.animate().alpha(on ? 1f : 0.72f)
                    .setDuration(UiKit.DUR_BASE)
                    .setInterpolator(UiKit.SPRING).start();
            c.setBackground(on
                    ? UiKit.ripple(cRipple(), UiKit.plate(tabOnBg(), dp(14)), dp(14))
                    : UiKit.rippleBare(cRipple(), dp(14)));
        }
    }

    private int tabOnBg() { return dark ? 0x2E7AB1FF : 0x1F0A6CF5; }

    // ── 搜索：每页的查询落地 ──────────────────────────────────────────────

    /**
     * 进入/退出搜索时统一展开或还原抽屉，并把不相关的开关行收起来。
     *
     * <p>为什么必须展开：开关都放在默认折叠的抽屉里，用户搜“步行”却看到
     * “高德 · 首页推荐内容”的折叠头，等于什么都没发生。
     * 退出搜索时按 {@link #expandSnapshot} 还原，不让一次搜索永久改变用户的展开偏好。
     *
     * @param searching true = 展开全部 + 只留命中；false = 还原
     */
    private void applySearch(boolean searching) {
        for (int i = 0; i < secs.size(); i++) {
            Sec s = secs.get(i);
            if (s == null || s.card == null) continue;
            if (searching) {
                if (!expandSnapshot.containsKey(s)) expandSnapshot.put(s, s.expanded);
                if (!s.expanded) expand(s);
            } else {
                Boolean was = expandSnapshot.get(s);
                if (was != null && was.booleanValue() != s.expanded) {
                    if (was.booleanValue()) expand(s);
                    else collapse(s);
                }
            }
            if (!searching) expandSnapshot.remove(s);
        }
    }

    private void expand(Sec s) {
        if (s == null || s.expanded || s.card == null) return;
        s.expanded = true;
        s.card.setVisibility(View.VISIBLE);
        s.card.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        s.card.setAlpha(0f);
        s.card.requestLayout();
        s.card.animate().alpha(1f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.DECELERATE).start();
        if (s.chev != null) s.chev.animate().cancel();
        if (s.chev != null) s.chev.animate().rotation(90f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start();
        if (s.bar != null) s.bar.animate().alpha(1f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.STANDARD).start();
    }

    private void collapse(Sec s) {
        if (s == null || !s.expanded || s.card == null) return;
        s.expanded = false;
        if (s.chev != null) s.chev.animate().cancel();
        if (s.chev != null) s.chev.animate().rotation(0f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start();
        if (s.bar != null) s.bar.animate().alpha(0.25f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.STANDARD).start();
        s.card.animate().alpha(0f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.ACCELERATE)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        s.card.setVisibility(View.GONE);
                    }
                }).start();
    }

    /**
     * 一页的查询实现：把该页所有抽屉里的开关行按命中收起/显示。
     *
     * <p>行的可见性靠“标题 + 备注 + 配置键”三段匹配（{@link SwitchFinder.Entry#haystack}），
     * 所以「步行」「walk」「tool_walk」敲法都命中同一个开关。
     */
    private boolean applyQueryOnPage(int page, String query) {
        boolean any = false;
        for (int i = 0; i < secs.size(); i++) {
            Sec s = secs.get(i);
            if (s == null || s.card == null) continue;
            int shown = filterRows(s.card, query);
            // 抽屉一个命中都没有时整组收起，避免留一堆空壳标题。
            if (shown == 0) {
                s.group.setVisibility(View.GONE);
            } else {
                s.group.setVisibility(View.VISIBLE);
                any = true;
            }
        }
        return any;
    }

    /** @return 该卡片里被显示出来的开关行数 */
    private int filterRows(android.view.ViewGroup host, String query) {
        int shown = 0;
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (!(child instanceof android.view.ViewGroup)) continue;
            android.view.ViewGroup row = (android.view.ViewGroup) child;
            Object tag = row.getTag(TAG_HAYSTACK);
            if (!(tag instanceof String)) {
                // 说明行（左侧细色条那一种）不带开关，跟着同组显隐就行，别动它。
                continue;
            }
            String haystack = ((String) tag).toLowerCase(java.util.Locale.ROOT);
            boolean hit = query.length() == 0 || haystack.contains(query);
            row.setVisibility(hit ? View.VISIBLE : View.GONE);
            if (hit) shown++;
        }
        return shown;
    }

    /** 百度那一页的信息卡：模块状态 + 最近一次规则执行 + 一键恢复 */
    private void addBmapInfoCard(LinearLayout root) {
        LinearLayout card = newCard();
        LinearLayout.LayoutParams clp = cardLp();
        root.addView(card, clp);

        TextView head = text("百度地图 · 界面精简", 15, Typeface.BOLD, cTxP());
        head.setPadding(0, dp(2), 0, dp(8));
        card.addView(head);

        // 「最近一次规则执行 / 累计回报」那一大片诊断文字已按 LO 要求移除（2026-10-14）：
        // 用户要的是开关，不是 hook 计数。诊断仍可通过 logcat 看（TAG=BMapClean）。
        TextView reset = text("全部恢复显示（清空百度侧配置）", 13, Typeface.BOLD, cDotBad());
        reset.setPadding(0, dp(14), 0, dp(4));
        reset.setBackground(ripple(capsule(0x12FF6B70), HEAD_R));
        reset.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = false;
                try { ok = io.github.ldxm666.bmapclean.App.resetAll(); } catch (Throwable ignored) {}
                Toast.makeText(MainActivity.this,
                        ok ? "已恢复默认，重开百度地图生效" : "LSPosed 服务未连接",
                        Toast.LENGTH_SHORT).show();
                if (ok) syncSwitches();
            }
        });
        card.addView(reset, rlp);
    }

    /** 百度组的开关行（配置 group 与高德不同，写 bmapclean_config） */
    /** 百度组的开关行（配置 group 与高德不同，写 bmapclean_config） */
    private void addBmapSwitch(final LinearLayout card, String title, final String key, String note) {
        LinearLayout row = newSwitchRow(card, key, title, note);
        TextView label = text(title, 14.5f, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(14), dp(10), dp(14));
        label.setLineSpacing(0, 1.15f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = makeSwitch();
        sw.setLabel(title);
        boolean def = io.github.ldxm666.bmapclean.Spec.defaultVisible(key);
        try {
            sw.setChecked(io.github.ldxm666.bmapclean.App.read(key, def), false);
        } catch (Throwable t) {
            sw.setChecked(def, false);
        }
        bmapSwitches.put(key, sw);
        index(finder, key, title, note, PAGE_BAIDU);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                boolean ok = false;
                try { ok = io.github.ldxm666.bmapclean.App.write(key, next); } catch (Throwable ignored) {}
                if (!ok) {
                    view.setChecked(!next, true);
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
        if (note != null && note.length() > 0) addNoteRow(card, note);
    }

    /** 腾讯那一页的信息卡：模块状态 + 最近一次回报 + 学到的工具目录条数 */
    private void addTmapInfoCard(LinearLayout root) {
        LinearLayout card = newCard();
        LinearLayout.LayoutParams clp = cardLp();
        root.addView(card, clp);

        TextView head = text("腾讯地图 · 界面精简", 15, Typeface.BOLD, cTxP());
        head.setPadding(0, dp(2), 0, dp(8));
        card.addView(head);

        // 「界面规则执行 / 累计回报 / 已学到 N 个首页工具」那一大片诊断文字已按 LO 要求移除
        // （2026-10-14）：用户要的是开关，不是 hook 计数。诊断仍可通过 logcat 看（TAG=TMapClean）。
    }

    /** 腾讯组的开关行（第三个配置 group：tmapclean_config） */
    private void addTmapSwitch(final LinearLayout card, String title, final String key, String note) {
        LinearLayout row = newSwitchRow(card, key, title, note);
        TextView label = text(title, 14.5f, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(14), dp(10), dp(14));
        label.setLineSpacing(0, 1.15f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = makeSwitch();
        sw.setLabel(title);
        boolean def = io.github.ldxm666.tmapclean.Spec.defaultChecked(
                key, io.github.ldxm666.tmapclean.Spec.APP_READ);
        try {
            sw.setChecked(io.github.ldxm666.tmapclean.App.read(key, def), false);
        } catch (Throwable t) {
            sw.setChecked(def, false);
        }
        tmapSwitches.put(key, sw);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                boolean ok = false;
                try { ok = io.github.ldxm666.tmapclean.App.write(key, next); } catch (Throwable ignored) {}
                if (!ok) {
                    view.setChecked(!next, true);
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
        if (note != null && note.length() > 0) addNoteRow(card, note);
    }

    private void addVersionFooter(LinearLayout root, String tip) {
        TextView t = text(tip, 12, Typeface.NORMAL, cTxS());
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(4), dp(16), 0, dp(2));
        root.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 底部悬浮玻璃操作栏：作为 root 的覆盖层浮在 pager 之上，不占布局流 */
    private void buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(7), dp(7), dp(7), dp(7));
        bottomBar.setBackground(glass(26));
        bottomBar.setElevation(0);
        UiKit.clipRounded(bottomBar, dp(26));

        bottomBar.addView(barChip("SDK 清单", new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        }));
        bottomBar.addView(spacer(dp(6)));
        bottomBar.addView(barChip("配置", new Runnable() {
            @Override public void run() { showConfigManager(); }
        }));
        bottomBar.addView(spacer(dp(6)));
        bottomBar.addView(barChip("联系作者", new Runnable() {
            @Override public void run() { showContact(); }
        }));

        // Root is a FrameLayout wrapper only for the bar overlay; the page column is the
        // first child. Using a dedicated overlay host keeps the bar out of the vertical
        // flow so the pager genuinely fills the remaining space.
        barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barLp.setMargins(dp(14), 0, dp(14), dp(14));
    }

    private View spacer(int w) {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, 1);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView barChip(String label, Runnable action) {
        TextView c = text(label, 13, Typeface.BOLD, cAccent());
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        c.setBackground(UiKit.ripple(cRipple(),
                UiKit.plate(dark ? 0x2E7AB1FF : 0x330A6CF5, dp(20)), dp(20)));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        UiKit.pressable(c, 0.93f);
        return c;
    }

    // ---------------------------------------------------------------- 状态区

    /** Render a cached public-service snapshot; framework IPC stays off the UI thread. */
    private void renderStatus() {
        if (statusView == null) return;
        FrameworkStatus.Snapshot state = io.github.ldxm666.mapclean.App.frameworkStatus();
        boolean connected = state.connected;

        String[] targets = {MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP};
        String[] labels = {"高德", "百度", "腾讯"};
        if (embedded()) {
            targets = new String[]{embeddedHost};
            labels = new String[]{embeddedAmap() ? "高德" : "百度"};
        }
        int scoped = 0;
        StringBuilder picked = new StringBuilder();
        if (state.scopeKnown) {
            for (int i = 0; i < targets.length; i++) {
                if (state.scope.contains(targets[i])) {
                    scoped++;
                    if (picked.length() > 0) picked.append(" / ");
                    picked.append(labels[i]);
                }
            }
        }
        boolean scopeOk = scoped > 0;

        String l1 = connected
                ? (state.loaded ? "已连接 · " + state.frameworkLabel() : "已连接 · 正在读取框架信息…")
                : "正在连接框架服务 · 请确认已启用模块";
        if (connected && state.apiVersion > 0 && state.apiVersion < 102) {
            l1 += "（需要 API 102+）";
        }
        String l2;
        if (!connected) l2 = "连接后读取地图作用域";
        else if (!state.loaded) l2 = "正在读取地图作用域…";
        else if (!state.scopeKnown) l2 = "作用域读取失败 · 请在框架管理器确认，稍后重试";
        else if (scopeOk) l2 = "作用域已勾选 · " + picked + "（" + scoped + "/" + targets.length + "）";
        else l2 = "作用域未包含地图 · 请在框架管理器勾选地图应用";

        int scopeColor = !state.scopeKnown ? cTxS() : (scopeOk ? cDotOk() : cDotBad());

        // The hero shows a one-line verdict plus a coloured status pill; the full
        // scope / compatibility dump now lives behind a tap so it cannot wrap and
        // stretch the header the way the old five-row block did.
        int pillColor = connected && scopeOk ? cDotOk() : (connected ? cDotBad() : cTxS());
        scopeMetric = state.scopeKnown ? (scoped + "/" + targets.length) : "未知";
        scopeMetricColor = !state.scopeKnown ? cTxS() : (scopeOk ? cDotOk() : cDotBad());

        // 心跳每几秒跑一次；文案与配色没变就不要再 setText / 重新分配 drawable。
        String sig = connected + "|" + state.loaded + "|" + state.scopeKnown + "|" + scoped
                + "|" + targets.length + "|" + state.frameworkLabel() + "|" + l1 + "|" + l2;
        if (!sig.equals(lastStatusSig)) {
            lastStatusSig = sig;
            if (statusPillText != null) {
                statusPillText.setText(connected ? (scopeOk ? "已就绪" : "待配置") : "连接中");
                statusPillText.setTextColor(pillColor);
            }
            if (navPill != null && navPill.getChildCount() > 0) {
                navPill.getChildAt(0).setBackground(pillDot(pillColor));
            }
            statusView.setText(l1 + "　·　" + l2);
        }
        refreshMetrics();
    }

    private String lastStatusSig;
    private int pillDotColor;
    private GradientDrawable pillDotCache;

    /** 缓存 7dp 状态圆点：renderStatus 跑在心跳上，不该每拍都分配一个 drawable。 */
    private GradientDrawable pillDot(int color) {
        if (pillDotCache == null || pillDotColor != color) {
            pillDotColor = color;
            pillDotCache = dotDrawable(color, 7);
        }
        return pillDotCache;
    }

    /** 圆形色点 drawable（用于刷新状态药丸里的圆点） */
    private GradientDrawable dotDrawable(int color, int sizeDp) {
        int s = dp(sizeDp);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize(s, s);
        return g;
    }

    /** 状态详情弹层：把 hero 折叠掉的适配 / 作用域信息集中展示 */
    private void showStatusDetail() {
        FrameworkStatus.Snapshot state = io.github.ldxm666.mapclean.App.frameworkStatus();
        StringBuilder sb = new StringBuilder();
        sb.append("框架：").append(state.connected
                ? (state.loaded ? state.frameworkLabel() : "已连接（读取中）") : "未连接");
        if (state.apiVersion > 0) sb.append("　API ").append(state.apiVersion);
        sb.append("\n");
        sb.append("作用域：").append(state.scopeKnown ? joinScope(state) : "读取失败");
        sb.append("\n");
        sb.append("本机版本：v").append(Version.NAME);
        String latest = UpdateChecker.latest(this);
        if (latest != null && latest.length() > 0) sb.append("　最新：v").append(latest);
        sb.append("\n");
        int sdk = 0;
        try { sdk = SdkAutoBlock.learnedListForApp().size(); } catch (Throwable ignored) {}
        sb.append("已捕获广告 SDK：").append(sdk).append(" 个");
        String ready = adLine == null ? "" : String.valueOf(adLine.getText());
        if (ready.length() > 0) sb.append("\n").append(ready);

        glassDialog("运行状态", sb.toString(),
                "查看 SDK 清单", new Runnable() {
                    @Override public void run() { showLearnedSdks(); }
                },
                null, null,
                "关闭", null);
    }

    private String joinScope(FrameworkStatus.Snapshot state) {
        String[] targets = {MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP};
        String[] labels = {"高德", "百度", "腾讯"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.length; i++) {
            if (!state.scope.contains(targets[i])) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(labels[i]);
        }
        return sb.length() == 0 ? "未包含地图" : sb.toString();
    }

    private final android.os.Handler statusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 绑定前快轮询（等 LSPosed 服务），绑定后降频，避免每 800ms 白跑一遍 UI 刷新。 */
    private static final long POLL_FAST = 400L;
    private static final long POLL_SLOW = 2500L;

    private final Runnable statusRefresher = new Runnable() {
        @Override public void run() {
            io.github.ldxm666.mapclean.App.refreshFrameworkStatus();
            renderStatus();
            // 服务是异步绑定的：绑定前 readState() 只能回退默认 true，
            // 若此时就把开关画成"开"，用户重开设置页会以为配置全丢了。
            // 所以绑定成功后立刻按真实存储重刷一遍开关。
            boolean bound = App.svc() != null;
            if (bound && !synced) {
                synced = true;
                syncSwitches();
            } else if (!bound && synced) {
                synced = false;      // 服务掉了：下次绑定要重新对齐一遍开关
            }
            statusHandler.postDelayed(this, synced ? POLL_SLOW : POLL_FAST);
        }
    };

    private final Map<String, GlassSwitch> switches = new LinkedHashMap<>();
    private volatile boolean synced;

    private void syncSwitches() {
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s == null) return;
            android.content.SharedPreferences p =
                    s.getRemotePreferences(Config.PREF_GROUP);
            // 一次 getAll() 拿整张表：readVisible(prefs,key) 每个键都会复制一份 TreeMap。
            java.util.Map<String, ?> values = p.getAll();
            for (Map.Entry<String, GlassSwitch> e : switches.entrySet()) {
                GlassSwitch sw = e.getValue();
                sw.setChecked(Config.readVisible(values, e.getKey()), false);
            }
            // 百度那一页用的是另一个 group（bmapclean_config）—— 单独再刷一遍
            try {
                android.content.SharedPreferences bp =
                        s.getRemotePreferences(io.github.ldxm666.bmapclean.Cfg.GROUP);
                for (Map.Entry<String, GlassSwitch> e : bmapSwitches.entrySet()) {
                    GlassSwitch sw = e.getValue();
                    sw.setChecked(bp.getBoolean(e.getKey(),
                            io.github.ldxm666.bmapclean.Spec.defaultVisible(e.getKey())), false);
                }
            } catch (Throwable ignored) {}
            // 腾讯那一页同理（tmapclean_config）
            try {
                android.content.SharedPreferences tp =
                        s.getRemotePreferences(io.github.ldxm666.tmapclean.Cfg.GROUP);
                for (Map.Entry<String, GlassSwitch> e : tmapSwitches.entrySet()) {
                    GlassSwitch sw = e.getValue();
                    sw.setChecked(tp.getBoolean(e.getKey(),
                            io.github.ldxm666.tmapclean.Spec.defaultChecked(
                                    e.getKey(), io.github.ldxm666.tmapclean.Spec.APP_READ)), false);
                }
            } catch (Throwable ignored) {}
            refreshSdkRow();
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- 对话框

    // ------------------------------------------------------------ 玻璃对话框

    /** 框架 AlertDialog 套玻璃皮：透明窗口 + 自绘玻璃圆角容器，标题/正文/三键齐全 */
    private AlertDialog glassDialog(String title, String message,
                                    String posText, Runnable posAction,
                                    String neuText, Runnable neuAction,
                                    String negText, Runnable negAction) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(glass(GLASS_R));
        int pad = dp(20);
        box.setPadding(pad, dp(18), pad, dp(10));
        TextView tv = text(title, 18, Typeface.BOLD, cTxP());
        box.addView(tv);
        if (message != null && message.length() > 0) {
            TextView mv = text(message, 14, Typeface.NORMAL, cTxS());
            mv.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(10);
            box.addView(mv, mlp);
        }
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        box.addView(btns, blp);
        AlertDialog dlg = new AlertDialog.Builder(this).create();
        Runnable[] dismiss = new Runnable[1];
        java.util.List<String[]> defs = new ArrayList<>();
        if (negText != null) defs.add(new String[]{negText, "neg"});
        if (neuText != null) defs.add(new String[]{neuText, "neu"});
        if (posText != null) defs.add(new String[]{posText, "pos"});
        for (final String[] def : defs) {
            TextView b = text(def[0], 14, Typeface.BOLD, cAccent());
            b.setPadding(dp(14), dp(10), dp(14), dp(10));
            b.setBackground(ripple(dark ? tintBg(0x2E7AB1FF) : tintBg(0x1E0A6CF5), HEAD_R));
            Runnable run = "pos".equals(def[1]) ? posAction : "neu".equals(def[1]) ? neuAction : negAction;
            final Runnable act = run != null ? run : new Runnable() {
                @Override public void run() { }
            };
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    act.run();
                    dlg.dismiss();
                }
            });
            LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp2.leftMargin = dp(8);
            btns.addView(b, blp2);
        }
        dlg.setView(box);
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.45f;
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360));
            w.setAttributes(lp);
        }
        return dlg;
    }

    private GradientDrawable tintBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(HEAD_R));
        return g;
    }

    // ------------------------------------------------------------ 配置管理

    private static final String CFG_SNAPSHOT = "cfg_snapshot";
    private static final String CFG_FILE = "MapAdKiller_config.json";

    private void showConfigManager() {
        boolean hasSnap = readSnapshot() != null;
        glassDialog("配置管理",
                "保存配置：把高德、百度及桌面图标设置存到本地\n" +
                "恢复配置：回到上次保存的快照\n" +
                "导出 / 导入：通过分享或粘贴迁移到其它设备" + (hasSnap ? "\n\n已有本地快照" : "\n\n尚未保存过快照"),
                "保存配置", new Runnable() {
                    @Override public void run() { saveSnapshot(); }
                },
                "恢复配置", hasSnap ? new Runnable() {
                    @Override public void run() {
                        glassDialog("恢复配置？", "将覆盖当前全部开关，回到上次保存的快照。",
                                "恢复", new Runnable() {
                                    @Override public void run() { restoreSnapshot(); }
                                }, null, null, "取消", null);
                    }
                } : null,
                "更多", new Runnable() {
                    @Override public void run() { showConfigMore(); }
                });
    }

    private void showConfigMore() {
        glassDialog("导入 / 导出",
                "导出：生成配置文本并分享（可发给自己 / 存文件）\n" +
                "导入：粘贴配置文本恢复",
                "导出", new Runnable() {
                    @Override public void run() { exportConfig(); }
                },
                "导入", new Runnable() {
                    @Override public void run() { importConfig(); }
                },
                "恢复默认", new Runnable() {
                    @Override public void run() {
                        glassDialog("恢复默认？", "恢复" + settingsTargetLabel() + "的默认开关，强停地图 App 后生效。",
                                "恢复默认", new Runnable() {
                                    @Override public void run() {
                                        resetConfig();
                                    }
                                }, null, null, "取消", null);
                    }
                });
    }

    private Map<String, java.util.Set<String>> configSchema() {
        Map<String, java.util.Set<String>> schema = new LinkedHashMap<>();
        java.util.Set<String> amap = new java.util.LinkedHashSet<>(switches.keySet());
        amap.add(ConfigBackup.LEARNED);
        if (!embedded() || embeddedAmap()) schema.put(ConfigBackup.AMAP, amap);
        if (!embedded() || !embeddedAmap()) schema.put(ConfigBackup.BAIDU, new java.util.LinkedHashSet<>(bmapSwitches.keySet()));
        if (!embedded()) schema.put(ConfigBackup.UI, new java.util.LinkedHashSet<>(java.util.Arrays.asList(App.K_HIDE_ICON)));
        return schema;
    }

    private ConfigBackup.Store configStore() {
        final io.github.libxposed.service.XposedService service = App.svc();
        if (service == null) throw new IllegalStateException("框架服务未连接，请稍后重试");
        return new ConfigBackup.Store() {
            private android.content.SharedPreferences prefs(String group) {
                return ConfigBackup.UI.equals(group) ? uiPrefs()
                    : service.getRemotePreferences(group);
            }
            @Override public Map<String, ?> read(String group) { return prefs(group).getAll(); }
            @Override public boolean write(String group, Map<String, Object> values) {
                android.content.SharedPreferences.Editor editor = prefs(group).edit();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    Object value = entry.getValue();
                    if (value == null) editor.remove(entry.getKey());
                    else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                    else if (value instanceof java.util.Set) editor.putStringSet(entry.getKey(), new java.util.LinkedHashSet<String>((java.util.Set<String>) value));
                    else throw new IllegalArgumentException("配置类型错误");
                }
                return editor.commit();
            }
        };
    }

    private JSONObject collectConfig() throws Exception {
        return ConfigBackup.encode(configStore(), configSchema(), getPackageName());
    }

    private void saveSnapshot() {
        try {
            String json = collectConfig().toString();
            if (!uiPrefs().edit()
                    .putString(CFG_SNAPSHOT, json).commit()) throw new IllegalStateException("本地快照写入失败");
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject readSnapshot() {
        try {
            String j = uiPrefs()
                    .getString(CFG_SNAPSHOT, null);
            if (j != null) {
                JSONObject o = new JSONObject(j);
                ConfigBackup.decode(o, configSchema());
                return o;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void applyConfig(JSONObject backup) {
        try {
            Map<String, Map<String, Object>> groups = ConfigBackup.decode(backup, configSchema());
            int count = ConfigBackup.apply(configStore(), groups);
            if (!embedded() && groups.containsKey(ConfigBackup.UI) && !App.setHideIcon(this, App.hideIcon(this)))
                throw new IllegalStateException("开关已恢复，桌面图标更新失败，请重新切换图标开关");
            String scope = embedded() ? settingsTargetLabel()
                    : (groups.containsKey(ConfigBackup.BAIDU) ? "高德和百度" : "高德");
            Toast.makeText(this, "已恢复" + scope + " " + count + " 项，重开地图后生效", Toast.LENGTH_LONG).show();
            recreate();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetConfig() {
        try {
            Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
            for (Map.Entry<String, java.util.Set<String>> entry : configSchema().entrySet()) {
                if (ConfigBackup.UI.equals(entry.getKey())) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                for (String key : entry.getValue()) values.put(key, null);
                groups.put(entry.getKey(), values);
            }
            ConfigBackup.apply(configStore(), groups);
            Toast.makeText(this, settingsTargetLabel() + "已恢复默认，重开地图后生效", Toast.LENGTH_LONG).show();
            recreate();
        } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void restoreSnapshot() {
        JSONObject backup = readSnapshot();
        if (backup == null) {
            Toast.makeText(this, "没有可用快照", Toast.LENGTH_SHORT).show();
            return;
        }
        applyConfig(backup);
    }

    private void exportConfig() {
        try {
            String json = collectConfig().toString(2);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_SUBJECT, CFG_FILE);
            send.putExtra(Intent.EXTRA_TEXT, json);
            send.putExtra(Intent.EXTRA_TITLE, CFG_FILE);
            startActivity(Intent.createChooser(send, "导出配置"));
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importConfig() {
        final ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        final EditText input = new EditText(this);
        input.setHint("粘贴导出的 JSON 配置");
        input.setTextSize(13);
        input.setTextColor(cTxP());
        input.setHintTextColor(cTxS());
        input.setBackground(tintBg(dark ? 0x33000000 : 0x14000000));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (cm != null) {
            ClipData cd = cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).getText();
                if (t != null && t.length() > 0) input.setText(t);
            }
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(glass(GLASS_R));
        int pad = dp(20);
        box.setPadding(pad, dp(18), pad, dp(10));
        box.addView(text("导入配置", 18, Typeface.BOLD, cTxP()));
        // 内置默认配置的入口与导入同源（都是「把一份 JSON 应用过来」），放在这里而不是
        // 再挤一个按钮进「更多」—— 那个对话框的三键布局是固定的。
        box.addView(restoreDefaultsRow());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130));
        ilp.topMargin = dp(12);
        box.addView(input, ilp);
        TextView tip = text("粘贴后点导入；键名不匹配的项会被忽略", 12, Typeface.NORMAL, cTxS());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        box.addView(tip, tlp);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);
        box.addView(btns, blp);
        TextView cancel = text("取消", 14, Typeface.BOLD, cTxS());
        cancel.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView go = text("导入", 14, Typeface.BOLD, cAccent());
        go.setPadding(dp(14), dp(10), dp(14), dp(10));
        go.setBackground(ripple(dark ? tintBg(0x2E7AB1FF) : tintBg(0x1E0A6CF5), HEAD_R));
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doImport(String.valueOf(input.getText())); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        btns.addView(cancel, clp);
        btns.addView(go, clp);
        final AlertDialog d2 = new AlertDialog.Builder(this).create();
        d2.setView(box);
        d2.show();
        Window w = d2.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.45f;
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360));
            w.setAttributes(lp);
        }
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d2.dismiss(); }
        });
    }

    private void doImport(String text) {
        try {
            applyConfig(new JSONObject(text));
        } catch (Throwable t) {
            Toast.makeText(this, "JSON 解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 「恢复默认配置」一行：重新应用模块内置的那份 JSON。
     *
     * <p>与 {@link #importConfig()} 的写入逻辑同源（都走 {@code DefaultConfig}），不重复造。
     * 与应用首启的区别只有一处：这里是无条件覆盖，所以先确认。
     */
    private View restoreDefaultsRow() {
        TextView action = text("恢复默认配置", 13, Typeface.BOLD, cAccent());
        action.setPadding(dp(2), dp(6), dp(2), dp(6));
        action.setBackground(ripple(dark ? tintBg(0x2E7AB1FF) : tintBg(0x1E0A6CF5), HEAD_R));
        action.setGravity(Gravity.CENTER);
        action.setContentDescription("重新应用模块内置的默认配置，会覆盖当前开关");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        action.setLayoutParams(lp);
        action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                glassDialog("恢复默认配置？",
                        "将把模块内置的默认配置重新写入，会覆盖你已经改过的开关。\n\n"
                        + "没有值的项（null）保持原样；写入后重开地图生效。",
                        "恢复", new Runnable() {
                            @Override public void run() { applyBuiltInDefaults(); }
                        }, null, null, "取消", null);
            }
        });
        return action;
    }

    /** 应用内置默认配置并刷新界面；失败只提示，不中断。 */
    private void applyBuiltInDefaults() {
        try {
            DefaultConfig.Result result = DefaultConfig.apply(this);
            if (result.groups.isEmpty()) {
                Toast.makeText(this, "没有可写入的默认配置（服务未连接或资源缺失）",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "已恢复 " + result.applied + " 项，重开地图后生效",
                    Toast.LENGTH_LONG).show();
            recreate();
        } catch (Throwable t) {
            Toast.makeText(this, "恢复失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 联系作者：Telegram 群组 + GitHub Issues */
    private void showContact() {
        glassDialog("联系作者",
                "Telegram 群组：反馈 / 交流 / 第一时间更新\n"
                + "GitHub Issues：bug 报告 / 功能需求",
                "Telegram 群", new Runnable() {
                    @Override public void run() { openUrl("https://t.me/+2rqisPe5tJxjNTk1"); }
                },
                "GitHub Issues", new Runnable() {
                    @Override public void run() { openUrl("https://github.com/ldxm666/MapAdKiller/issues"); }
                },
                "取消", null);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 已捕获清单；还能一键清空重新学习 */
    private void showLearnedSdks() {
        java.util.List<String> list;
        try { list = SdkAutoBlock.learnedListForApp(); } catch (Throwable t) { list = null; }
        if (list == null || list.isEmpty()) {
            glassDialog("已捕获广告 SDK",
                    "还没有捕获记录。\n\n模块会扫描三家地图 App 自身 dex 里带广告特征的类，"
                    + "把厂商包名记下来并拦截；记录跨进程保存，下次启动直接生效。",
                    "知道了", null, null, null, null, null);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        glassDialog("已捕获广告 SDK（" + list.size() + "）",
                sb.toString().trim(),
                "关闭", null,
                "清空重新学习", new Runnable() {
                    @Override public void run() {
                        try { SdkAutoBlock.clearLearned(); } catch (Throwable ignored) {}
                        Toast.makeText(MainActivity.this, "已清空，强停对应地图后重新打开即重新学习",
                                Toast.LENGTH_LONG).show();
                        recreate();
                    }
                }, null, null);
    }

    // ---------------------------------------------------------------- 行工厂

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass(22));
        card.setPadding(dp(10), dp(4), dp(10), dp(4));
        card.setElevation(0);
        UiKit.clipRounded(card, dp(22));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(dark ? 0x14FFFFFF : 0x0F000000);
        d.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return d;
    }

    /** 行容器：60dp 最小高、行级涟漪、可点回弹 */
    private LinearLayout newRow(LinearLayout card) {
        if (card.getChildCount() > 0) card.addView(divider());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(6), 0, dp(6), 0);
        row.setBackground(UiKit.rippleBare(cRipple(), dp(14)));
        return row;
    }

    /**
     * 开关行 + 搜索索引。
     *
     * <p>haystack（标题 + 备注 + 配置键）直接挂在行的 tag 上，搜索时
     * {@link #filterRows} 顺着视图树读出来即可 —— 不必另外维护一份
     * “行 → key”的映射，也就不可能和界面显示的文字脱节。
     */
    private LinearLayout newSwitchRow(LinearLayout card, String key, String title, String note) {
        LinearLayout row = newRow(card);
        StringBuilder sb = new StringBuilder();
        sb.append(title == null ? "" : title);
        if (note != null && note.length() > 0) sb.append('\n').append(note);
        sb.append('\n').append(key == null ? "" : key);
        row.setTag(TAG_HAYSTACK, sb.toString());
        return row;
    }

    /** 行上缓存的 haystack key。用框架不会占用的负数 id，避免再建 ids.xml。 */
    private static final int TAG_HAYSTACK = 0x7f0f0001;
    /** 统一规格开关行：60dp 最小高、左标题右侧胶囊开关、行间分隔线（写 RemotePreferences） */
    private void addSwitch(final LinearLayout card, String title, final String key) {
        LinearLayout row = newSwitchRow(card, key, title, null);

        TextView label = text(title, 14.5f, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(14), dp(10), dp(14));
        label.setLineSpacing(0, 1.15f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = makeSwitch();
        sw.setLabel(title);
        sw.setChecked(readState(key), false);
        switches.put(key, sw);
        index(finder, key, title, null, PAGE_AMAP);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 单一真相路径：开关自己吃掉点击并翻转 → 回调里落盘；落盘失败就回滚。
        // 行点击只是把同一个 performClick() 代理一遍，所以胶囊与行永远不会打架。
        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                if (App.writeBoolean(key, next)) {
                    if (Config.K_TOOLS_ALLOWLIST.equals(key)) syncSwitches();
                } else {
                    view.setChecked(!next, true);
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
    }

    /** 本地开关行（不走 LSPosed RemotePreferences），仅用于模块 App 自身组件可见性。 */
    /** 本地开关行（不走 LSPosed RemotePreferences），仅用于模块 App 自身组件可见性。 */
    private void addLocalSwitch(final LinearLayout card, String title, final String key) {
        LinearLayout row = newRow(card);

        TextView label = text(title, 14.5f, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(14), dp(10), dp(14));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = makeSwitch();
        sw.setLabel(title);
        boolean on = false;
        try {
            on = getSharedPreferences(App.UI_PREFS, MODE_PRIVATE).getBoolean(key, false);
        } catch (Throwable ignored) {}
        sw.setChecked(on, false);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 本地开关不进 RemotePreferences：翻转后先弹确认，确认了才落盘，取消就回滚。
        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                if (!next) { applyHide(false, view); return; }
                // 桌面入口（LAUNCHER alias）禁掉后桌面看不到，但 LSPosed 管理器
                // 走 CATEGORY_INFO 通道（见 AndroidManifest 注释），入口不受影响。
                glassDialog("隐藏桌面图标？",
                        "· 桌面/抽屉里的 MapAdKiller 图标消失\n"
                        + "· LSPosed 管理器里的「打开」入口不受影响，随时可回到本页\n"
                        + "· 快捷设置磁贴与 adb 命令同样始终可用",
                        "仍然隐藏", new Runnable() {
                            @Override public void run() { applyHide(true, view); }
                        }, null, null, "取消", new Runnable() {
                            @Override public void run() { view.setChecked(false, true); }
                        });
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
    }

    private void applyHide(boolean hide, GlassSwitch sw) {
        if (embedded()) return;
        boolean ok = App.setHideIcon(MainActivity.this, hide);
        sw.setChecked(hide);
        Toast.makeText(MainActivity.this,
                ok ? (hide ? "已隐藏桌面图标（LSPosed 管理器可随时再打开）" : "已恢复桌面图标")
                   : "组件状态变更被系统拒绝",
                Toast.LENGTH_SHORT).show();
    }

    /** 当前状态：优先读 RemotePreferences（Hook 侧同一数据源），读不到按各键默认值 */
    private boolean readState(String key) {
        boolean def = Config.defaultVisible(key);
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s != null) return Config.readVisible(s.getRemotePreferences(Config.PREF_GROUP), key);
        } catch (Throwable ignored) {}
        return def;
    }

    /** 把 SDK 计数刷新成实时值 */
    private void refreshSdkRow() {
        try {
            if (sdkRow != null) sdkRow.setText(sdkSummary());
        } catch (Throwable ignored) {}
    }

    /** 设置页上那一行：已自动捕获多少个广告 SDK */
    private String sdkSummary() {
        int n = 0;
        try { n = SdkAutoBlock.learnedListForApp().size(); } catch (Throwable ignored) {}
        return "已捕获广告 SDK：" + n + " 个 · 点按查看清单";
    }

    private TextView addActionButton(LinearLayout card, String title, final Runnable action) {
        if (card.getChildCount() > 0) card.addView(divider());
        TextView label = text(title, 13.5f, Typeface.BOLD, cAccent());
        label.setPadding(dp(14), dp(13), dp(14), dp(13));
        label.setGravity(Gravity.CENTER);
        label.setBackground(UiKit.ripple(cRipple(), UiKit.plate(tintBg(), dp(14)), dp(14)));
        label.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        UiKit.pressable(label, 0.97f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        card.addView(label, lp);
        return label;
    }

    private int tintBg() { return dark ? 0x2E7AB1FF : 0x1A0A6CF5; }

    /** 说明文字：左侧细色条 + 淡色正文，与开关行区分开 */
    private void addNoteRow(LinearLayout card, String s) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setPadding(dp(6), dp(12), dp(6), dp(12));

        View rule = new View(this);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(dp(2),
                ViewGroup.LayoutParams.MATCH_PARENT);
        ruleLp.rightMargin = dp(10);
        rule.setBackground(UiKit.plate(dark ? 0x33FFFFFF : 0x1F000000, dp(1)));
        wrap.addView(rule, ruleLp);

        TextView t = text(s, 12.5f, Typeface.NORMAL, cTxS());
        t.setLineSpacing(dp(2), 1.18f);
        wrap.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------------------------------------------------------------- 基础工厂

    private TextView text(String s, float sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(style == Typeface.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT, style);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
