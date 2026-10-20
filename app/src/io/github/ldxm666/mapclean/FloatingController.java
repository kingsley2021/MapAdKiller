package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Attaches the floating entry point to the map's own window.
 *
 * <h3>Why a decor-level overlay</h3>
 * The module cannot add a real window: an integrated LSPatch build has no
 * {@code SYSTEM_ALERT_WINDOW} grant, and asking the user for one would defeat the point of
 * an embedded build. Instead the layer is added to the Activity's content root, which keeps
 * it inside the app's own window -- no extra permission, and it disappears together with the
 * map, which is exactly the "inside the app" behaviour we want.
 *
 * <h3>Attach timing</h3>
 * The map's root is built late and rebuilt on theme changes, so a single
 * {@code onResume} hook is not enough. The controller watches for the first moment the
 * content view has a parent with non-zero size, then inserts exactly one layer per Activity.
 * A {@link WeakHashMap} keyed by root view prevents duplicates across rebuilds.
 */
public final class FloatingController {

    private static final String TAG = "MapCleanFloat";

    /** Root views we already decorated. Weak so a rebuilt tree can be re-decorated. */
    private static final Set<ViewGroup> attached = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<ViewGroup, Boolean>()));

    /** Live layers, so a theme change can retint them without rebuilding. */
    private static final Set<Layer> layers = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Layer, Boolean>()));

    /**
     * Every Activity the lifecycle hooks accepted, so "turn it back on" can re-attach at once
     * instead of waiting for the next resume. Weak: a finished Activity must not be kept alive.
     */
    private static final Set<Activity> hosts = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));

    /**
     * The 悬浮窗 switch, resolved from storage on first use.
     *
     * <p>{@code null} means "not read yet". It is a tri-state on purpose: {@link #setEnabled}
     * must be able to pin the value without the lazy read overwriting it on the next attach.
     */
    private static volatile Boolean enabled;

    /** Set by {@link #setEnabled(boolean)} when turning on, so the ball shows up right away. */
    private static volatile boolean revealOnAttach;

    private FloatingController() {}

    /** A ball + panel pair living inside one Activity. */
    private static final class Layer {
        final Activity activity;
        final FloatingBall ball;
        final FloatingPanel panel;
        final FrameLayout host;
        /** The Activity content root the host lives in: the coordinate space the orb parks in. */
        final ViewGroup root;
        boolean dark;
        /**
         * True while the host covers the whole content root because the sheet is open.
         *
         * <p>The host is orb-sized the rest of the time (see {@code attach}): a full-screen
         * transparent view above 高德's map SurfaceView changes the composition path and was
         * measured as a real frame-time regression.
         */
        boolean expanded;

        Layer(Activity activity, FloatingBall ball, FloatingPanel panel, FrameLayout host,
              ViewGroup root, boolean dark) {
            this.activity = activity;
            this.ball = ball;
            this.panel = panel;
            this.host = host;
            this.root = root;
            this.dark = dark;
        }
    }

    /**
     * Turn the overlay on or off, and make the change visible immediately.
     *
     * <p>Off: every live layer is removed from its Activity's content root on the UI thread, so
     * the orb and the sheet are gone before the click that asked for it returns. On: the
     * Activities the lifecycle hooks have seen are re-attached right away, with the first
     * auto-hide skipped so the user actually sees the orb come back rather than a thin tab.
     *
     * <p>Idempotent: {@link #attach} keeps its own {@code attached} set, so a redundant
     * {@code setEnabled(true)} cannot add a second orb.
     */
    public static void setEnabled(boolean value) {
        // 双保险：模块模式下永远不进入「挂载」分支（关闭分支照常执行，用于清理残留）。
        if (value) {
            try {
                if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) {
                    Log.i(TAG, "event=floating_skipped reason=module_mode via=setEnabled");
                    return;
                }
            } catch (Throwable ignored) {
                return;
            }
        }
        enabled = Boolean.valueOf(value);
        if (value) {
            revealOnAttach = true;
            reattach();
        } else {
            detachAll();
        }
        Log.i(TAG, "floating " + (value ? "enabled" : "disabled"));
    }

    /**
     * The persisted switch, loaded once from {@link FloatingConfig}.
     *
     * <p>Read lazily rather than at class-init: the controller is first touched from
     * {@code Application.onCreate}, which can be before the module's preferences are reachable.
     */
    public static boolean isEnabled() {
        Boolean current = enabled;
        if (current == null) {
            boolean loaded;
            try {
                loaded = FloatingConfig.floatEnabled();
            } catch (Throwable t) {
                // Cannot read the switch -> keep the orb. Losing the only entry point in the
                // 免 root build is far worse than an unwanted orb.
                loaded = true;
            }
            current = Boolean.valueOf(loaded);
            enabled = current;
        }
        return current.booleanValue();
    }

    /** Remove every live layer; safe from any thread. */
    private static void detachAll() {
        final Layer[] snapshot;
        synchronized (layers) {
            snapshot = layers.toArray(new Layer[0]);
            layers.clear();
        }
        for (final Layer layer : snapshot) {
            Runnable task = new Runnable() {
                @Override public void run() {
                    FrameLayout host = layer.host;
                    try {
                        if (host.getParent() instanceof ViewGroup) {
                            ViewGroup parent = (ViewGroup) host.getParent();
                            parent.removeView(host);
                            attached.remove(parent);
                        }
                        // Drop the ball and the sheet too, so nothing keeps a callback alive.
                        host.removeAllViews();
                    } catch (Throwable t) {
                        Log.w(TAG, "detach failed: " + t.getClass().getName());
                    }
                }
            };
            // Activity#runOnUiThread, not View#post: a detached View's post() is queued until it
            // is attached again, which never happens once we are removing it.
            try {
                layer.activity.runOnUiThread(task);
            } catch (Throwable t) {
                task.run();
            }
        }
        if (snapshot.length > 0) Log.i(TAG, "floating layers removed: " + snapshot.length);
    }

    /** Re-attach to every Activity we know about; safe from any thread. */
    private static void reattach() {
        final Activity[] snapshot;
        synchronized (hosts) {
            snapshot = hosts.toArray(new Activity[0]);
        }
        if (snapshot.length == 0) {
            // Diagnostic: "turn it back on" with nothing to attach to looks like a dead switch.
            // FloatingHooks.remember() is what keeps this list populated while the overlay is off.
            Log.i(TAG, "reattach: no remembered activities yet");
        }
        for (final Activity activity : snapshot) {
            if (activity == null) continue;
            try {
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        attach(activity);
                        reveal(activity);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "reattach failed: " + t.getClass().getName());
            }
        }
    }

    /** Pull every orb of this Activity back into view (it may be parked as a grab tab). */
    private static void reveal(Activity activity) {
        synchronized (layers) {
            for (Layer layer : layers) {
                if (layer.activity != activity) continue;
                try {
                    int spaceW = layer.root.getWidth();
                    if (spaceW > 0) layer.ball.reveal(spaceW);
                } catch (Throwable t) {
                    // best effort only
                }
            }
        }
    }

    /**
     * Record an Activity as a window the overlay may use, without attaching anything.
     *
     * <p>{@link #attach} already does this, but it is only reached once the Activity owns a
     * visible window. With the overlay switched <b>off</b> that never happens, so
     * {@code hosts} stayed empty -- and {@link #setEnabled(boolean)} then had nothing to
     * re-attach to, which makes "turn the floating window back on" look broken until the next
     * cold start. Recording here is cheap and has no side effects.
     */
    static void remember(Activity activity) {
        if (activity != null) hosts.add(activity);
    }

    /**
     * The layer of one Activity, or null.
     *
     * <p>Named {@code layerOf} rather than {@code find} because a sibling change on this file
     * removed a same-purpose helper; keeping the name distinct avoids a silent duplicate.
     */
    private static Layer layerOf(Activity activity) {
        if (activity == null) return null;
        synchronized (layers) {
            for (Layer layer : layers) {
                if (layer.activity == activity) return layer;
            }
        }
        return null;
    }

    /**
     * Grow the host to cover the content root, or shrink it back to the orb's footprint.
     *
     * <p>The sheet is a MATCH_PARENT child of the host and needs the whole window (plus the
     * scrim behind it), so the host is only full-screen for as long as the sheet is up. This
     * is the compromise the composition measurement asked for: the map keeps its SurfaceView
     * path except while the user is actually looking at the sheet.
     */
    private static void setExpanded(final Layer layer, boolean expanded) {
        if (layer.expanded == expanded) return;
        layer.expanded = expanded;
        ViewGroup.LayoutParams lp = layer.host.getLayoutParams();
        if (lp == null) return;
        lp.width = expanded ? ViewGroup.LayoutParams.MATCH_PARENT
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = expanded ? ViewGroup.LayoutParams.MATCH_PARENT
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        layer.host.setLayoutParams(lp);
        if (expanded) {
            // Pinned to the root's origin; the orb is placed inside it from now on.
            layer.host.setX(0f);
            layer.host.setY(0f);
        }
        // Re-seat the orb so it stays exactly where it was on screen in both modes.
        layer.ball.moveTo(layer.ball.centerX(), layer.ball.centerY(), layer.root.getWidth());
        // Log the requested layout params, not the current width: the layout pass for this
        // change has not run yet, so getWidth() would still report the previous mode.
        Log.i(TAG, "host expanded=" + expanded
                + " lp=" + lp.width + "x" + lp.height
                + " space=" + layer.root.getWidth() + "x" + layer.root.getHeight());
    }

    /**
     * Open the sheet, growing the host first.
     *
     * <p>Every entry point goes through here: the orb tap, {@link #openPanelNear}, and the
     * rescue path. Opening the sheet while the host is still orb-sized would measure the
     * MATCH_PARENT card against a 62dp parent for one frame.
     */
    private static void openSheet(final Layer layer) {
        if (layer == null) return;
        setExpanded(layer, true);
        layer.host.requestLayout();
        try {
            layer.panel.open();
        } catch (Throwable t) {
            Log.w(TAG, "openSheet failed: " + t.getClass().getName());
        }
    }

    /**
     * Open the sheet of whichever layer lives in the same window as {@code anyViewInWindow}.
     *
     * <p>This is the Activity-free entry: the layer's host is a child of the Activity's content
     * root, so {@code host.getRootView()} is the same object as the pressed tab's
     * {@code getRootView()}. Matching on that identity sidesteps the whole "which Activity owns
     * the window" problem (on 17.02 the resumed Activity and the window owner are different).
     */
    public static boolean openPanelNear(View anyViewInWindow) {
        if (anyViewInWindow == null) return false;
        final View root = anyViewInWindow.getRootView();
        if (root == null || !root.isAttachedToWindow()) return false;
        Layer target = null;
        synchronized (layers) {
            for (Layer layer : layers) {
                if (layer == null || layer.host == null) continue;
                if (layer.host.getRootView() == root) { target = layer; break; }
            }
            if (target == null) {
                // Fallback: the only attached layer (single-window builds).
                for (Layer layer : layers) {
                    if (layer != null && layer.host != null && layer.host.isAttachedToWindow()) {
                        target = layer;
                        break;
                    }
                }
            }
        }
        if (target == null) {
            // Nothing is attached to this window yet. That is the normal state when the
            // lifecycle hooks could not attach (no Activity of ours owns a visible window
            // early on), so use the view that is definitely in a real window to find its
            // owner and attach there. Without this the orb can only be opened from a code
            // path that already holds an Activity.
            final Activity owner = activityOf(anyViewInWindow);
            if (owner == null) return false;
            Runnable rescue = new Runnable() {
                @Override public void run() {
                    attach(owner);
                    Layer created = layerOf(owner);
                    if (created == null) return;
                    try {
                        openSheet(created);
                    } catch (Throwable t) {
                        Log.w(TAG, "openPanelNear failed: " + t.getClass().getName());
                    }
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                rescue.run();
                return layerOf(owner) != null;
            }
            anyViewInWindow.post(rescue);
            return true;
        }
        final Layer open = target;
        open.host.post(new Runnable() {
            @Override public void run() {
                try {
                    openSheet(open);
                } catch (Throwable t) {
                    Log.w(TAG, "openPanelNear failed: " + t.getClass().getName());
                }
            }
        });
        return true;
    }
    /**
     * Insert the layer into an Activity if it is not there yet.
     *
     * Safe to call repeatedly (onResume, onConfigurationChanged, after a layout pass); the
     * {@code attached} set makes it idempotent.
     */
    public static void attach(final Activity activity) {
        if (activity == null) return;
        // 双保险：模块模式（root/LSPosed）永不挂载悬浮层，即使有人从别处调进来。
        try {
            if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) return;
        } catch (Throwable ignored) {
            return;
        }
        // Record the Activity before the switch is consulted: when the user turns the overlay
        // back on, reattach() needs to know which windows to put it into, and by then the
        // switch has been off for the whole session.
        hosts.add(activity);
        if (!isEnabled() || activity.isFinishing()) return;
        final ViewGroup root = contentRoot(activity);
        if (root == null) {
            Log.w(TAG, "no content root for " + activity.getClass().getName());
            return;
        }
        // A root that is not attached to a window, or has no size, belongs to an Activity
        // that is not being composited. 高德 17.02 keeps NewMapActivity in exactly that
        // state: attaching there put the orb into a tree nobody draws, which is why the user
        // saw no floating ball at all. Give up on this Activity instead of retrying -- the
        // retry used to loop on a root that never gains a size -- and let the next resume
        // (the Activity that really owns the visible window) do the attach.
        if (!root.isAttachedToWindow() || root.getWidth() <= 0 || root.getHeight() <= 0) {
            Log.i(TAG, "skip attach, no visible root: " + activity.getClass().getSimpleName());
            return;
        }
        if (!attached.add(root)) return;

        try {
            boolean dark = isDark(activity);
            final FloatingPanel panel = new FloatingPanel(activity, dark);
            panel.setVisibility(View.GONE);

            final FloatingBall ball = new FloatingBall(activity);
            // 自绘 View 不在无障碍树里；给个描述既利于读屏，也方便自动化验证时定位这颗球。
            try { ball.setContentDescription("MapAdKiller 设置"); } catch (Throwable ignored) { }
            applyPalette(ball, dark);
            // The 外观 page previews size/opacity on this very orb, so the sheet needs the
            // reference; the ball reads the persisted values in its own constructor.
            panel.setBall(ball);

            // ---- host: only as big as the orb ------------------------------------
            // It used to be MATCH_PARENT, i.e. a full-screen transparent FrameLayout on top
            // of the map. 高德 draws the map in its own SurfaceView, and a full-screen view
            // above it changes the composition path -- measured p99 frame gap 82.77 ms with
            // 6.1% dropped frames while every rendering stage stayed under 10 ms. So the host
            // wraps the orb and is grown to full size only while the sheet is open (a modal
            // sheet plus its scrim needs the whole window).
            final FrameLayout host = new FrameLayout(activity);
            host.setClipChildren(false);
            host.setClipToPadding(false);
            // Nothing to draw, no hardware layer, and never a touch target of its own: a
            // WRAP_CONTENT host that painted or intercepted would keep adding per-frame work,
            // which is the exact cost this change removes. FrameLayout's default
            // onInterceptTouchEvent already returns false, so only the orb is clickable.
            host.setBackground(null);
            host.setWillNotDraw(true);
            host.setLayerType(View.LAYER_TYPE_NONE, null);
            host.setClickable(false);
            host.setFocusable(false);

            host.addView(panel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            host.addView(ball, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            host.setVisibility(View.VISIBLE);

            final Layer layer = new Layer(activity, ball, panel, host, root, dark);

            // The orb keeps its centre in content-root coordinates; this turns that centre
            // into a position. Wrapped: the host travels with the orb. Expanded: the host is
            // pinned at (0,0) and the orb moves inside it.
            ball.setFrame(new FloatingBall.Frame() {
                @Override public void place(float cx, float cy) {
                    if (layer.expanded) {
                        ball.setX(cx - ball.getWidth() / 2f);
                        ball.setY(cy - ball.getHeight() / 2f);
                    } else {
                        // The host wraps the orb, so host size == orb size; using the orb's own
                        // width keeps this independent of a layout pass that may not have run
                        // yet after a resize.
                        ball.setX(0f);
                        ball.setY(0f);
                        host.setX(cx - ball.getWidth() / 2f);
                        host.setY(cy - ball.getHeight() / 2f);
                    }
                }
                @Override public int spaceWidth() { return root.getWidth(); }
                @Override public int spaceHeight() { return root.getHeight(); }
            });

            panel.setOnClose(new Runnable() {
                @Override public void run() {
                    // Returning from the sheet re-parks the orb instead of leaving it out. The
                    // delay lets the close animation finish before the host shrinks -- shrinking
                    // first would resize the sheet mid-animation.
                    ball.postDelayed(new Runnable() {
                        @Override public void run() {
                            // Re-opened during the close animation: shrinking now would resize
                            // the sheet the user is looking at.
                            if (layer.panel.isOpen()) return;
                            setExpanded(layer, false);
                            ball.moveTo(ball.centerX(), ball.centerY(), root.getWidth());
                            if (!ball.isHidden()) ball.hide(root.getWidth());
                        }
                    }, 260L);
                }
            });
            ball.setListener(new FloatingBall.Listener() {
                @Override public void onTap() {
                    openSheet(layer);
                }
                @Override public void onMoved(int left, int top) {
                    // Persist nothing: the resting edge is recomputed on every settle, and a
                    // stale position would fight a rotation or a split-screen resize.
                }
            });

            root.addView(host, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            layers.add(layer);

            // A rotation or a split-screen resize moves the edges. The signal must be the
            // content root, not the host: the host is orb-sized now and would also fire when
            // the sheet expands it.
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right,
                        int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    int w = right - left, h = bottom - top;
                    if (w <= 0 || h <= 0) return;
                    if (w == oldRight - oldLeft && h == oldBottom - oldTop) return;
                    if (!ball.isParked()) return;
                    ball.settle(false, w, h);
                    if (!panel.isOpen()) ball.hide(w);
                }
            });

            // One line so a verification run can prove the host is no longer full screen.
            host.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right,
                        int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (host.getWidth() <= 0) return;
                    host.removeOnLayoutChangeListener(this);
                    Log.i(TAG, "floating host size=" + host.getWidth() + "x" + host.getHeight()
                            + " attached=" + host.isAttachedToWindow()
                            + " space=" + root.getWidth() + "x" + root.getHeight());
                }
            });

            // Park it once the real size is known, then auto-hide -- unless this attach is the
            // direct result of the user switching the overlay back on. In that case the orb
            // stays fully visible: an orb that hides 1.4 s after "已开启" reads as "still off".
            final boolean keepVisible = revealOnAttach;
            revealOnAttach = false;
            host.post(new Runnable() {
                @Override public void run() {
                    // The coordinate space is the content root, not the (orb-sized) host.
                    int spaceW = root.getWidth(), spaceH = root.getHeight();
                    if (spaceW <= 0 || spaceH <= 0) return;
                    // Position via moveTo(): setX/setY would leave the orb's internal centre at
                    // (0,0) and settle() would then snap it into the top-left corner.
                    ball.moveTo(spaceW - ball.getWidth() / 2f - UiKit.dp(activity, 4),
                            spaceH * 0.42f, spaceW);
                    ball.settle(false, spaceW, spaceH);
                    if (keepVisible) return;
                    ball.postDelayed(new Runnable() {
                        @Override public void run() { ball.hide(root.getWidth()); }
                    }, 1400L);
                }
            });
            Log.i(TAG, "floating layer attached to " + activity.getClass().getName());
        } catch (Throwable t) {
            attached.remove(root);
            Log.w(TAG, "floating layer failed: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** Retint every live layer; called when the host flips between light and dark. */
    public static void refreshTheme(Activity activity) {
        boolean dark = isDark(activity);
        synchronized (layers) {
            for (Layer layer : layers) {
                if (layer.activity != activity || layer.dark == dark) continue;
                layer.dark = dark;
                applyPalette(layer.ball, dark);
            }
        }
    }

    private static void applyPalette(FloatingBall ball, boolean dark) {
        ball.setPalette(
                dark ? 0xF21D2027 : 0xF7FFFFFF,
                dark ? 0xFF7AB1FF : 0xFF0A6CF5,
                dark ? 0xFF9CC3FF : 0xFF0A6CF5);
    }

    /**
     * The Activity that owns the window {@code anyViewInWindow} lives in, or null.
     *
     * <p>Same unwrap loop {@code EmbeddedSettings.open} uses: a decor view's context is
     * usually a {@code ContextThemeWrapper} around the Activity, not the Activity itself.
     * Bounded so a pathological context chain cannot spin.
     */
    private static Activity activityOf(View anyViewInWindow) {
        try {
            android.content.Context owner = anyViewInWindow.getContext();
            for (int depth = 0; depth < 16 && owner != null; depth++) {
                if (owner instanceof Activity) return (Activity) owner;
                if (!(owner instanceof android.content.ContextWrapper)) break;
                android.content.Context base =
                        ((android.content.ContextWrapper) owner).getBaseContext();
                if (base == owner) break;
                owner = base;
            }
        } catch (Throwable t) {
            Log.w(TAG, "activity lookup failed: " + t.getClass().getName());
        }
        return null;
    }

    /** The FrameLayout the Activity's content was inflated into. */
    private static ViewGroup contentRoot(Activity activity) {
        try {
            // android.R.id.content is the container the Activity's layout is inflated into.
            // Adding the overlay there keeps it above the map but below dialogs, which is
            // exactly the layer an in-app entry point belongs in.
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof ViewGroup) return (ViewGroup) content;
            View decor = activity.getWindow().getDecorView();
            if (decor instanceof ViewGroup) return (ViewGroup) decor;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isDark(Activity activity) {
        try {
            int mask = activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return mask == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }
}
