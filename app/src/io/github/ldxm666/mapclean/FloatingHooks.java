package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Installs the floating entry point inside an integrated (rootless) host.
 *
 * <h3>Where it hooks</h3>
 * {@link Application.ActivityLifecycleCallbacks} is registered through a hook on
 * {@code Application.onCreate}. Using the public lifecycle callbacks rather than hooking
 * each map Activity keeps this independent of 高德's obfuscated activity names, and it works
 * for every Activity the map opens -- the orb follows the user.
 *
 * <h3>Scope</h3>
 * The layer is only attached to the map's own main Activities, not to every Activity. Adding
 * it to a login screen or a web-view payment page would be noise, so the controller filters on
 * "does this Activity belong to the map package and is it not one of the lightweight helpers".
 */
public final class FloatingHooks {

    private static final String TAG = "MapCleanFloat";

    /** One 「module mode, floating skipped」 line per process (install can be called per package). */
    private static volatile boolean skippedLogged;

    /** One "I looked at this Activity class" line per class, so the log stays readable. */
    private static final java.util.Set<String> reported = java.util.Collections.synchronizedSet(
            new java.util.LinkedHashSet<String>());

    /**
     * Activities that must never receive the overlay.
     *
     * Note these are <b>class-name</b> prefixes, and 高德's own screens live under
     * {@code com.autonavi.map.*} -- not under the app id. Matching the app id here would
     * never exclude anything.
     *
     * <h3>SplashActivity is deliberately absent (2.2.2)</h3>
     * It used to be listed as "transient; the orb belongs on the map". Device evidence on
     * 高德 17.02.0.1541 showed the opposite: {@code NewMapActivity} was the resumed Activity
     * but had <b>no window at all</b> ({@code dumpsys window} listed only
     * {@code SplashActivity}), so the layer went into a content root that is never composited
     * and the orb was invisible everywhere. That exclusion was the direct cause of
     * 「悬浮球看不见」. The {@link #hasVisibleWindow} gate is the correct filter: attach to
     * whichever Activity actually owns a visible window, whatever it is called.
     * {@code HicarSplashActivity} (车机) stays excluded -- untested, and not the phone map UI.
     */
    private static final String[] EXCLUDED = {
            "com.autonavi.map.activity.HicarSplashActivity",
            "com.autonavi.minimap.wxapi",       // 微信回调
            "com.autonavi.minimap.ddshare",     // 钉钉分享
            "com.autonavi.minimap.basemap.errorback",
            "com.tencent.tauth",                // QQ 授权
            "com.tencent.connect",
            "com.alipay.sdk",
            "com.google.ar.core",
            "com.unionpay",
    };

    /**
     * How often a single Activity is re-checked for a visible window, and how far apart.
     *
     * <p>{@code onActivityResumed} can fire before the first layout pass, so one check is not
     * enough; but the old code retried forever through {@code root.post()}, which on an
     * Activity that never gets a window is an endless loop. Six tries over ~1.5 s covers the
     * cold-start gap and then gives up, leaving the next real resume to try again.
     */
    private static final int MAX_VISIBILITY_RETRIES = 6;
    private static final long VISIBILITY_RETRY_MS = 250L;

    /** Per-Activity retry counters; weak so a finished Activity is not kept alive. */
    private static final java.util.Map<Activity, Integer> retries =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Activity, Integer>());


    private FloatingHooks() {}

    /** Registers the lifecycle watcher. Called once per host process. */
    public static void install(XposedModule module, final String hostPackage, final boolean darkCapable) {
        // 模块模式（root / LSPosed 用户）**一条 hook 都不装**：不是装上再判断，而是根本不安装，
        // 连 ActivityLifecycleCallbacks 都不注册 ⇒ 零成本、零残留。
        // 悬浮窗是免 root 内置版专属功能；root 用户用模块 App 自己的设置页。
        if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) {
            if (!skippedLogged) {
                skippedLogged = true;
                Log.i(TAG, "event=floating_skipped reason=module_mode");
            }
            return;
        }
        try {
            module.hook(Application.class.getDeclaredMethod("onCreate"))
                    .setId("floating.lifecycle")
                    .intercept(new io.github.ldxm666.mapclean.HookGuard("floating.lifecycle",
                            new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object result = chain.proceed();
                                    if (chain.getThisObject() instanceof Application) {
                                        hook((Application) chain.getThisObject(), hostPackage);
                                    }
                                    return result;
                                }
                            }, new HookGuard.Reporter() {
                                @Override public void disabled(String id, Throwable error) {
                                    Log.w(TAG, "lifecycle hook disabled: " + error.getClass().getSimpleName());
                                }
                            }));
            Log.i(TAG, "floating hooks installed for " + hostPackage);
        } catch (Throwable t) {
            Log.w(TAG, "floating hooks unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static void hook(final Application application, final String hostPackage) {
        try {
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) {
                    if (!accept(activity, hostPackage)) return;
                    FloatingConfig.ensureService(application);
                }

                @Override public void onActivityStarted(Activity activity) {
                    if (!accept(activity, hostPackage)) return;
                    // The content tree may not exist during onCreate; retry on each start.
                    attachWhenVisible(activity);
                }

                @Override public void onActivityResumed(Activity activity) {
                    if (!accept(activity, hostPackage)) return;
                    attachWhenVisible(activity);
                    FloatingController.refreshTheme(activity);
                }

                @Override public void onActivityPaused(Activity activity) { }

                @Override public void onActivityStopped(Activity activity) { }

                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

                @Override public void onActivityDestroyed(Activity activity) { }
            });
            Log.i(TAG, "activity lifecycle callbacks registered");
        } catch (Throwable t) {
            Log.w(TAG, "register callbacks failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * True when this Activity owns a window that is really on screen.
     *
     * <p>This is the fix for the invisible orb. 高德 17.02 resumes {@code NewMapActivity} but
     * composites a different Activity's window; the old code only asked "is this our
     * package", so the layer was inserted into a content root that never gets drawn and the
     * user saw nothing. Every clause below is a separate way for that to happen:
     * finishing/destroyed, no window, decor not attached, window not {@code VISIBLE}
     * (covers {@code GONE}/{@code INVISIBLE} and a window that is still being added), and a
     * decor that has no measured size yet.
     *
     * <p>Never throws: a ROM that answers oddly must degrade to "not visible", not crash the
     * lifecycle callback.
     */
    private static boolean hasVisibleWindow(Activity a) {
        try {
            if (a == null || a.isFinishing() || a.isDestroyed()) return false;
            android.view.Window w = a.getWindow();
            if (w == null) return false;
            android.view.View decor = w.getDecorView();
            if (decor == null || !decor.isAttachedToWindow()) return false;
            // View#getWindowVisibility() is the window's own visibility flag (Window has no
            // such getter), so this catches GONE/INVISIBLE windows and ones still being added.
            if (decor.getWindowVisibility() != android.view.View.VISIBLE) return false;
            return decor.getWidth() > 0 && decor.getHeight() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Attach the layer once, and only to a window that is actually visible.
     *
     * <p>If the window is not up yet the check is repeated a bounded number of times
     * ({@link #MAX_VISIBILITY_RETRIES} × {@link #VISIBILITY_RETRY_MS}) instead of being
     * written off: {@code onActivityResumed} can legitimately run before the first layout
     * pass. Nothing is recorded as "done" while the window is missing, so a later resume of
     * any eligible Activity gets a fresh, full budget.
     */
    private static void attachWhenVisible(final Activity activity) {
        if (activity == null) return;
        // Record it even when the window is not up yet: turning the overlay back on has to know
        // which windows to put it into, and that can happen long after the last resume.
        FloatingController.remember(activity);
        if (hasVisibleWindow(activity)) {
            retries.remove(activity);
            FloatingController.attach(activity);
            return;
        }
        Integer seen = retries.get(activity);
        int attempt = seen == null ? 0 : seen.intValue();
        if (attempt >= MAX_VISIBILITY_RETRIES) return;
        retries.put(activity, Integer.valueOf(attempt + 1));
        android.view.View decor;
        try {
            decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        } catch (Throwable t) {
            decor = null;
        }
        if (decor == null) return;
        decor.postDelayed(new Runnable() {
            @Override public void run() { attachWhenVisible(activity); }
        }, VISIBILITY_RETRY_MS);
    }

    private static boolean accept(Activity activity, String hostPackage) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        // Match on the package the Activity *belongs to*, not on its class name.
        // 高德 declares its screens under com.autonavi.map.* (SplashActivity,
        // NewMapActivity, ...), so a class-name prefix test against the app id
        // "com.autonavi.minimap" rejected every Activity and the layer never attached.
        try {
            if (!hostPackage.equals(activity.getPackageName())) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        for (String prefix : EXCLUDED) {
            if (name.startsWith(prefix)) {
                if (reported.add(name)) Log.i(TAG, "skip excluded activity " + name);
                return false;
            }
        }
        if (reported.add(name)) Log.i(TAG, "eligible activity " + name);
        return true;
    }
}
