package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Access to the module's own {@code res/} from inside a patched host.
 *
 * <h3>The problem</h3>
 * In an integrated (rootless) LSPatch build the module is an APK embedded at
 * {@code assets/lspatch/modules/} and loaded by its own class loader. Its resources are
 * <b>not</b> merged into the host, so every one of these returns nothing:
 *
 * <pre>
 *   host.getResources().getIdentifier("avatar_author", "drawable", hostPackage)   // 0
 *   host.getResources().getIdentifier("avatar_author", "drawable", modulePackage) // 0
 *   host.getApplicationInfo().loadIcon(...)   // the *host's* icon, not the module's
 * </pre>
 *
 * That last line is the trap: it silently succeeds and yields 高德's own launcher icon, so
 * the UI looks fine while showing the wrong picture entirely.
 *
 * <h3>The fix</h3>
 * Build a {@link Resources} over an {@link AssetManager} that has the module APK added as an
 * asset path, then resolve names against that. The module APK's location comes from this
 * class's own {@link java.security.CodeSource}, which is where its dex was actually loaded
 * from -- no guessing at cache directories.
 *
 * <p>Everything here is best-effort and cached: if the module resources cannot be reached
 * the caller falls back to a drawn monogram, which is preferable to showing the host's icon.
 */
public final class ModuleResources {

    private static final String TAG = "MapCleanFloat";
    /** The package the module APK's resources.arsc was built with. */
    private static final String MODULE_PACKAGE = "io.github.ldxm666.mapadkiller";

    private static volatile boolean attempted;
    private static volatile Resources resources;
    private static volatile String sourcePath;
    private static volatile String failure;

    private ModuleResources() {}

    /** The module's resources, or null when they could not be reached. */
    public static Resources get(Context host) {
        if (!attempted) {
            synchronized (ModuleResources.class) {
                if (!attempted) {
                    load(host);
                    attempted = true;
                }
            }
        }
        return resources;
    }

    /**
     * The path of the module APK this class was loaded from, or null when it cannot be located.
     *
     * <p>Exposed for callers that need the module's own files without going through the resource
     * table -- {@link DefaultConfig} reads {@code res/raw/default_config.json} straight out of the
     * zip when the resource lookup does not resolve. Uses the same cached lookup as
     * {@link #get(Context)}, and falls back to a context-free discovery so a caller that has no
     * {@code Context} at hand (the framework service callback) still gets an answer.
     */
    public static String moduleApkPath() {
        if (sourcePath == null) {
            String found = locateModuleApk();
            if (found != null) sourcePath = found;
        }
        return sourcePath;
    }

    private static void load(Context host) {
        sourcePath = locateModuleApk();
        if (sourcePath == null) {
            failure = "module apk path not found; candidates=" + candidates();
            Log.w(TAG, "avatar: " + failure);
            return;
        }
        try {
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object cookie = addAssetPath.invoke(assets, sourcePath);
            if (cookie instanceof Integer && (Integer) cookie == 0) {
                failure = "addAssetPath rejected " + sourcePath;
                Log.w(TAG, "avatar: " + failure);
                return;
            }
            Resources hostRes = host.getResources();
            resources = new Resources(assets, hostRes.getDisplayMetrics(),
                    hostRes.getConfiguration());
            Log.i(TAG, "avatar: module resources ready from " + sourcePath);
        } catch (Throwable t) {
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "avatar: module resources unavailable (" + failure + ")");
        }
    }

    /**
     * Where the module's dex was loaded from.
     *
     * <p>Tries, in order:
     * <ol>
     *   <li>this class's {@link java.security.CodeSource};</li>
     *   <li>the class loader description (LSPatch renders the dex path there);</li>
     *   <li>LSPatch's extraction directory under the host's cache, which is where the
     *       embedded module APK is unpacked before being loaded.</li>
     * </ol>
     */
    private static String locateModuleApk() {
        // 1) Code source.
        try {
            java.security.CodeSource cs = ModuleResources.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String path = cs.getLocation().getPath();
                if (path != null) {
                    path = java.net.URLDecoder.decode(path, "UTF-8");
                    if (path.endsWith(".apk") && new java.io.File(path).exists()) return path;
                }
            }
        } catch (Throwable ignored) { }

        // 2) Class loader description.
        try {
            String description = String.valueOf(ModuleResources.class.getClassLoader());
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([^\\s\\[\\]\":]+\\.apk)").matcher(description);
            while (m.find()) {
                String path = m.group(1);
                if (new java.io.File(path).exists()) return path;
            }
        } catch (Throwable ignored) { }

        // 3) LSPatch's extraction directory.
        for (String dir : new String[]{"cache/lspatch/modules", "cache/lspatch", "files/lspatch"}) {
            try {
                java.io.File folder = new java.io.File(
                        System.getProperty("java.io.tmpdir", "/data/local/tmp"));
                // java.io.tmpdir is not the app dir; build from the process's own data path.
                String dataDir = dataDir();
                if (dataDir == null) break;
                folder = new java.io.File(dataDir, dir);
                java.io.File[] files = folder.listFiles();
                if (files == null) continue;
                for (java.io.File f : files) {
                    if (f.getName().endsWith(".apk") && f.getName().contains("mapadkiller")) {
                        return f.getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** The app's data directory, read from the process's own environment. */
    private static String dataDir() {
        // /proc/self/cmdline gives the package; the data dir is /data/user/0/<pkg>.
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/cmdline"));
            String cmdline = reader.readLine();
            reader.close();
            if (cmdline != null && cmdline.length() > 0) {
                int nul = cmdline.indexOf(0);
                String pkg = nul > 0 ? cmdline.substring(0, nul) : cmdline;
                return "/data/user/0/" + pkg;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Candidate list, for the diagnostic line only. */
    private static String candidates() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("loader=").append(ModuleResources.class.getClassLoader());
        } catch (Throwable ignored) { }
        try {
            java.security.CodeSource cs = ModuleResources.class.getProtectionDomain().getCodeSource();
            sb.append(" codeSource=").append(cs == null ? "null" : cs.getLocation());
        } catch (Throwable ignored) { }
        sb.append(" dataDir=").append(dataDir());
        return sb.toString();
    }

    /** Load a drawable by name from the module's resources, or null. */
    public static Drawable drawable(Context host, String name) {
        Resources res = get(host);
        if (res == null) return null;
        try {
            int id = res.getIdentifier(name, "drawable", MODULE_PACKAGE);
            if (id == 0) id = res.getIdentifier(name, "drawable", null);
            if (id == 0) return null;
            return res.getDrawable(id, host.getTheme());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Diagnostic string for the one-shot log line. */
    public static String describe() {
        if (resources != null) return "ok path=" + sourcePath;
        return "failed (" + failure + ") path=" + sourcePath;
    }
}
