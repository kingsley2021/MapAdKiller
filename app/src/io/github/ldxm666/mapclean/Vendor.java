package io.github.ldxm666.mapclean;

/**
 * Which map app the orb is currently running inside.
 *
 * <p>The floating orb lives in the <b>host</b> process, so it always knows which map it is
 * drawn over. That one fact decides everything vendor-specific: which switch catalogue to
 * show, and which configuration group to read and write.
 *
 * <p>Two ways to learn it, because the two matter at different times:
 * <ul>
 *   <li>{@link #host(String)} — the framework hands us the package name at install time,
 *       before any Activity exists;</li>
 *   <li>{@link #from(android.content.Context)} — the panel's own context, which is available
 *       even if the install callback somehow did not run.</li>
 * </ul>
 * Unknown host falls back to 高德, which is the variant that has always existed.
 */
public final class Vendor {

    public static final String AMAP = "com.autonavi.minimap";
    public static final String BAIDU = "com.baidu.BaiduMap";
    public static final String TMAP = "com.tencent.map";

    private static volatile String host;

    private Vendor() {}

    public static void host(String pkg) {
        if (pkg != null && pkg.length() > 0) host = pkg;
    }

    public static String host() { return host; }

    /** True when the orb is running inside 百度地图. */
    public static boolean baidu() { return BAIDU.equals(host); }

    /** True when the orb is running inside 腾讯地图. */
    public static boolean tmap() { return TMAP.equals(host); }

    /** Same question, answered from a live context; also records the answer. */
    public static boolean from(android.content.Context context) {
        if (context == null) return baidu();
        String pkg = context.getPackageName();
        host(pkg);
        return BAIDU.equals(pkg);
    }
}
