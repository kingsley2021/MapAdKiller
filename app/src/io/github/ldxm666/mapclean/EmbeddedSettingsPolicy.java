package io.github.ldxm666.mapclean;

/** Narrow launch policy for the settings activity inside an LSPatch map process. */
public final class EmbeddedSettingsPolicy {
    public static final String EXTRA = "mapclean.embedded.settings";
    public static final String ACTION = "io.github.ldxm666.mapclean.EMBEDDED_SETTINGS";
    public static final String UI_PREFS = "mapclean_embedded_ui";
    public static final String BRIDGE_GROUP = "mapclean_embedded_bridge";
    public static final String PLACEHOLDER = "placeholder_activity";
    public static final String AMAP = "com.autonavi.minimap";
    public static final String BAIDU = "com.baidu.BaiduMap";
    public static final String TENCENT = "com.tencent.map";

    private EmbeddedSettingsPolicy() {}

    /**
     * True when the module is running from inside a patched host (rootless / integrated
     * build) rather than as a standalone LSPosed module.
     *
     * <p>Two probes, because neither alone is sufficient:
     * <ol>
     *   <li>The framework name reported by the module. LSPatch identifies itself as such,
     *       and this is the only signal available at {@code onPackageReady} time.</li>
     *   <li>The host's asset table. LSPatch always writes {@code lspatch/config.json} next
     *       to the embedded module, so its presence is conclusive. This needs the host
     *       Application, which does not exist yet during {@code onPackageReady} -- so it is
     *       only consulted once an Application is available.</li>
     * </ol>
     * An earlier revision used the asset probe exclusively and therefore always answered
     * false at hook-install time, which silently disabled the whole floating layer.
     */
    public static boolean integrated(String frameworkName, android.content.Context host) {
        if (frameworkName != null && "LSPatch".equalsIgnoreCase(frameworkName)) return true;
        if (host != null) {
            try {
                host.getAssets().open("lspatch/config.json").close();
                return true;
            } catch (Throwable ignored) {
                // Not an LSPatch host.
            }
        }
        return false;
    }

    /**
     * Every map this module can run inside.
     *
     * <p><b>This list is load-bearing for service delivery, not just for the settings entry.</b>
     * In a slim (免 root 内置) build {@code mapclean.EmbeddedSettings} is the
     * {@code tools/variant-stubs} no-op, whose {@code enabledFor()} is exactly this method --
     * and {@code App.attachEmbedded()} opens with that gate. Omitting a vendor here makes
     * {@code attachEmbedded} throw <i>before</i> it registers the framework service listener,
     * and {@code FloatingConfig.ensureService()}'s catch turns that into a silent no-op: the
     * process then has no delivery path at all, {@code App.svc()} stays null forever, and
     * every switch in that vendor's orb reads 只读 · 服务未连接.
     *
     * <p>腾讯 was missing from this list, which is precisely why 百度 worked and 腾讯 did not
     * in the same build. Real-device evidence (build 2.1.6, both processes in one logcat):
     * <pre>
     *   I/MapCleanApp ( 8013) [com.baidu.BaiduMap]  : onServiceBind ok
     *   I/MapCleanApp ( 8013) [com.baidu.BaiduMap]  : registerListener ok
     *   W/MapCleanFloat(20273) [com.tencent.map]    : embedded service gate closed pkg=com.tencent.map
     *   W/MapCleanFloat(20273) [com.tencent.map]    : java.lang.IllegalStateException: Embedded settings require the LSPatch host
     *   W/MapCleanFloat(20273) [com.tencent.map]    :   at io.github.ldxm666.mapclean.App.attachEmbedded(App.java:62)
     *   W/MapCleanFloat(20273) [com.tencent.map]    :   at io.github.ldxm666.mapclean.FloatingConfig.ensureService(FloatingConfig.java:84)
     *   W/MapCleanFloat(20273) [com.tencent.map]    :   at io.github.ldxm666.mapclean.FloatingHooks$3.onActivityCreated(FloatingHooks.java:86)
     * </pre>
     */
    public static boolean isHostPackage(String pkg) {
        return AMAP.equals(pkg) || BAIDU.equals(pkg) || TENCENT.equals(pkg);
    }

    public static boolean mayInstall(String frameworkName, String pkg, String process) {
        return "LSPatch".equalsIgnoreCase(frameworkName) && isHostPackage(pkg)
                && pkg.equals(process);
    }

    /** Manager mode has no writable host service; integrated mode becomes ready after delivery. */
    public static boolean canOpen(String host, String pkg, boolean loaderReady, boolean serviceReady) {
        return isHostPackage(host) && host.equals(pkg) && loaderReady && serviceReady;
    }

    public static boolean accepts(String host, String intentPackage, String componentPackage,
            String componentClass, String activityClass, String permittedClass, String action,
            boolean extra, boolean hostLoader) {
        return isHostPackage(host) && host.equals(intentPackage) && host.equals(componentPackage)
                && permittedClass != null && permittedClass.equals(componentClass)
                && permittedClass.equals(activityClass) && ACTION.equals(action)
                && extra && hostLoader;
    }
}
