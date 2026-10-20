package io.github.ldxm666.mapclean;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Drives {@code libmapclean_labels.so}, which is what actually removes 兴趣点 from the map.
 *
 * <h3>What the native side does</h3>
 * Its 高德 host hooks {@code AAssetManager_open} and intercepts exactly one asset family --
 * {@code map_assets/style_*X_Main} -- decompresses the style protobuf, rewrites the POI
 * features to invisible with {@code style::amap()}, and serves the filtered bytes onwards.
 * Roads, water, terrain and place names are untouched, which is why this is the correct
 * mechanism for 「去地图标注」 and a global label switch is not.
 *
 * <h3>Two independent things must both happen</h3>
 * <ol>
 *   <li>The library must be loaded <b>through this class loader</b>, because JNI resolves
 *       {@code Java_…_configure} against the loader that declared the native method.</li>
 *   <li>The framework must call the library's {@code native_init(entries)} so the inline-hook
 *       primitive gets bound. {@code configure()} alone cannot install anything without it --
 *       the native side reports {@code accepted=0} in that case (this used to be masked by a
 *       {@code return true} on the unbound path, which is why 地图标注 looked "installed"
 *       while doing nothing).</li>
 * </ol>
 *
 * <h3>Where the library actually lives (measured on device, 2026-10-12)</h3>
 * In an integrated (rootless) LSPatch build the module is a nested APK at
 * {@code assets/lspatch/modules/…} which the patcher unpacks to the host's cache. Two facts
 * were established by comparing that extracted copy with the embedded one byte for byte:
 * <ul>
 *   <li>the patcher <b>drops the {@code lib/} level</b>: our
 *       {@code lib/arm64-v8a/libmapclean_labels.so} becomes
 *       {@code arm64-v8a/libmapclean_labels.so};</li>
 *   <li>the module class loader only ever searches {@code <module.apk>!/lib/<abi>} plus
 *       {@code java.library.path}, so {@code System.loadLibrary} can never succeed there.</li>
 * </ul>
 * The previous revision therefore always ended at
 * {@code no packaged abi matched [arm64-v8a, armeabi-v7a, armeabi]} and the switch silently
 * did nothing. Both layouts are now accepted, and the host's own extracted native library
 * directory is preferred when it is known (see {@link #start(String, String)}).
 */
public final class NativeLabelAssets {

    private static final String TAG = "MapCleanLabels";
    private static final String HOST = "com.autonavi.minimap";
    private static final String LIBRARY = "mapclean_labels";
    private static final String FILE = "lib" + LIBRARY + ".so";

    /** {@code hidden} argument handed to the native side: 1 hides POI features, 0 leaves them. */
    private static final int HIDE = 1;
    private static final int SHOW = 0;

    private NativeLabelAssets() {}

    private static native void configure(int map, int hiddenMask);

    private static native boolean configureBaidu(String[] originalPaths, String[] filteredPaths);

    /** Kept for the full build, where the Baidu host is compiled in. */
    public static boolean prepareBaidu(String[] originalPaths, String[] filteredPaths) {
        if (!load()) return false;
        try {
            return configureBaidu(originalPaths, filteredPaths);
        } catch (Throwable error) {
            Log.w(TAG, "native exact-file interface unavailable", error);
            return false;
        }
    }

    /**
     * Arm the label filter for one process, without a host {@code ApplicationInfo} yet.
     *
     * <p>Kept so the module entry point can call this by reflection at module-load time, when
     * the host Application does not exist. {@link #start(String, String)} re-runs it once the
     * host's native library directory is known.
     */
    public static void start(String process) {
        start(process, null);
    }

    /**
     * Arm the label filter for one process.
     *
     * @param libraryDir the host's {@code ApplicationInfo.nativeLibraryDir}, or null when it is
     *                   not known yet. The host APK ships an extra copy of the library inside
     *                   {@code lib/<abi>/} so the platform extracts it there and the framework's
     *                   own {@code native_init} lookup can find it by name; that directory is
     *                   also the cheapest place for this class to load it from.
     */
    public static void start(String process, String libraryDir) {
        if (!HOST.equals(process)) return;
        if (libraryDir != null && libraryDir.length() > 0) nativeDirectory = libraryDir;
        arm(true);
    }

    /**
     * Re-read the switch and hand the new state to the native side.
     *
     * <p>The panel and the hooks share one process in the integrated build, so a toggle can be
     * pushed straight into the running engine instead of waiting for a restart. The style
     * protobuf is only opened while the map is being set up, so an already-rendered map keeps
     * its current appearance until it is re-entered -- the panel says as much in its footer.
     */
    public static void refresh() {
        arm(false);
    }

    /** True when the library is in the process and the framework primitive has been bound. */
    public static boolean active() {
        return loaded && appliedMask >= 0;
    }

    private static void arm(boolean force) {
        // The POI switch lives in a different group per vendor (amap_enhancer_config vs
        // bmapclean_config), so the read goes through whichever panel this build ships.
        int mask = (Vendor.baidu() ? BaiduPanel.mapPoiVisible() : AmapPanel.mapPoiVisible())
                ? SHOW : HIDE;
        // Loading is best-effort and separate on purpose: configure() must still run when the
        // library is already mapped, and a failed load must not be allowed to skip arming.
        load();
        if (!loaded) {
            Log.w(TAG, "label filter inactive: library not loaded (poi hidden=" + (mask != SHOW) + ")");
            return;
        }
        askFramework();
        if (!force && mask == appliedMask) return;
        try {
            configure(1, mask);
            appliedMask = mask;
            Log.i(TAG, "label filter armed hide_poi=" + (mask != SHOW) + " from=" + loadedFrom);
        } catch (Throwable error) {
            Log.w(TAG, "native style interface unavailable", error);
        }
    }

    /**
     * Ask the framework to dlopen the library and call its {@code native_init}.
     *
     * <p>{@code configure()} on its own cannot install anything: the asset interception is
     * built on the framework's inline-hook primitive, and {@code native_init(entries)} is the
     * only place that primitive is handed over. A normal LSPosed install finds the module
     * library by name, but an integrated LSPatch build cannot -- the patcher repacks the
     * nested module APK, moving {@code lib/<abi>/x.so} to {@code <abi>/x.so}, and no
     * name-based search path covers that layout. Measured on device, the library was never
     * loaded anywhere and the switch did nothing, silently.
     *
     * <p>{@code org.matrix.vector.nativebridge.NativeAPI.recordNativeEntrypoint} is the
     * framework's own entry point -- it is what the module loader calls for every line of
     * {@code META-INF/xposed/native_init.list} -- so the same library can be handed over here
     * by absolute path, which needs no APK surgery. A repeated call for a library the
     * framework already handled is harmless; the native side keeps the first hook it is given.
     */
    private static void askFramework() {
        if (frameworkAsked) return;
        frameworkAsked = true;
        String path = loadedPath;
        if (path == null) return;             // found by name: the framework already did this
        try {
            Class<?> api = Class.forName("org.matrix.vector.nativebridge.NativeAPI");
            api.getMethod("recordNativeEntrypoint", String.class).invoke(null, path);
            Log.i(TAG, "framework asked to call native_init for " + path);
        } catch (Throwable error) {
            Log.i(TAG, "framework native entry point unavailable: " + error);
        }
    }

    /** Load the library once; true when it ended up in the process. */
    private static boolean load() {
        if (loaded) return true;
        synchronized (NativeLabelAssets.class) {
            if (loaded) return true;

            // 1) The linker's own search path. Works for a plain LSPosed install, where the
            //    library sits next to the module APK.
            try {
                System.loadLibrary(LIBRARY);
                loaded = true;
                loadedFrom = "linker";
                Log.i(TAG, "library loaded from the host native path");
                return true;
            } catch (Throwable ignored) {
                // Expected in an integrated build; fall through to the explicit paths.
            }

            // 2) The host's extracted native library directory. The build injects a copy of the
            //    library into the patched host APK's lib/<abi>/ for exactly this reason: the
            //    platform extracts it, the app's namespace can dlopen it by name (which is what
            //    makes the framework call our native_init), and this class can load the very
            //    same file without unpacking anything.
            String directory = nativeDirectory;
            if (directory != null && directory.length() > 0) {
                File candidate = new File(directory, FILE);
                if (candidate.isFile()) {
                    try {
                        System.load(candidate.getAbsolutePath());
                        loaded = true;
                        loadedFrom = candidate.getAbsolutePath();
                        loadedPath = candidate.getAbsolutePath();
                        Log.i(TAG, "library loaded from the host native dir " + candidate);
                        return true;
                    } catch (Throwable error) {
                        Log.w(TAG, "host native dir load failed", error);
                    }
                } else {
                    Log.w(TAG, "host native dir has no " + FILE + ": " + candidate);
                }
            }

            // 3) The module APK itself, for hosts whose ApplicationInfo is unavailable.
            try {
                if (loadFromModuleApk()) {
                    loaded = true;
                    return true;
                }
            } catch (Throwable error) {
                Log.w(TAG, "module library extraction failed", error);
            }
            return false;
        }
    }

    /** Pull the library out of the module APK and load it by absolute path. */
    private static boolean loadFromModuleApk() throws Exception {
        String apkPath = moduleApkPath();
        if (apkPath == null) {
            Log.w(TAG, "module apk path not found");
            return false;
        }
        File moduleApk = new File(apkPath);

        // Extract into a sub-directory of the module's own folder rather than into the app's
        // cache: this can run from onModuleLoaded, before the host Application exists, so no
        // Context is available. The module APK's directory is already writable by this process.
        File directory = new File(moduleApk.getParentFile(), "native");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "module native directory unavailable: " + directory);
            return false;
        }

        try (ZipFile zip = new ZipFile(moduleApk)) {
            for (String abi : supportedAbis()) {
                String entryName = findEntry(zip, abi);
                if (entryName == null) continue;
                ZipEntry source = zip.getEntry(entryName);
                if (source == null) continue;

                // The module APK's length is part of the file name so a rebuilt library never
                // gets shadowed by a stale extraction of the same ABI.
                File target = new File(directory, abi + "-" + moduleApk.length() + "-" + FILE);
                if (!target.isFile() || target.length() != source.getSize()) {
                    try (InputStream in = zip.getInputStream(source);
                         FileOutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[1 << 16];
                        int read;
                        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    }
                    target.setReadable(true, true);
                    target.setExecutable(true, true);
                    target.setWritable(false, false);
                }
                System.load(target.getAbsolutePath());
                loadedFrom = entryName;
                loadedPath = target.getAbsolutePath();
                Log.i(TAG, "library loaded from module apk entry=" + entryName);
                return true;
            }
        }
        Log.w(TAG, "no packaged abi matched " + Arrays.toString(supportedAbis()) + " in " + apkPath);
        return false;
    }

    /**
     * Locate the library inside the module APK, tolerating the two layouts that occur.
     *
     * <p>{@code lib/<abi>/} is the layout this project builds. LSPatch rewrites it to
     * {@code <abi>/} while handing the module to the host, and a future patcher version could
     * use something else again, so the last step simply accepts any path that names both the
     * ABI and the file.
     */
    private static String findEntry(ZipFile zip, String abi) {
        String packaged = "lib/" + abi + "/" + FILE;
        if (zip.getEntry(packaged) != null) return packaged;
        String repacked = abi + "/" + FILE;
        if (zip.getEntry(repacked) != null) return repacked;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.endsWith("/" + FILE) && name.contains(abi)) return name;
        }
        return null;
    }

    /** ABI candidates, most preferred first. */
    private static String[] supportedAbis() {
        String[] abis = Build.SUPPORTED_ABIS;
        return abis == null || abis.length == 0
                ? new String[]{"arm64-v8a", "armeabi-v7a"} : abis;
    }

    /**
     * Where the module's dex was loaded from.
     *
     * <p>The class loader description carries it: Vector's module loader renders as
     * {@code VectorModuleClassLoader[module=/data/…/io.github.ldxm666.mapadkiller/<hash>.apk, …]}.
     */
    private static String moduleApkPath() {
        try {
            String description = String.valueOf(NativeLabelAssets.class.getClassLoader());
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("module=([^,\\]]+\\.apk)").matcher(description);
            if (m.find()) {
                File file = new File(m.group(1));
                if (file.isFile()) return file.getAbsolutePath();
            }
        } catch (Throwable ignored) { }
        try {
            java.security.CodeSource source =
                    NativeLabelAssets.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                String path = java.net.URLDecoder.decode(source.getLocation().getPath(), "UTF-8");
                if (path.endsWith(".apk") && new File(path).isFile()) return path;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static volatile String nativeDirectory;
    private static volatile boolean loaded;
    private static volatile String loadedFrom = "";
    /** Absolute path this class loaded the library from, or null when the linker found it. */
    private static volatile String loadedPath;
    private static volatile boolean frameworkAsked;
    /** Mask handed to the native side, or -1 when it has never been armed. */
    private static volatile int appliedMask = -1;
}
