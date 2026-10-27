package io.github.ldxm666.mapadkiller;

import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 「安装包异常」第二修：**覆盖安装后第一次冷启动**的 linker 命名空间库目录错位。
 *
 * <h3>现象与根因（真机证据）</h3>
 * 免 root 内置版每次覆盖安装后第一次冷启动，高德必弹「安装包异常 / …去下载…」，
 * 第二次起正常。真机 13:36:39 与 13:45:57 两次复现的日志完全同构：
 * <pre>
 *   13:45:57.260 nativeloader: Configuring clns-10 for other apk
 *                  /data/user/0/com.autonavi.minimap/cache/lspatch/origin/4187910933.apk
 *                  library_path=/data/app/~~OkjFNN…/lib/arm64      ← 上一版的目录（已被删）
 *   13:45:57.402 MapAdKiller: event=libdir_probe used=/data/app/~~4tQRD8…/lib/arm64
 *   13:45:57.4xx dlopen failed: library "libamapkvd.so" not found:
 *                  needed by /data/app/~~4tQRD8…/lib/arm64/libamaptts.so in namespace clns-10
 * </pre>
 * 即：**应用侧拿到的是新目录**（libamaptts.so 确实是从新目录打开的），但 LSPatch 建的
 * 那个 linker namespace（clns-10）里登记的搜索路径还是**上一版**的目录，于是
 * libamaptts.so 的 DT_NEEDED 一个都找不到 → UnsatisfiedLinkError → 高德兜底的
 * {@code kf3.uncaughtException} → {@code t30.showErrorActivity} 判成 InstallError →
 * 起 ErrorActivity（就是那个「安装包异常」黑框）。
 *
 * <h3>为什么 AmapSoPreload 修不了这一次</h3>
 * AmapSoPreload 挂在 {@code AppInterfaces.setupSoLoader}，而它由
 * {@code NewMapApplication.attachBaseContext} 调用；这次崩溃发生在 **Application
 * 之前**（ContentProvider 初始化阶段），日志里只有 {@code event=so_preload_hooked}
 * 而没有任何 {@code event=so_preload …} 行，说明 setupSoLoader 压根没被调用。
 *
 * <h3>修法</h3>
 * 在模块加载的瞬间（比 ContentProvider 早）用**高德自己的 ISoLoader**
 * （{@code AppInterfaces.getSoLoader()} → {@code p000.t46.loadLibrary(String)}）把
 * libamaptts.so 依赖的整个闭包按依赖顺序先装一遍。t46 内部是 {@code System.loadLibrary}，
 * 调用方是高德自己的类 ⇒ 落进的正是 clns-10，DT_NEEDED 之后全部命中已加载的 so。
 *
 * <p>注意两个"不能"：
 * <ul>
 *   <li>不能用 {@code System.load(绝对路径)} —— 那会落进模块自己的 namespace，
 *       宿主导入时依然看不到（bionic 只在本 namespace 内按 soname 找已加载库）。</li>
 *   <li>不能用 {@code Runtime.load0(ClassLoader,String)} —— Android 16 上这个方法
 *       不存在（真机实测 NoSuchMethodException），而且它拿不到应用 namespace。</li>
 * </ul>
 *
 * <p>可重入/幂等：bionic 按 soname 去重，已加载的库再 loadLibrary 只会返回旧 handle，
 * 不会二次 JNI_OnLoad；因此健康启动时这一发只是把高德本来就要做的事提前几十毫秒，
 * 对用户零副作用。任何异常只写日志，绝不抛出。
 */
public final class AmapLibDirFix {

    /**
     * libamaptts.so 的 DT_NEEDED 闭包（build/…/lib/arm64-v8a 里的库，按依赖顺序，
     * 不带 lib 前缀/后缀 —— 直接喂给 System.loadLibrary）。系统库
     * （libdl/liblog/libz/libm/libc/libandroid）永远能解析，不在此列。
     */
    private static final String[] DEPS = {
            "c++_shared", "openssl", "zstd", "amapnsq", "amapkvd",
            "xquic", "cJSON", "opus", "nui", "OSL_MNN"
    };

    private static volatile boolean sStarted;

    private AmapLibDirFix() {}

    /** 同步执行：必须赶在 ContentProvider 之前，所以**不开线程**。总耗时约 30-60ms。 */
    public static void install(ClassLoader cl) {
        if (sStarted) return;
        sStarted = true;
        try {
            probe(cl);
            Object loader = soLoader(cl);
            if (loader == null) { H.log("event=libdir_skip reason=no_loader"); return; }
            Method loadLibrary = loader.getClass().getMethod("loadLibrary", String.class);
            loadLibrary.setAccessible(true);
            int ok = 0;
            long begin = System.currentTimeMillis();
            for (String name : DEPS) {
                long one = System.currentTimeMillis();
                try {
                    loadLibrary.invoke(loader, name);
                    ok++;
                    H.log("event=libdir_dep name=" + name + " ok ms=" + (System.currentTimeMillis() - one));
                } catch (Throwable error) {
                    H.log(Log.WARN, MainHook.TAG, "event=libdir_dep name=" + name + " failed", unwrap(error));
                }
            }
            H.log("event=libdir_preload_done ok=" + ok + "/" + DEPS.length
                    + " ms=" + (System.currentTimeMillis() - begin));
        } catch (Throwable error) {
            H.log(Log.WARN, MainHook.TAG, "event=libdir_failed", error);
        }
    }

    /** 只读诊断：把三个来源的库目录各打一行，方便对比"应用侧新目录 vs 出问题的 namespace 旧目录"。 */
    private static void probe(ClassLoader cl) {
        try {
            Class<?> at = H.cls(cl, "android.app.ActivityThread");
            Object thread = at == null ? null : at.getMethod("currentActivityThread").invoke(null);
            Object bind = thread == null ? null : H.getObjectField(thread, "mBoundApplication");
            Object appInfo = bind == null ? null : H.getObjectField(bind, "appInfo");
            Object loadedApk = bind == null ? null : H.getObjectField(bind, "info");
            String used = appInfo == null ? null : (String) H.getObjectField(appInfo, "nativeLibraryDir");
            ClassLoader appCl = null;
            if (loadedApk != null) {
                try { appCl = (ClassLoader) loadedApk.getClass().getMethod("getClassLoader").invoke(loadedApk); }
                catch (Throwable ignored) {}
            }
            H.log("event=libdir_probe used=" + used + " pms=" + pmsDir(appInfo) + " java=" + javaLibDirs(appCl));
        } catch (Throwable error) {
            H.log(Log.WARN, MainHook.TAG, "event=libdir_probe_failed", error);
        }
    }

    /** 高德自己的 ISoLoader（{@code com.amap.AppInterfaces.getSoLoader()}）。 */
    private static Object soLoader(ClassLoader cl) {
        try {
            Class<?> api = H.cls(cl, "com.amap.AppInterfaces");
            if (api == null) return null;
            return api.getMethod("getSoLoader").invoke(null);
        } catch (Throwable error) {
            H.log(Log.WARN, MainHook.TAG, "event=libdir_loader_failed", error);
            return null;
        }
    }

    /** PackageManager 的权威 nativeLibraryDir（直接问 PMS，不受绑定时的快照影响）。 */
    private static String pmsDir(Object appInfo) {
        String pkg = appInfo == null ? null : (String) H.getObjectField(appInfo, "packageName");
        if (pkg == null) return null;
        try {
            Class<?> globals = Class.forName("android.app.AppGlobals");
            Object ipm = globals.getMethod("getPackageManager").invoke(null);
            if (ipm == null) return null;
            int userId = android.os.Process.myUid() / 100000;
            for (Method method : ipm.getClass().getMethods()) {
                if (!"getApplicationInfo".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 3 || types[0] != String.class) continue;
                try {
                    Object arg = types[1] == long.class ? (Object) Long.valueOf(0L) : (Object) Integer.valueOf(0);
                    Object info = method.invoke(ipm, pkg, arg, Integer.valueOf(userId));
                    String dir = info == null ? null : (String) H.getObjectField(info, "nativeLibraryDir");
                    if (dir != null) return dir;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** {@code BaseDexClassLoader.pathList.nativeLibraryDirectories}。 */
    private static List<String> javaLibDirs(ClassLoader loader) {
        List<String> out = new ArrayList<>();
        if (loader == null) return out;
        try {
            Object pathList = H.getObjectField(loader, "pathList");
            Object dirs = H.getObjectField(pathList, "nativeLibraryDirectories");
            if (dirs instanceof Object[]) {
                for (Object dir : (Object[]) dirs) {
                    String text = String.valueOf(dir);
                    if (!text.isEmpty() && !out.contains(text)) out.add(text);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) {
            return error.getCause();
        }
        return error == null ? new IllegalStateException("null") : error;
    }

    /** 保留：某些 ROM 上 File 判存在比 loadLibrary 更快失败，便于日志自证。 */
    private static boolean exists(String dir, String name) {
        return dir != null && new File(dir, "lib" + name + ".so").isFile();
    }
}
