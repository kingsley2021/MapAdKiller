package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

/**
 * 宿主身份诊断：**签名 DN** + **嵌入/模块模式**。
 *
 * <h3>为什么需要</h3>
 * 用户报「root 用户用了模块后原版高德签名被改」。静态排查已证明模块**不可能**改高德本体
 * （全源码 PackageInstaller / pm install / deletePackage / Runtime.exec / APK 写入 全 0 命中），
 * 真实原因是那位用户装的是 **174.8 MB 的免 root 内置版**（用 {@code CN=xiawanli} 重新签名、
 * 包名仍是 {@code com.autonavi.minimap} 的重打包高德），而不是 4.2 MB 的模块。
 *
 * <p>所以这里打一条**一次性**日志，以后支持人员一眼就能看出用户装错了包：
 * <pre>
 * event=host_signer pkg=com.autonavi.minimap dn=CN=xiawanli embedded=true sig=1a2b3c4d5e6f7788
 * event=host_signer pkg=com.autonavi.minimap dn=CN=minimap embedded=false sig=9f8e7d6c5b4a3928
 * </pre>
 * 判据：官方高德 {@code CN=minimap}；内置版 {@code CN=xiawanli}。
 *
 * <h3>为什么单独一个类，而不是塞进 {@code App}</h3>
 * {@code mapadkiller/App} 在精简构建里会被 {@code tools/variant-stubs/} 的同名 stub 替换
 * （stub 只有构造器）。往 {@code App} 加方法会**让变体构建缺符号**。新建一个类不受 stub 影响，
 * 两种构建里都是真实实现。{@code mapclean/App} 同理（它也有 stub）。
 */
public final class HostInfo {

    /** 只打一次。 */
    /**
     * 已上报过的**宿主包名**集合（按包名去重，不是全局一次性）。
     *
     * <p>为什么按包名：模块可能被注入到多个进程/多个 App（实测日志里出现过
     * {@code com.miui.contentcatcher}、{@code com.google.android.webview}），
     * 早期用单个 AtomicBoolean 会被"别的进程先跑"占位，导致真正的高德进程一次都报不出来。
     */
    private static final java.util.Set<String> REPORTED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 模式检测只算一次。 */
    private static volatile Boolean embedded;

    private HostInfo() {}

    // ══════════════════════════════════════════════ 嵌入 / 模块 模式

    /**
     * 当前是不是**免 root 内置（LSPatch）**形态。
     *
     * <p>判据按可靠性排序，任一命中即为 true：
     * <ol>
     *   <li><b>宿主 APK 内存在 {@code assets/lspatch/config.json}</b> —— 最直接；
     *       只用 {@link java.util.zip.ZipFile} 读**目录项**，不解压、不写盘；</li>
     *   <li>运行期存在 LSPatch 的类（{@code org.lsposed.lspatch.loader.LSPApplication} 等）；</li>
     *   <li>{@code mapclean.App.embedded()} 为 true（面板侧已经判过，反射调用避免编译期依赖）。</li>
     * </ol>
     * 全程 try/catch；任何异常都按"模块模式（false）"处理，并且结果静态缓存，只算一次。
     */
    public static boolean isEmbedded() {
        Boolean v = embedded;
        if (v != null) return v;
        boolean r;
        try {
            r = detectEmbedded();
        } catch (Throwable t) {
            r = false;
        }
        embedded = r;
        return r;
    }

    private static boolean detectEmbedded() {
        // 1) 宿主 APK 里的 assets/lspatch/config.json（只读 zip 中央目录）
        try {
            String apk = apkPath();
            if (apk != null) {
                java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk);
                try {
                    if (z.getEntry("assets/lspatch/config.json") != null) return true;
                } finally {
                    try { z.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }

        // 2) LSPatch 运行期类
        String[] probes = {
                "org.lsposed.lspatch.loader.LSPApplication",
                "org.lsposed.lspatch.loader.LSPLoader",
                "org.lsposed.lspatch.share.Constants"
        };
        for (String c : probes) {
            try { Class.forName(c); return true; } catch (Throwable ignored) { }
        }

        // 3) 面板侧已判过的结果
        try {
            Class<?> a = Class.forName("io.github.ldxm666.mapclean.App");
            Object v = a.getMethod("embedded").invoke(null);
            if (v instanceof Boolean && (Boolean) v) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    // ══════════════════════════════════════════════ 宿主签名诊断

    /**
     * 打**一次** `event=host_signer`。
     *
     * @param pkg 当前进程的包名（由框架回调给出）
     */
    public static void reportOnce(String pkg) {
        // ★ 不能在这里先置位：早期（module_loaded 后 ~37ms）宿主 Application 还没建好，
        // hostCtx() 返回 null，若此时就把 REPORTED 置 true，就永远不会重试 —— 实测
        // dn=-/sig=- 就是这么来的（和 r23 的 localPrefs() 是同一个坑）。
        // 先判 Context，拿不到直接返回、**不置位**，等 Application 就绪后的第二次调用。
        try {
            boolean emb = isEmbedded();
            String dn = "-";
            String sig = "-";
            try {
                Context ctx = Config.hostCtx();
                if (ctx == null) return;   // 未就绪，留待下次（不置位）
                // ★ 包名以 Context 为准，不用调用方传进来的值：实测 App.onCreate 传进来的
                // 可能是 contentcatcher / webview（模块也被注入到那些进程），而 Context 才是宿主。
                String real = pkg;
                try { String fromCtx = ctx.getPackageName(); if (fromCtx != null && fromCtx.length() > 0) real = fromCtx; } catch (Throwable ignored) { }
                if (REPORTED.contains(real)) return;
                pkg = real;
                if (ctx != null) {
                    PackageManager pm = ctx.getPackageManager();
                    // API 33+ 上 GET_SIGNATURES 已废弃且**返回 null**（本机 targetSdk=35 实测
                    // dn=-/sig=-），必须走 GET_SIGNING_CERTIFICATES + signingInfo。
                    byte[] raw = null;
                    try {
                        PackageInfo pi28 = pm.getPackageInfo(pkg,
                                PackageManager.GET_SIGNING_CERTIFICATES);
                        if (pi28 != null && pi28.signingInfo != null) {
                            Signature[] arr = pi28.signingInfo.getApkContentsSigners();
                            if (arr == null || arr.length == 0) {
                                arr = pi28.signingInfo.getSigningCertificateHistory();
                            }
                            if (arr != null && arr.length > 0) raw = arr[0].toByteArray();
                        }
                    } catch (Throwable ignored) { }
                    if (raw == null) {
                        // API < 28 兜底（老系统上 GET_SIGNING_CERTIFICATES 不可用）
                        try {
                            PackageInfo piOld = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                            Signature[] sigs = piOld.signatures;
                            if (sigs != null && sigs.length > 0) raw = sigs[0].toByteArray();
                        } catch (Throwable ignored) { }
                    }
                    if (raw != null) {
                        sig = sha256Head(raw);
                        try {
                            java.security.cert.X509Certificate cert =
                                    (java.security.cert.X509Certificate)
                                            java.security.cert.CertificateFactory
                                                    .getInstance("X.509")
                                                    .generateCertificate(
                                                            new java.io.ByteArrayInputStream(raw));
                            dn = cert.getSubjectX500Principal().getName();
                        } catch (Throwable ignored) {
                            dn = "?";
                        }
                    }
                }
            } catch (Throwable ignored) { }
            // ★ 不再输出 dn / sig：LSPatch 会把宿主的 PackageManager 包装成"原版应用"，
            // 于是 getPackageInfo() 返回的是高德**原始**签名（CN=minimap），而不是磁盘上
            // 那个重签名包（CN=xiawanli）。这条日志以前因此具有误导性 —— 内置版用户会
            // 以为自己装的是官方包。真正要回答的问题「用户装的是官方版还是内置版」，
            // 用 embedded 一个字段就够了（判据是宿主 APK 里有没有 assets/lspatch/config.json，
            // 不经过 PackageManager，一直准确）。
            String modeLine = "event=host_mode pkg=" + pkg + " embedded=" + emb
                    + (emb ? " kind=lspatch-embedded" : " kind=official-or-lsposed");
            // 宿主进程里走 H.log（顺带进「运行日志」）；设置页进程里 H 类初始化会失败
            // （缺 libxposed API），兜底用 SafeLog，别让这一行日志把整个方法打断。
            try {
                H.log(modeLine);
            } catch (Throwable t) {
                SafeLog.i(MainHook.TAG, modeLine);
            }
            // 只有在真正打出日志之后才记入去重集合：拿不到 Context / 抛异常时不会占位。
            REPORTED.add(pkg);
        } catch (Throwable ignored) { }
    }

    /** 签名字节的 SHA-256 前 8 字节（16 个 hex），够做指纹对照又不刷屏。 */
    private static String sha256Head(byte[] raw) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < d.length; i++) {
                int v = d[i] & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String apkPath() {
        try {
            Context ctx = Config.hostCtx();
            if (ctx == null) return null;
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0);
            return ai == null ? null : ai.sourceDir;
        } catch (Throwable t) {
            return null;
        }
    }
}
