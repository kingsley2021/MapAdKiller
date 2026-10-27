package io.github.ldxm666.mapadkiller;

/**
 * Variant stub: 百度 / 腾讯 内置版 drop the whole {@code mapadkiller} package (that is 高德's
 * adapter), but the shared 悬浮窗 config code names this class for its backend constants.
 *
 * <p>Only the three constants are required to compile; the vendor variants route every read and
 * write through their own {@code Cfg} instead, so nothing here is ever called.
 */
public final class Config {
    private Config() {}

    public static final int BACKEND_NONE = 0;
    public static final int BACKEND_LOCAL = 1;
    public static final int BACKEND_REMOTE = 2;
}