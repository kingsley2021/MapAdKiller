package io.github.ldxm666.mapadkiller;

/**
 * Variant stub: the real {@code mapadkiller} package is dropped from every 免 root 内置版
 * (百度 / 腾讯), so the shared 悬浮窗 code cannot link against the real class.
 *
 * <p>{@link #isEmbedded()} must answer <b>true</b> here: a 免 root 内置版 *is* an embedded
 * build (the module is baked into the host APK), so the floating orb / panel is its only
 * settings surface and must stay enabled. Answering false would silently remove the orb
 * from 百度/腾讯 内置版.
 */
public final class HostInfo {
    private HostInfo() {}

    /** Always true for a 免 root 内置版. */
    public static boolean isEmbedded() {
        return true;
    }

    /** No-op: the real one reports the host signature/mode for diagnostics. */
    public static void reportOnce(String pkg) {
    }
}