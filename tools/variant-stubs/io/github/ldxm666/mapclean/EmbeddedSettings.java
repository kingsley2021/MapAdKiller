package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * Stub for the slim (免 root 内置) variant.
 *
 * The old entry point -- long-pressing 高德's "More Tools" cell -- is replaced by the
 * always-present floating orb, so the Activity-bridge machinery is not compiled in.
 * {@code FloatingHooks} owns the entry point instead.
 *
 * <b>{@link #enabledFor} deliberately returns true.</b> It is the gate
 * {@code mapclean.App.attachEmbedded} uses before attaching the host Application and
 * registering the framework service listener. Returning false would leave
 * {@code App.svc()} permanently null, and every switch in the orb's panel would silently
 * fail to persist -- the panel would look alive but write nothing.
 */
public final class EmbeddedSettings {
    private static final String TAG = "MapCleanEmbedded";

    private EmbeddedSettings() {}

    /**
     * The floating layer takes over the role this gate used to guard, so the gate is
     * always open.
     *
     * <p>It used to delegate to {@code EmbeddedSettingsPolicy.isHostPackage}, which made
     * service delivery depend on a vendor allow-list. That list did not name 腾讯, so
     * {@code mapclean.App.attachEmbedded} threw <i>before</i> its last line
     * ({@code registerListener}) and the whole process lost its delivery path: the orb's
     * panel read 只读·服务未连接 and every switch wrote nothing.
     *
     * <p>A gate that only guards the floating layer has no business asking which map is
     * hosting it -- {@code FloatingHooks.accept} already refuses any Activity whose
     * package is not the variant's own host. Keeping the allow-list out of this decision
     * is what stops the next vendor from being shipped dead.
     */
    public static boolean enabledFor(String pkg) {
        return true;
    }

    /** No standalone settings Activity in this variant; the orb is the UI. */
    public static boolean open(Activity activity) { return false; }

    /** The tab bar keeps its native long-press behaviour. */
    public static void bindEntry(View view, String pkg) { }

    /** Never consumes: the floating orb, not this bridge, owns the entry point. */
    public static boolean consumeEntryTouch(View view, MotionEvent event) { return false; }

    public static void install(Object module, Object param, String process) {
        Log.i(TAG, "embedded settings replaced by the floating entry");
    }
}
