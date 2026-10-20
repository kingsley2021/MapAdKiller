package io.github.ldxm666.mapclean;

/**
 * Stub for the 百度-only variant.
 *
 * <p>Mirror of the 百度 stub: the 高德 catalogue and its config group are not compiled in, but
 * {@link FloatingPanel} and {@link NativeLabelAssets} still name this class, so it has to exist
 * and answer safely.
 */
final class AmapPanel {

    private AmapPanel() {}

    static FloatingPanel.Group[] groups() { return new FloatingPanel.Group[0]; }

    static boolean writable() { return false; }

    static boolean read(String key) { return true; }

    static boolean write(String key, boolean value) { return false; }

    static void invalidate() { }

    static void apply(String key) { }

    /** Visible = do not touch the map; 高德's filter is not compiled into this variant. */
    static boolean mapPoiVisible() { return true; }
}
