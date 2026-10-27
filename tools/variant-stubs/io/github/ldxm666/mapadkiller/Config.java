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

    /** 与真实 Config 同名同值：CaptureLog 的配置快照按这个前缀过滤工具槽位键。 */
    public static final String K_TOOL_PREFIX = "tool_";

    /** 内置版没有可读的配置后端，快照恒为空 —— CaptureLog 只会把它写进日志文本。 */
    public static java.util.Map<String, Object> snapshot() {
        return java.util.Collections.emptyMap();
    }
}