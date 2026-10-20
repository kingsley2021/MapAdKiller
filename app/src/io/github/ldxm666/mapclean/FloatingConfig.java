package io.github.ldxm666.mapclean;

import io.github.libxposed.service.XposedService;

/**
 * Read/write access to the hook-side configuration from inside the host (map) process.
 *
 * <h3>Why two paths</h3>
 * In a normal LSPosed install the module's own App process holds the writable
 * {@code RemotePreferences}, and the hook process only gets a read-only view. In an LSPatch
 * <i>integrated</i> build there is no separate module process at all: the patcher hands the
 * host a writable Binder, which arrives as an {@link XposedService} via
 * {@code XposedServiceHelper}.
 *
 * <p>So writes try, in order:
 * <ol>
 *   <li>the embedded service (present in the integrated build),</li>
 *   <li>the hook-side preferences (writable in frameworks that expose it).</li>
 * </ol>
 * Reads always use the same snapshot the hooks read, so the panel can never disagree with
 * what the hooks actually enforce.
 *
 * <h3>Which vendor</h3>
 * Decided by {@link Vendor}: the orb is drawn inside the host, so it can only ever be
 * configuring the map it is running in. The per-vendor details (group name, default for a
 * given key, whether a write needs pushing into a live view tree) live in {@link AmapPanel},
 * {@link BaiduPanel} and {@link TmapPanel}.
 */
public final class FloatingConfig {

    /**
     * The 悬浮窗 master switch: true = the orb and its sheet are attached, false = no overlay.
     *
     * <p>Deliberately <b>not</b> in {@code mapadkiller.Config} / {@code bmapclean.Spec}: this
     * switch belongs to {@link FloatingController}, which every variant keeps, and putting it
     * here means all three vendors read the same key through the same {@link #read} /
     * {@link #write} pair without touching a vendor catalogue.
     *
     * <p>Default is <b>on</b>: an unset key must keep the orb, because in the 免 root build the
     * orb is the only way into the settings sheet. All three vendor default tables fall through
     * to {@code true} for an unknown key, so no per-vendor edit is needed -- see the note on
     * {@link #floatEnabled()}.
     */
    public static final String K_FLOAT_ENABLED = "float_enabled";

    /* ---------------------------------------------------------------- 悬浮窗外观 */

    /**
     * Appearance of the orb and the sheet, adjustable from the sheet's 外观 page.
     *
     * <h3>Why these are ints, and why they need their own channel</h3>
     * {@link #read(String)} / {@link #write(String, boolean)} are the <b>switch</b> channel:
     * every vendor panel resolves a missing boolean through its own {@code defaultVisible}
     * table, which is exactly right for on/off rows and useless for a size. These four keys
     * are therefore stored as ints and read through {@link #readInt} / {@link #writeInt},
     * which never touch that table.
     *
     * <h3>Defaults are the pre-2.2.2 behaviour</h3>
     * An unset key must reproduce what shipped before this page existed: a
     * {@link #BALL_SIZE_DEFAULT} orb, fully opaque, and a sheet that spans the screen. A user
     * who never opens the page must not see the orb change after upgrading.
     */
    public static final String K_FLOAT_BALL_SIZE = "float_ball_size";
    public static final String K_FLOAT_BALL_ALPHA = "float_ball_alpha";
    public static final String K_FLOAT_PANEL_WIDTH = "float_panel_width";
    public static final String K_FLOAT_PANEL_ALPHA = "float_panel_alpha";

    /** Orb diameter in dp. 36dp still leaves a 52dp tap target with the 8dp pad. */
    public static final int BALL_SIZE_MIN = 36, BALL_SIZE_MAX = 72, BALL_SIZE_DEFAULT = 46;
    /** Orb opacity in percent. Below ~30% the parked grab tab stops reading as a control. */
    public static final int BALL_ALPHA_MIN = 30, BALL_ALPHA_MAX = 100, BALL_ALPHA_DEFAULT = 100;
    /** Sheet width in dp; {@code 0} is the "自动" stop = full width, the pre-2.2.2 look. */
    public static final int PANEL_WIDTH_AUTO = 0;
    public static final int PANEL_WIDTH_MIN = 280, PANEL_WIDTH_MAX = 420, PANEL_WIDTH_DEFAULT = 0;
    /** Sheet plate opacity in percent; the scrim behind it is deliberately not affected. */
    public static final int PANEL_ALPHA_MIN = 60, PANEL_ALPHA_MAX = 100, PANEL_ALPHA_DEFAULT = 100;

    /**
     * Preference group for the appearance keys on the vendors whose panel has no int channel
     * (百度 / 腾讯). 高德 keeps them in its own {@code amap_enhancer_config} group through
     * {@link AmapPanel}, i.e. the same file its switch rows already write.
     *
     * <p><b>未确认</b>: this path is untested on a 百度 / 腾讯 device -- this build only ships 高德.
     */
    private static final String FLOAT_GROUP = "mapclean_float";

    private FloatingConfig() {}

    /** Orb diameter in dp, clamped to the range the sheet can show. */
    public static int ballSizeDp() {
        return clamp(readInt(K_FLOAT_BALL_SIZE, BALL_SIZE_DEFAULT), BALL_SIZE_MIN, BALL_SIZE_MAX);
    }

    /** Orb opacity in percent, clamped. */
    public static int ballAlphaPercent() {
        return clamp(readInt(K_FLOAT_BALL_ALPHA, BALL_ALPHA_DEFAULT),
                BALL_ALPHA_MIN, BALL_ALPHA_MAX);
    }

    /** Sheet width in dp; {@link #PANEL_WIDTH_AUTO} means "follow the screen". */
    public static int panelWidthDp() {
        int stored = readInt(K_FLOAT_PANEL_WIDTH, PANEL_WIDTH_DEFAULT);
        if (stored <= PANEL_WIDTH_AUTO) return PANEL_WIDTH_AUTO;
        return clamp(stored, PANEL_WIDTH_MIN, PANEL_WIDTH_MAX);
    }

    /** Sheet plate opacity in percent, clamped. */
    public static int panelAlphaPercent() {
        return clamp(readInt(K_FLOAT_PANEL_ALPHA, PANEL_ALPHA_DEFAULT),
                PANEL_ALPHA_MIN, PANEL_ALPHA_MAX);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * Read one int-valued key.
     *
     * <p>Deliberately <b>not</b> routed through {@link #read(String, boolean)}: that one
     * answers "is this switch on" and would turn a stored {@code 46} into {@code true}.
     * Every failure path returns {@code fallback}, because a preferences read that throws
     * (service not bound yet, group missing) must degrade to the default orb rather than
     * crash {@code FloatingBall}'s constructor.
     */
    public static int readInt(String key, int fallback) {
        try {
            if (Vendor.tmap() || Vendor.baidu()) return readIntViaService(key, fallback);
            return AmapPanel.readInt(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Persist one int-valued key. {@code commit()}, not {@code apply()}: the user can leave
     * the map the instant the finger lifts off the slider.
     *
     * <p>Unlike the switch channel this does <b>not</b> run a vendor "apply" step: no hook
     * reads these keys, so pushing them into the live view tree would only cost a native
     * refresh on every slider release.
     */
    public static boolean writeInt(String key, int value) {
        try {
            if (Vendor.tmap() || Vendor.baidu()) return writeIntViaService(key, value);
            return AmapPanel.writeInt(key, value);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int readIntViaService(String key, int fallback) {
        XposedService service = App.service();
        if (service == null) return fallback;
        return service.getRemotePreferences(FLOAT_GROUP).getInt(key, fallback);
    }

    private static boolean writeIntViaService(String key, int value) {
        XposedService service = App.service();
        if (service == null) return false;
        return service.getRemotePreferences(FLOAT_GROUP).edit().putInt(key, value).commit();
    }

    /**
     * The 悬浮窗 switch, read the way the controller reads it.
     *
     * <p>Verified per vendor: 高德 {@code Config.defaultVisible}, 百度
     * {@code Spec.defaultVisible} and 腾讯 {@code Spec.defaultVisible} all return {@code true}
     * for a key they do not know, so {@code float_enabled} defaults to on everywhere. The
     * {@code true} fallback below covers the "preferences not reachable yet" case, which must
     * also mean "keep the orb" rather than "silently lose the only entry point".
     */
    public static boolean floatEnabled() {
        try {
            return read(K_FLOAT_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** No writable path at all (neither the service nor the module's own preferences). */
    public static final int WRITE_NONE = 0;
    /** No framework service, but {@code Config} has a local backend -- writable in-process. */
    public static final int WRITE_PREFS = 1;
    /** The framework service is bound: writable in every build. */
    public static final int WRITE_SVC = 2;

    /**
     * Which write path is usable right now, for the panel's status pill.
     *
     * <p>Three states instead of a boolean: 「只读 · 服务未连接」 used to be shown even when the
     * in-process preferences were perfectly writable, which told the user their tap could not
     * work when it could (and the other way round).
     */
    public static int writePath() {
        try {
            if (Vendor.tmap() || Vendor.baidu()) {
                return App.service() != null ? WRITE_SVC : WRITE_NONE;
            }
            return AmapPanel.writePath();
        } catch (Throwable t) {
            return WRITE_NONE;
        }
    }

    /** True when a writable path is currently available. */
    public static boolean writable() {
        return writePath() > 0;
    }

    /** No backend at all (mirrors {@code Config.BACKEND_NONE}). */
    public static final int BACKEND_NONE = io.github.ldxm666.mapadkiller.Config.BACKEND_NONE;
    /** Local in-process backend: writable without any framework channel. */
    public static final int BACKEND_LOCAL = io.github.ldxm666.mapadkiller.Config.BACKEND_LOCAL;
    /** Framework remote-preferences backend (service bound). */
    public static final int BACKEND_REMOTE = io.github.ldxm666.mapadkiller.Config.BACKEND_REMOTE;

    /**
     * The configuration backend actually in use, after verification.
     *
     * <p>{@code NONE} is never reported on a static verdict alone -- the vendor panel probes it
     * with one real write first, because a stale NONE is exactly what told users 「只读」 while
     * the sheet was running inside the target app.
     */
    public static int backend() {
        try {
            if (Vendor.tmap() || Vendor.baidu()) {
                return App.service() != null ? BACKEND_REMOTE : BACKEND_NONE;
            }
            return AmapPanel.effectiveBackend();
        } catch (Throwable t) {
            return BACKEND_NONE;
        }
    }

    /** Nothing written yet in this process. */
    public static final int W_UNTRIED = 0;
    /** The last write landed. */
    public static final int W_OK = 1;
    /** The last write was refused (permissions / IO / read-only proxy). */
    public static final int W_REFUSED = 2;

    /**
     * Result of the last write attempt, so the header pill can stop claiming 「可写入」 while
     * every tap is being refused. Never polled -- it is set by the write itself.
     */
    public static int lastWriteState() {
        try {
            if (Vendor.tmap() || Vendor.baidu()) return App.service() != null ? W_UNTRIED : W_REFUSED;
            return AmapPanel.lastWriteState();
        } catch (Throwable t) {
            return W_UNTRIED;
        }
    }

    /** {@code svc_null} / {@code prefs_null} / {@code commit_false} / {@code io}. */
    public static String lastWriteReason() {
        try {
            return Vendor.tmap() || Vendor.baidu() ? null : AmapPanel.lastWriteReason();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean read(String key) {
        if (Vendor.tmap()) return TmapPanel.read(key);
        return Vendor.baidu() ? BaiduPanel.read(key) : AmapPanel.read(key);
    }

    public static boolean read(String key, boolean fallback) {
        try {
            if (Vendor.tmap()) return TmapPanel.read(key);
            return Vendor.baidu() ? BaiduPanel.read(key) : AmapPanel.read(key);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Read a switch whose "on" is the <b>literal</b> stored value rather than the visibility
     * table -- today only {@code labels_on}, whose consumer reads {@code Config.selected()}.
     *
     * <p>Without this the row would hydrate from {@link #read(String, boolean)}, which resolves
     * a missing key through {@code defaultVisible} and answers {@code true}: the switch would
     * show ON while the native label filter is actually off (its default).
     */
    public static boolean readStrict(String key) {
        try {
            if (Vendor.tmap() || Vendor.baidu()) return read(key, false);
            return AmapPanel.readStrict(key);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Persist one switch. The vendor panel uses {@code commit()} rather than {@code apply()}:
     * the user may leave the map immediately after flipping a switch, and an async write
     * would be lost.
     */
    public static boolean write(String key, boolean value) {
        boolean ok;
        if (Vendor.tmap()) ok = TmapPanel.write(key, value);
        else ok = Vendor.baidu() ? BaiduPanel.write(key, value) : AmapPanel.write(key, value);
        // The 悬浮窗 key is the one switch whose "apply" step is not a vendor action but the
        // controller itself: without this, flipping it in the sheet would only take effect on
        // the next resume, which reads as "the switch does nothing".
        //
        // Applied only when the write landed: the vendor panels' own readers cache the same
        // snapshot, so acting on a failed write would make the UI and the hook disagree.
        if (ok && K_FLOAT_ENABLED.equals(key)) {
            try {
                FloatingController.setEnabled(value);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "float toggle failed: " + t.getClass().getSimpleName());
            }
        }
        return ok;
    }

    /** Drop the cached snapshot after a write so the panel and the hooks agree at once. */
    public static void invalidate() {
        if (Vendor.tmap()) TmapPanel.invalidate();
        else if (Vendor.baidu()) BaiduPanel.invalidate();
        else AmapPanel.invalidate();
    }

    private static final String TAG = "MapCleanFloat";

    /** One line per process: this catch used to be silent, which hid a dead service for a release. */
    private static final java.util.concurrent.atomic.AtomicBoolean gateReported =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** One line per process the first time the panel finds no service at all. */
    private static final java.util.concurrent.atomic.AtomicBoolean svcNullReported =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Best-effort recovery before a write is reported as failed.
     *
     * <p>The service binds asynchronously, and the sheet can be built before it lands. Running
     * the attach path here registers the delivery listener (it is idempotent), so the next tap
     * succeeds instead of the user concluding the module is read-only.
     */
    public static void ensureServiceFrom(android.content.Context context) {
        if (context == null) return;
        try {
            android.content.Context app = context.getApplicationContext();
            if (app instanceof android.app.Application) ensureService((android.app.Application) app);
        } catch (Throwable ignored) {
            // Nothing to recover with; the caller falls back to the failure toast.
        }
    }

    /** One-shot diagnostic: the panel found neither the service nor in-process preferences. */
    public static void reportNoWritePath(String where) {
        if (svcNullReported.compareAndSet(false, true)) {
            android.util.Log.w(TAG, "event=svc_null where=" + where + " path=0");
        }
    }

    /**
     * The embedded service binds asynchronously, and it can be ready long before the user
     * ever opens the panel. Make sure the host Application is attached to the module's
     * static holders so {@code App.svc()} has somewhere to land.
     */
    public static void ensureService(android.app.Application application) {
        if (application == null) return;
        try {
            io.github.ldxm666.mapclean.App.attachEmbedded(application);
        } catch (Throwable t) {
            // Not an LSPatch host (plain LSPosed) -- the hook-side path still works.
            //
            // Never swallow this silently: {@code attachEmbedded} registers the framework
            // service listener on its last line, so an exception here means this process has
            // *no* delivery path at all -- {@code App.svc()} stays null and every vendor
            // holder's panel reads 只读. That is exactly how the 腾讯 build shipped with a
            // dead service while 百度 worked: the gate was a vendor allow-list that did not
            // name com.tencent.map. Logged once per process, with the stack, so the next
            // occurrence is a one-line diagnosis instead of a log hunt.
            if (gateReported.compareAndSet(false, true)) {
                android.util.Log.w(TAG, "embedded service gate closed pkg="
                        + application.getPackageName(), t);
            }
        }
    }
}
