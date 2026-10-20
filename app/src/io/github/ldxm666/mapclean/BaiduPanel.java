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
        // One extra leading group: the 悬浮窗 master switch belongs to FloatingController, not
        // to 百度's own catalogue (Spec is the single source for the map's switches, and this
        // key is not one of them). Without it the orb could be switched off but never back on
        // from inside the sheet.
        PanelTypes.Group[] out = new PanelTypes.Group[cats.length + 1];
        out[0] = new PanelTypes.Group("悬浮窗", new PanelTypes.Item[]{
            });
        for (int i = 0; i < cats.length; i++) {
            Spec.Cat cat = cats[i];
            PanelTypes.Item[] items = new PanelTypes.Item[cat.rows.length];
            for (int j = 0; j < cat.rows.length; j++) {
                Spec.Row row = cat.rows[j];
                items[j] = new PanelTypes.Item(row.key, row.title, row.note);
            }
            // The category's own subtitle ("改动后重启百度生效" and friends) is shown as the
            // group's note, the same slot the 高德 tool catalogue uses for its 5-cell warning.
            out[i + 1] = new PanelTypes.Group(cat.title, items, cat.subtitle);
        }
        return out;
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
