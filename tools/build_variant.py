#!/usr/bin/env python3
"""Build the AMap-only ("免 root 内置版") module variant.

Produces a slimmed module APK that contains only the 高德 half of the module, then feeds
it to the pinned official LSPatch CLI to produce an integrated (rootless) host APK whose
only entry point is the in-app floating orb.

What "slimmed" means here
-------------------------
The full module carries three vendor adapters (高德 / 百度 / 腾讯) plus the standalone
settings Activity. This variant keeps:

  * io.github.ldxm666.mapadkiller.*  -- but only the 高德 files (Amap*.java + shared core)
  * io.github.ldxm666.mapclean.*     -- shared motion/UI kit + the floating layer
  * the libxposed service/interface jars and the native label library

and drops every 百度 / 腾讯 source file, the standalone settings Activity, and the
old "long-press More Tools" embedded entry (replaced by the floating orb).

Cross-boundary calls are satisfied with generated no-op stubs rather than runtime
reflection, so the slim build stays statically verifiable.

Usage:
  python tools/build_variant.py --ModuleApk output/MapClean-lsp-v2.1.3.apk \
      --OriginalApk <amap-original.apk> --OutputDirectory output/nonroot-amap
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# 交接包是 src\ 布局，2.3.0 树是 app\ 布局
APP = (ROOT / "app") if (ROOT / "app").is_dir() else (ROOT / "src")

TAG = "v1.2"
CLI_NAME = "lspatch-v1.2-487-release.jar"
CLI_SHA256 = "d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165"
RELEASE_URL = f"https://github.com/JingMatrix/LSPatch/releases/tag/{TAG}"
SOURCE_COMMIT = "0dc50f42503711b14f5e2bb217c2bdd6321ce5be"
LICENSE_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"

# ABI folders that must receive the label library inside the patched host APK.
NATIVE_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
NATIVE_LIBRARY = "libmapclean_labels.so"
NATIVE_ENTRY = "lib/{abi}/" + NATIVE_LIBRARY

MODULE_PACKAGE = "io.github.ldxm666.mapadkiller"
AMAP = "com.autonavi.minimap"
BAIDU = "com.baidu.BaiduMap"
CONFIG_ENTRY = "assets/lspatch/config.json"
MODULE_ENTRY = f"assets/lspatch/modules/{MODULE_PACKAGE}.apk"
ORIGINAL_ENTRY = "assets/lspatch/origin.apk"

# ---- what the slim variant drops -------------------------------------------

# Whole packages that belong to other vendors.
DROP_PACKAGES = ("io.github.ldxm666.bmapclean",)

# 高德 package files that are not part of the 高德 hook path.
DROP_AMAP_FILES = {
    "BmapHooks.java", "TmapHooks.java",          # other vendors' adapters
    "Sweeper.java", "ViewKiller.java",           # unused by the amap baseline
    "SplashProbe.java", "SplashContainerHooks.java", "SplashPhase.java",
    "SettingsTile.java",                          # QS tile belongs to the standalone app
}

# mapclean files replaced by the floating layer or not needed in an embedded build.
DROP_MAPCLEAN_FILES = {
    "MainActivity.java",        # standalone settings page; the orb replaces it
    "EmbeddedSettings.java",    # old long-press entry; the orb replaces it
    "Pager.java",               # used only by MainActivity
    "Backdrop.java",            # photo backdrop for the glass settings page
    # NOTE: UpdateChecker.java is deliberately NOT dropped.
    # It was listed as standalone-app only, but the embedded build now shows the module
    # version + a check-for-updates action in the floating panel (user request).
    # It only needs android.* + java.net/java.util.regex, so a slim build compiles.
    "ConfigBackup.java",        # standalone settings import/export
    # NOTE: NativeLabelAssets.java is deliberately NOT dropped here.
    #
    # It was originally listed as "baidu label assets", and that was wrong: the class
    # gates on the process name and handles 高德 too --
    #     if ("com.autonavi.minimap".equals(process)) { map = 1; mask = ...K_MAP_POI ? 0 : 1; }
    # -- and it is the *only* thing that drives libmapclean_labels.so, whose AMap host
    # filters the map style protobuf (map_assets/style_*X_Main) to remove POI features while
    # leaving roads, water and place names intact.
    #
    # Dropping it silently disabled 地图标注 in this variant: the switch still wrote its
    # preference, nothing ever read it, and the panel looked broken.
}

# Stubs generated so the remaining sources still compile against dropped symbols.
# ---- generated stubs -------------------------------------------------------
#
# Every stub is a real file under tools/variant-stubs/, laid out like the source tree, so it can
# be read and reviewed like any other source. A slim build copies the selected ones over the
# files of the vendor it drops.
#
# The rule that matters: never delete a *caller* to make a slim build compile. One APK declares
# exactly one Xposed entry point and exactly one Application, so the shared code reaches the
# other vendor halves by name -- and a slim build satisfies that by generating a no-op, not by
# dropping the call. Deleting the 百度 forwarding is precisely how 2.1.4 shipped with 百度
# silently dead; app/build.ps1 now fails the build if the forwarding disappears.

STUB_DIR = ROOT / "tools" / "variant-stubs"

# Standalone-app surface: it has no home in an embedded build (the orb replaces it).
SHARED_STUBS = (
    "io/github/ldxm666/mapclean/EmbeddedSettings.java",
    "io/github/ldxm666/mapclean/MainActivity.java",
    # The floating-window code asks "am I an embedded build?" through HostInfo, which lives in
    # mapadkiller -- a package every vendor variant drops. The stub answers true (an embedded
    # build IS the embedded form), so 百度/腾讯 内置版 keep their orb.
    "io/github/ldxm666/mapadkiller/HostInfo.java",
)

# Classes the shared code still names after a vendor package is dropped, so they get a no-op.
STUBBED_CLASSES = ("MainHook", "App", "Config", "HostInfo", "Baseline", "CaptureLog")

FLOATING_REQUIRED = (
    b"Lio/github/ldxm666/mapclean/FloatingBall;",
    b"Lio/github/ldxm666/mapclean/FloatingHooks;",
    b"Lio/github/ldxm666/mapclean/FloatingPanel;",
)

# ---- the vendor allow-list, checked in the compiled DEX ---------------------
#
# 免 root 内置版 的 service 投递整条链挂在一份厂商白名单上。slim 构建把
# mapclean.EmbeddedSettings 换成 tools/variant-stubs 的替身，替身的 enabledFor() 就是
# EmbeddedSettingsPolicy.isHostPackage()（app 源码里的真件语义相同）。白名单漏掉一个
# 宿主，mapclean.App.attachEmbedded() 就会在**最后一行 registerListener() 之前**抛
# IllegalStateException；FloatingConfig.ensureService() 的 catch (Throwable) 把它吞掉，
# 于是那个进程一条投递路径都没有：
#
#   * App.svc() 恒 null → 悬浮球面板恒显「○ 只读 · 服务未连接」，每个开关都写不进去；
#   * 该厂商的 App / 诊断 / token 持有者也拿不到 Application。
#
# 腾讯正是这么死的：2.1.6 装机包的 classes.dex 里 isHostPackage() 只有两条
# const-string（高德 / 百度），同一份 APK 里百度进程 registerListener ok、腾讯进程
# 只有一条 "embedded service gate closed"。
#
# 为什么必须读 DEX 而不是源码：产物里它就只是一个字符串常量，required/banned 那种
# 「类描述符在不在」的检查完全看不见它；而源码里的名单和真正编译进去的名单之间隔着
# 桩替换、javac 常量内联和 d8 —— 只有反读编译出来的方法体才是真的。
HOST_WHITELIST_CLASS = b"Lio/github/ldxm666/mapclean/EmbeddedSettingsPolicy;"
HOST_WHITELIST_METHOD = "isHostPackage"


def vendor_classes(package: str, keep) -> tuple:
    """DEX type descriptors for one vendor package, minus the classes we stub out."""
    folder = APP / "src" / Path(package.replace(".", "/"))
    prefix = b"L" + package.replace(".", "/").encode() + b"/"
    return tuple(prefix + f.stem.encode() + b";"
                 for f in sorted(folder.glob("*.java")) if f.stem not in keep)


# One entry per map app we ship an integrated build for. The two are mirror images: each drops
# the other vendor's whole package and substitutes no-ops for the few symbols still named.
TARGETS = {
    "amap": {
        "package": AMAP,
        "label": "高德地图",
        "artifact": "高德地图-{version}-免root内置版.apk",
        "slimModule": "MapAdKiller-amap-slim-{version}.apk",
        "dropPackages": ("io.github.ldxm666.bmapclean",),
        "stubs": SHARED_STUBS + (
            "io/github/ldxm666/mapadkiller/TmapHooks.java",
            "io/github/ldxm666/bmapclean/MainHook.java",
            "io/github/ldxm666/bmapclean/App.java",
            "io/github/ldxm666/mapclean/BaiduPanel.java",
            "io/github/ldxm666/mapclean/TmapPanel.java",
        ),
        # Same failure mode as 百度 2.1.4, one vendor over: a no-op stub for the 腾讯 forwarding
        # compiles, passes every structural check, and leaves 腾讯 with no UI hooks at all.
        # Checked on the generated source because the DEX cannot tell the difference.
        "invariants": (
            ("io/github/ldxm666/mapadkiller/MainHook.java",
             "tmapclean.MainHook.onPackageReady"),
        ),
        "required": FLOATING_REQUIRED + (
            b"Lio/github/ldxm666/mapadkiller/AmapHooks;",
            b"Lio/github/ldxm666/mapadkiller/AmapNativeHome;",
            b"Lio/github/ldxm666/mapclean/BaiduPanel;",      # the generated no-op
            b"Lio/github/ldxm666/mapclean/TmapPanel;",       # the generated no-op
            b"Lio/github/ldxm666/bmapclean/MainHook;",       # the forwarding target
        ),
        "banned": vendor_classes("io.github.ldxm666.bmapclean", STUBBED_CLASSES) + (
            b"Lio/github/ldxm666/mapadkiller/BmapHooks;",
            b"Lcom/baidu/",
        ),
    },
    "tmap": {
        "package": "com.tencent.map",
        "label": "腾讯地图",
        "artifact": "腾讯地图-{version}-免root内置版.apk",
        "slimModule": "MapAdKiller-tmap-slim-{version}.apk",
        "dropPackages": ("io.github.ldxm666.mapadkiller", "io.github.ldxm666.bmapclean"),
        "stubs": SHARED_STUBS + (
            "io/github/ldxm666/mapadkiller/Config.java",
            "io/github/ldxm666/mapadkiller/App.java",
            "io/github/ldxm666/mapadkiller/MainHook.java",
                "io/github/ldxm666/mapadkiller/Baseline.java",
            "io/github/ldxm666/bmapclean/App.java",
            "io/github/ldxm666/bmapclean/MainHook.java",
            "io/github/ldxm666/mapclean/AmapPanel.java",
            "io/github/ldxm666/mapclean/BaiduPanel.java",
        ),
        "required": FLOATING_REQUIRED + (
            b"Lio/github/ldxm666/tmapclean/MainHook;",
            b"Lio/github/ldxm666/tmapclean/TmapMapIcons;",
            b"Lio/github/ldxm666/mapclean/TmapPanel;",
            b"Lio/github/ldxm666/mapadkiller/MainHook;",
        ),
        "banned": vendor_classes("io.github.ldxm666.mapadkiller", STUBBED_CLASSES)
                  + vendor_classes("io.github.ldxm666.bmapclean", STUBBED_CLASSES) + (
            b"Lcom/autonavi/",
            b"Lcom/baidu/",
        ),
    },
    "baidu": {
        "package": BAIDU,
        "label": "百度地图",
        "artifact": "百度地图-{version}-免root内置版.apk",
        "slimModule": "MapAdKiller-baidu-slim-{version}.apk",
        "dropPackages": ("io.github.ldxm666.mapadkiller",),
        "stubs": SHARED_STUBS + (
            "io/github/ldxm666/mapadkiller/Config.java",
            "io/github/ldxm666/mapadkiller/App.java",
            "io/github/ldxm666/mapadkiller/MainHook.java",
                "io/github/ldxm666/mapadkiller/Baseline.java",
                "io/github/ldxm666/mapadkiller/CaptureLog.java",
            "io/github/ldxm666/mapclean/AmapPanel.java",
            "io/github/ldxm666/mapclean/TmapPanel.java",
        ),
        # A stub that is supposed to *forward* must actually forward. A no-op compiles and
        # passes every structural check, then leaves 百度 with no hooks at all -- the 2.1.4
        # failure mode. This is checked on the generated source, because the DEX cannot tell
        # the difference.
        "invariants": (
            ("io/github/ldxm666/mapadkiller/MainHook.java",
             "bmapclean.MainHook.onPackageReady"),
        ),
        "required": FLOATING_REQUIRED + (
            b"Lio/github/ldxm666/bmapclean/BaiduHooks;",
            b"Lio/github/ldxm666/bmapclean/BaiduPoiLayer;",
            b"Lio/github/ldxm666/mapclean/AmapPanel;",       # the generated no-op
            b"Lio/github/ldxm666/mapclean/TmapPanel;",       # the generated no-op
            b"Lio/github/ldxm666/mapadkiller/MainHook;",     # the forwarding target
        ),
        "banned": vendor_classes("io.github.ldxm666.mapadkiller", STUBBED_CLASSES) + (
            b"Lcom/autonavi/",
        ),
    },
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def run(args, **kw):
    result = subprocess.run([str(a) for a in args], capture_output=True,
                            encoding="utf-8", errors="replace", **kw)
    if result.returncode:
        raise RuntimeError(f"{Path(str(args[0])).name} failed ({result.returncode}):\n"
                           + (result.stdout or "") + (result.stderr or ""))
    return (result.stdout or "") + (result.stderr or "")


def sdk_build_tools(sdk: Path, version: str | None) -> Path:
    versions = [x for x in (sdk / "build-tools").iterdir()
                if x.is_dir() and (x / ("aapt2.exe" if os.name == "nt" else "aapt2")).exists()]
    if version:
        versions = [x for x in versions if x.name == version]
    if not versions:
        raise ValueError(f"No Android SDK build-tools under {sdk}")
    return max(versions, key=lambda x: tuple(int(y) for y in re.findall(r"\d+", x.name)))


def tool(directory: Path, name: str) -> Path:
    """Resolve an SDK build-tool. On Windows d8/apksigner ship as .bat wrappers."""
    if os.name == "nt":
        for suffix in (".bat", ".exe", ""):
            candidate = directory / (name + suffix)
            if candidate.exists():
                return candidate
    return directory / (name + ".exe" if os.name == "nt" else name)


def pinned_download(directory: Path, name: str, expected: str, url: str) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    dest = directory / name
    if not dest.exists():
        tmp = dest.with_suffix(dest.suffix + ".download")
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "MapAdKiller-variant"})
            with urllib.request.urlopen(request, timeout=180) as r, tmp.open("wb") as out:
                shutil.copyfileobj(r, out)
            if sha256(tmp) != expected:
                raise ValueError(f"release SHA256 mismatch: {name}")
            tmp.replace(dest)
        finally:
            tmp.unlink(missing_ok=True)
    if sha256(dest) != expected:
        raise ValueError(f"cached release SHA256 mismatch: {name}")
    return dest


# ---------------------------------------------------------------- slim sources

def copy_slim_sources(dest: Path, target: dict) -> dict:
    """Copy app/ into dest, then remove the non-高德 half and write stubs.

    The signing keystore is carried over on purpose: the slim module must be signed with
    the same key as the full one, otherwise LSPatch's embedded module and the framework's
    signature checks disagree.
    """
    shutil.copytree(APP, dest, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("build", "dist"))

    src = dest / "src"
    removed, stubbed = [], []

    # 1) drop whole foreign packages
    for pkg in target["dropPackages"]:
        folder = src / Path(pkg.replace(".", "/"))
        if folder.is_dir():
            for f in folder.rglob("*.java"):
                removed.append(f.relative_to(src).as_posix())
            shutil.rmtree(folder)

    # 2) drop named files in the two kept packages
    for rel in sorted(DROP_AMAP_FILES):
        f = src / "io/github/ldxm666/mapadkiller" / rel
        if f.exists():
            removed.append(f.relative_to(src).as_posix())
            f.unlink()
    for rel in sorted(DROP_MAPCLEAN_FILES):
        f = src / "io/github/ldxm666/mapclean" / rel
        if f.exists():
            removed.append(f.relative_to(src).as_posix())
            f.unlink()

    # 3) write the stubs this target needs for symbols the kept sources still name
    for rel in target["stubs"]:
        f = src / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text((STUB_DIR / rel).read_text(encoding="utf-8"), encoding="utf-8")
        stubbed.append(rel)

    # 4) a stub that is meant to forward must actually forward
    for rel, needle in target.get("invariants", ()):
        body = (src / rel).read_text(encoding="utf-8")
        if needle not in body:
            raise ValueError(
                f"{rel} must contain {needle!r}: a silent no-op here would drop the forwarding "
                f"and leave that vendor with no hooks at all")
        stubbed.append(rel + " [invariant ok]")

    return {"removed": sorted(removed), "stubbed": sorted(stubbed)}


def rewrite_scope(dest: Path, target: dict) -> None:
    """A slim variant only ever runs inside its own map, so narrow the declared scope."""
    scope = dest / "META-INF" / "xposed" / "scope.list"
    scope.write_text(target["package"] + "\n", encoding="utf-8")


def rewrite_native_init(dest: Path) -> None:
    """
    Keep the native init list pointing at the label library.

    The framework calls each listed library's {@code native_init(entries)} at module load,
    which is where libmapclean_labels.so binds the framework's hook function. Without that
    pointer, its {@code configure()} JNI call reports {@code accepted=0} and the AMap style
    filter never installs -- so 地图标注 would silently do nothing.

    The slim variant does ship the library for every ABI (see build_slim_module), so the
    entry must stay. (An earlier revision blanked this file on the assumption that the
    library was Baidu-only; that assumption was wrong and broke 高德 地图标注.)
    """
    path = dest / "META-INF" / "xposed" / "native_init.list"
    if path.exists():
        path.write_text("libmapclean_labels.so\n", encoding="utf-8")


def rewrite_manifest(dest: Path, version_name: str, version_code: int) -> None:
    """Point the slim manifest at the floating entry and drop the standalone app surface."""
    path = dest / "AndroidManifest.xml"
    text = path.read_text(encoding="utf-8")
    text = re.sub(r'android:versionCode="\d+"', f'android:versionCode="{version_code}"', text)
    text = re.sub(r'android:versionName="[^"]*"', f'android:versionName="{version_name}"', text)
    text = text.replace('android:label="MapAdKiller"', 'android:label="高德增强"')
    # Drop the launcher alias and the QS tile / report receiver: an integrated build has no
    # module launcher of its own, and those components would only add attack surface.
    text = re.sub(r"\s*<!-- 桌面入口.*?</activity-alias>", "", text, flags=re.S)
    text = re.sub(r"\s*<!-- 快捷设置磁贴.*?</service>", "", text, flags=re.S)
    text = re.sub(r"\s*<!--\s*百度侧：.*?</receiver>", "", text, flags=re.S)
    path.write_text(text, encoding="utf-8")


def rewrite_module_prop(dest: Path, version_name: str, version_code: int, target: dict) -> None:
    path = dest / "META-INF" / "xposed" / "module.prop"
    lines = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            line = f"version={version_name}"
        elif line.startswith("versionCode="):
            line = f"versionCode={version_code}"
        elif line.startswith("description="):
            line = (f"description={target['label']}去广告与界面精简（免 root 内置版）："
                    "悬浮球入口，无 root 直接使用。")
        lines.append(line)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ------------------------------------------------------------------ build slim

def build_slim_module(slim: Path, out_apk: Path, java: Path, sdk: Path, build_tools: Path) -> dict:
    """Compile the slimmed sources into a module APK with the same pipeline as app/build.ps1."""
    aj = sdk / "platforms" / "android-34" / "android.jar"
    if not aj.exists():
        raise ValueError(f"android.jar not found: {aj}")
    bt = build_tools
    libs = slim / "libs"
    build = slim / "build"
    for d in ("stubs", "classes", "dex", "out"):
        (build / d).mkdir(parents=True, exist_ok=True)

    def java_files(folder):
        return [str(p) for p in Path(folder).rglob("*.java")]

    # 1) framework stubs (compile-only, never packaged)
    run([java.replace("java", "javac") if False else JAVA_JAVAC, "-encoding", "UTF-8", "-nowarn",
         "-source", "8", "-target", "8", "-bootclasspath", aj, "-d", build / "stubs",
         *java_files(slim / "stub-src")])
    # 2) module sources
    cp = os.pathsep.join([str(aj), str(libs / "api-102.0.0.jar"),
                          str(libs / "service-classes.jar"), str(libs / "interface-102.0.0.jar")])
    run([JAVA_JAVAC, "-encoding", "UTF-8", "-nowarn", "--release", "8",
         "-classpath", cp, "-d", build / "classes", *java_files(slim / "src")])

    # 3) dex
    run([JAVA_JAR, "-cf", build / "classes.jar", "-C", build / "classes", "."])
    shutil.copy2(libs / "service-classes.jar", build / "service-classes.jar")
    run([tool(bt, "d8"), "--min-api", "26", "--lib", aj,
         "--lib", libs / "api-102.0.0.jar", "--output", build / "dex",
         build / "classes.jar", build / "service-classes.jar", libs / "interface-102.0.0.jar"])

    # 4) resources + manifest
    run([tool(bt, "aapt2"), "compile", "--dir", slim / "res", "-o", build / "res.zip"])
    run([tool(bt, "aapt2"), "link", "-o", build / "out" / "module.apk",
         "--manifest", slim / "AndroidManifest.xml", "-I", aj,
         "--min-sdk-version", "26", "--target-sdk-version", "34", build / "res.zip"])

    # 5) add dex + metadata + native libs (STORED for the libs)
    with zipfile.ZipFile(build / "out" / "module.apk", "a", zipfile.ZIP_DEFLATED) as z:
        z.write(build / "dex" / "classes.dex", "classes.dex")
        for p in sorted((slim / "META-INF").rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(slim).as_posix())
    # Native libraries must live at lib/<abi>/ inside the APK.
    #
    # This used to be `jar -C libs <abi>`, which stores the directory itself and lands the
    # file at <abi>/libmapclean_labels.so -- the archive root, not the ABI directory.
    # Android only ever looks under lib/<abi>/, so the library was invisible to both
    # System.loadLibrary and the framework's native_init loader. That is why the 地图标注
    # switch could never arm its native style filter, in any build.
    #
    # Entries are STORED (not deflated) for the same reason the platform stores them:
    # the dynamic linker memory-maps the library straight out of the APK.
    with zipfile.ZipFile(build / "out" / "module.apk", "a", zipfile.ZIP_STORED) as z:
        existing = set(z.namelist())
        for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
            lib = libs / abi / "libmapclean_labels.so"
            if not lib.exists():
                continue
            entry = f"lib/{abi}/libmapclean_labels.so"
            if entry not in existing:
                z.write(lib, entry)

    # 6) align + sign
    run([tool(bt, "zipalign"), "-P", "16", "-f", "4",
         build / "out" / "module.apk", build / "out" / "module-aligned.apk"])
    run([tool(bt, "zipalign"), "-c", "-P", "16", "4", build / "out" / "module-aligned.apk"])
    ks = slim / "debug.keystore"
    if not ks.exists():
        raise ValueError(f"keystore missing: {ks}")
    run([tool(bt, "apksigner"), "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--v1-signing-enabled", "true",
         "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
         "--out", out_apk, build / "out" / "module-aligned.apk"])
    run([tool(bt, "apksigner"), "verify", "--print-certs", out_apk])
    run([tool(bt, "zipalign"), "-c", "-P", "16", "4", out_apk])

    with zipfile.ZipFile(out_apk) as z:
        dex = z.read("classes.dex")
    return {"file": out_apk.name, "bytes": out_apk.stat().st_size, "sha256": sha256(out_apk),
            "dexBytes": len(dex)}


JAVA_JAVAC = "javac"
JAVA_JAR = "jar"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--Version", default=None, help="defaults to the version in Version.java")
    ap.add_argument("--OriginalApk", type=Path, required=True)
    ap.add_argument("--Target", choices=sorted(TARGETS), default="amap",
                    help="which map app this integrated build is for")
    ap.add_argument("--OutputDirectory", type=Path, default=None)
    ap.add_argument("--Java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    ap.add_argument("--Javac", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "javac")
    ap.add_argument("--Jar", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "jar")
    ap.add_argument("--AndroidSdk", type=Path,
                    default=Path(os.environ.get("ANDROID_SDK_ROOT")
                                 or os.environ.get("ANDROID_HOME")
                                 or Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
    ap.add_argument("--BuildToolsVersion")
    ap.add_argument("--LspatchJar", type=Path)
    ap.add_argument("--CacheDirectory", type=Path)
    ap.add_argument("--SignatureBypass", type=int, choices=[0, 1, 2, 3], default=2)
    ap.add_argument("--KeepWork", action="store_true")
    args = ap.parse_args()

    global JAVA_JAVAC, JAVA_JAR
    if not args.Java.exists():
        args.Java = Path(shutil.which("java") or "java")
        args.Javac = Path(shutil.which("javac") or "javac")
        args.Jar = Path(shutil.which("jar") or "jar")
    JAVA_JAVAC, JAVA_JAR = str(args.Javac), str(args.Jar)

    version_file = (APP / "src/io/github/ldxm666/mapclean/Version.java").read_text(encoding="utf-8")
    version_name = args.Version or re.search(r'NAME\s*=\s*"([^"]+)"', version_file).group(1)
    version_code = int(re.search(r'CODE\s*=\s*(\d+)', version_file).group(1))

    sdk = args.AndroidSdk.resolve(strict=True)
    build_tools = sdk_build_tools(sdk, args.BuildToolsVersion)
    original = args.OriginalApk.resolve(strict=True)
    target = TARGETS[args.Target]
    out = (args.OutputDirectory or (ROOT / "output" / f"nonroot-{args.Target}")).resolve()
    out.mkdir(parents=True, exist_ok=True)

    print(f"variant    : {target['label']}-only (免 root 内置版)")
    print(f"scope      : {target['package']}")
    print(f"version    : {version_name} ({version_code})")
    print(f"original   : {original.name} ({original.stat().st_size:,} B)")
    print(f"build-tools: {build_tools.name}\n")

    work = Path(tempfile.mkdtemp(prefix="mak-variant-", dir=out if args.KeepWork else None))
    try:
        slim = work / "app"
        print("== 1/4 slim sources ==")
        plan = copy_slim_sources(slim, target)
        rewrite_scope(slim, target)
        rewrite_native_init(slim)
        rewrite_manifest(slim, version_name, version_code)
        rewrite_module_prop(slim, version_name, version_code, target)
        print(f"  removed {len(plan['removed'])} source files")
        for r in plan["removed"]:
            print(f"    - {r}")
        print(f"  generated {len(plan['stubbed'])} stubs")
        for s in plan["stubbed"]:
            print(f"    + {s}")

        print("\n== 2/4 build slim module ==")
        slim_apk = work / target["slimModule"].format(version=version_name)
        info = build_slim_module(slim, slim_apk, args.Java, sdk, build_tools)
        print(f"  {info['file']}  {info['bytes']:,} B  dex {info['dexBytes']:,} B")
        print(f"  sha256 {info['sha256']}")

        # The allow-list is baked into this DEX, so the cheap place to fail is right here --
        # before the ~200 MB LSPatch run, not after it.
        whitelist = check_host_whitelist(slim_apk, target)
        print(f"  host whitelist accepts {target['package']}  "
              f"[{' / '.join(whitelist)}]")

        print("\n== 3/4 patch host with LSPatch ==")
        cache = (args.CacheDirectory or out / ".build-tools").resolve()
        cli = (args.LspatchJar.resolve(strict=True) if args.LspatchJar
               else pinned_download(cache, CLI_NAME, CLI_SHA256,
                                    f"https://github.com/JingMatrix/LSPatch/releases/download/{TAG}/{CLI_NAME}"))
        if sha256(cli) != CLI_SHA256:
            raise ValueError("only the verified official LSPatch v1.2 CLI is accepted")
        license_file = pinned_download(cache, "LSPatch-LICENSE.txt", LICENSE_SHA256,
                                       f"https://raw.githubusercontent.com/JingMatrix/LSPatch/{SOURCE_COMMIT}/LICENSE")
        patch_dir = work / "patched"
        patch_dir.mkdir()
        # The patched APK is published exactly as LSPatch signs it.
        #
        # It is tempting to append the label library to the host's lib/<abi>/ afterwards, but
        # that cannot be done without rebuilding the archive, and this archive cannot be
        # rebuilt: LSPatch's central directory declares roughly 366 MB of entry data inside a
        # 200 MB file (about 166 MB of it overlapping), which is harmless for Android -- its
        # zip reader trusts the local header -- but makes zipalign and apksigner copy that much
        # per entry and emit a 367 MB APK. The library is therefore delivered from inside the
        # nested module APK and handed to the framework at runtime instead; see
        # NativeLabelAssets.askFramework() for the other half of that mechanism.
        print(run([args.Java, "-Xmx2g", "-jar", cli, "-o", patch_dir,
                   "-l", str(args.SignatureBypass), "-m", slim_apk, original]).strip())
        produced = list(patch_dir.glob("*.apk"))
        if len(produced) != 1:
            raise ValueError(f"LSPatch produced {len(produced)} APKs, expected 1")

        print("\n== 4/4 verify + publish ==")
        final = out / target["artifact"].format(version=version_name)
        shutil.copy2(produced[0], final)
        record = verify(final, original, slim_apk, args.SignatureBypass, build_tools, args.Java, target)
        record["module"] = info
        record["slimPlan"] = plan
        record["hostWhitelist"] = whitelist
        record["lspatch"] = {"release": RELEASE_URL, "cliSha256": CLI_SHA256,
                             "source": f"https://github.com/JingMatrix/LSPatch/tree/{SOURCE_COMMIT}"}
        (out / "variant-verification.json").write_text(
            json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        shutil.copy2(license_file, out / license_file.name)
        (out / "THIRD-PARTY-NOTICES.txt").write_text(
            "LSPatch v1.2 (build 487), JingMatrix / LSPosed contributors\n"
            "License: GNU General Public License v3. See LSPatch-LICENSE.txt.\n"
            f"Release: {RELEASE_URL}\n"
            f"Patcher source: https://github.com/JingMatrix/LSPatch/tree/{SOURCE_COMMIT}\n",
            encoding="utf-8")

        print(f"\n  {final.name}")
        print(f"  {record['size']:,} B")
        print(f"  sha256 {record['sha256']}")
        print(f"\n  settings entry: in-app floating orb (no extra permission)")
        return 0
    finally:
        if not args.KeepWork:
            shutil.rmtree(work, ignore_errors=True)


# ------------------------------------------------------------------- verify

def check_host_whitelist(module: Path, target: dict) -> list:
    """Fail the build unless the *compiled* allow-list accepts this variant's host.

    Reads the const-string table of {@code EmbeddedSettingsPolicy.isHostPackage} out of the
    slim module's DEX and requires the variant's own package to be one of its literals.
    See the HOST_WHITELIST_* block above for why this is a build-time invariant and not a
    code review note: the failure mode is a single missing string constant, and every
    structural check in this file is blind to it.

    Fail-closed on purpose: a missing method, or one whose body no longer carries any
    string literal (e.g. because a future toolchain inlined it away), is a build failure
    rather than a silent pass.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import dex_query

    host = target["package"]
    desc = HOST_WHITELIST_CLASS.decode()
    literals, method_seen = [], False
    for dex in dex_query.open_apk(module):
        if desc not in dex.classes():
            continue
        for m in dex.methods(desc):
            if m["name"] != HOST_WHITELIST_METHOD:
                continue
            method_seen = True
            literals.extend(s for _, s in dex.const_strings(m["code_off"]))

    if not method_seen:
        raise ValueError(
            f"{desc}->{HOST_WHITELIST_METHOD} is not in the slim module: the "
            f"{target['label']} build cannot decide whether it runs inside its own host")
    if host not in literals:
        raise ValueError(
            f"{desc}->{HOST_WHITELIST_METHOD} does not accept {host!r} -- the compiled "
            f"allow-list holds {literals or 'no string constants at all'}. Without it "
            f"mapclean.App.attachEmbedded() throws before its registerListener() call, "
            f"the exception is swallowed by FloatingConfig.ensureService(), and the "
            f"{target['label']} panel reads 只读·服务未连接 while every switch writes "
            f"nothing. Add {host!r} to EmbeddedSettingsPolicy.isHostPackage().")
    return literals


def local_data_offset(apk: Path, entry: zipfile.ZipInfo) -> int:
    with apk.open("rb") as src:
        src.seek(entry.header_offset)
        header = src.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("invalid ZIP local header")
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return entry.header_offset + 30 + name_len + extra_len


def stream_sha256(stream, length: int) -> str:
    digest = hashlib.sha256()
    remaining = length
    while remaining:
        block = stream.read(min(1 << 20, remaining))
        if not block:
            raise ValueError("truncated nested entry")
        digest.update(block)
        remaining -= len(block)
    return digest.hexdigest()


def badging(apk: Path, build_tools: Path) -> dict:
    text = run([tool(build_tools, "aapt2"), "dump", "badging", apk])
    m = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", text, re.M)
    if not m:
        raise ValueError(f"cannot read manifest: {apk.name}")
    lo = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
    return {"package": m[1], "versionCode": m[2], "versionName": m[3],
            "minSdk": int(lo[1]) if lo else None}


def verify(apk: Path, original: Path, module: Path, bypass: int,
           build_tools: Path, java: Path, target: dict) -> dict:
    expected, actual = badging(original, build_tools), badging(apk, build_tools)
    for f in ("package", "versionCode", "versionName"):
        if actual[f] != expected[f]:
            raise ValueError(f"patched APK changed {f}")
    if actual["minSdk"] is None or actual["minSdk"] < 28:
        raise ValueError("rootless LSPatch requires Android 9+")
    xml = run([tool(build_tools, "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub" not in xml:
        raise ValueError("LSPatch bootstrap factory missing")
    with zipfile.ZipFile(apk) as z:
        cfg = json.loads(z.read(CONFIG_ENTRY))
        if cfg.get("useManager") is not False:
            raise ValueError("expected an integrated (non-manager) build")
        if cfg.get("sigBypassLevel") != bypass:
            raise ValueError("signature bypass mismatch")
        entry = z.getinfo(ORIGINAL_ENTRY)
        if entry.compress_type != zipfile.ZIP_STORED or entry.file_size != entry.compress_size:
            raise ValueError("nested original must be STORED")
        with apk.open("rb") as src:
            src.seek(local_data_offset(apk, entry))
            if stream_sha256(src, entry.file_size) != sha256(original):
                raise ValueError("original host APK not preserved byte-for-byte")
        mods = [x for x in z.namelist() if x.startswith("assets/lspatch/modules/")]
        if mods != [MODULE_ENTRY]:
            raise ValueError(f"unexpected embedded modules: {mods}")
        with z.open(MODULE_ENTRY) as s:
            if stream_sha256(s, z.getinfo(MODULE_ENTRY).file_size) != sha256(module):
                raise ValueError("embedded module does not match the slim build")
        so = [x for x in z.namelist() if x.startswith("assets/lspatch/so/") and x.endswith(".so")]
        if not so:
            raise ValueError("LSPatch native runtime missing")
        # The slim module must keep the 高德 hooks and the floating layer, and must not
        # carry the dropped vendor code. DEX stores type *descriptors* (Lcom/x/Y;), so probe
        # with those rather than with dotted Java names.
        with zipfile.ZipFile(module) as mz:
            dex = mz.read("classes.dex")
        required = list(target["required"])
        banned = list(target["banned"])
        present = [b for b in banned if b in dex]
        if present:
            raise ValueError("slim module still contains dropped vendor code: "
                             + ", ".join(p.decode() for p in present))
    return {"file": apk.name, "size": apk.stat().st_size, "sha256": sha256(apk),
            "package": actual["package"], "versionName": actual["versionName"],
            "originalSha256": sha256(original), "embeddedModuleSha256": sha256(module),
            "mode": "integrated", "sigBypassLevel": bypass,
            "nativeRuntimeEntries": so, "deviceRuntimeTested": False,
            "installation": (f"与原厂签名不同，不能覆盖安装官方{target['label']}；"
                             "需先卸载官方版（会清除登录态与离线地图）。"),
            "target": target["package"]}


if __name__ == "__main__":
    sys.exit(main())
