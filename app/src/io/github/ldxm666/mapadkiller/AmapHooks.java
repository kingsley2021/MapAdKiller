package io.github.ldxm666.mapadkiller;

/** Exact application entry points. No view traversal or SDK discovery in AMap. */
public final class AmapHooks {
    private AmapHooks() {}
    public static void install(final ClassLoader cl) {
        // 「安装包异常」修复 #2：覆盖安装后首次冷启动的 linker namespace 库目录错位。
        // 必须是第一个 feature（越早越好，要点在 ContentProvider 之前跑完）。
        feature("libdir_fix", new Runnable() { public void run() { AmapLibDirFix.install(cl); } });
        AmapProtocolHooks.compatible(cl);
        // 「安装包异常」修复：原生库 libamaptts/libxquic 预加载（见 AmapSoPreload 注释）。
        feature("so_preload", new Runnable() { public void run() { AmapSoPreload.install(cl); } });
        Class<?> preloader = H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl");
        H.hookSig(preloader, "canShowSplash", "amap_splash_gate", H.FALSE);
        feature("search", new Runnable() { public void run() { HotWordGuard.install(cl); } });
        feature("search_grid", new Runnable() { public void run() { AmapSearchGrid.install(cl); } });
        feature("home", new Runnable() { public void run() { AmapNativeHome.install(cl); } });
        feature("tabs", new Runnable() { public void run() { AmapTabLayout.install(cl); } });
        feature("widgets", new Runnable() { public void run() { AmapMapWidget.install(cl); } });
        feature("labels", new Runnable() { public void run() { AmapPoiLayer.install(cl); } });
        feature("data", new Runnable() { public void run() { AmapProtocolHooks.install(cl); } });
        // 15.21.0.2038 专用：首页信息流 + 工具宫格（版本前缀匹配，17.02 不进入）。
        feature("feed1521", new Runnable() { public void run() { AmapFeed1521.install(cl); } });
        feature("tools1521", new Runnable() { public void run() { AmapToolbox1521.install(cl); } });
        feature("mine1521", new Runnable() { public void run() { AmapMine1521.install(cl); } });
        // 长按「我的」入口是**免 root 内置版专属**（root/LSPosed 用户用模块 App 的设置页）。
        // 在安装层就跳过，避免"装了 hook 再让面板打不开"的隐性开销。
        feature("mine_entry", new Runnable() { public void run() {
            if (!HostInfo.isEmbedded()) {
                H.log("event=mine_entry_skipped reason=module_mode_at_install");
                return;
            }
            AmapMineEntry.install(cl);
        } });
        feature("cruise", new Runnable() { public void run() { AmapCruise.install(cl); AmapCruise.installProbe(cl); } });
        feature("place_recommend", new Runnable() { public void run() {
            try { AmapPlaceAds.install(cl); }
            catch (Exception error) { throw new IllegalStateException(error); }
        } });
        H.done(MainHook.PKG_AMAP);
    }
    private static void feature(String name, Runnable installer) {
        int before = H.ok.get();
        try { installer.run(); }
        catch (Throwable error) { H.log("event=feature_skipped name=" + name + " reason=" + error.getClass().getSimpleName()); }
        H.log("event=feature_probe name=" + name + " hooks=" + (H.ok.get() - before));
    }
}
