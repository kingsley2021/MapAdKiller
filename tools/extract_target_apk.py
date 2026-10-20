"""Extract the target app's DEX set and decompile the classes the module hooks.

Read-only with respect to the target APK; writes only under the given output dir.

Two products:
  dex/      the target's classesN.dex, byte-identical to what ships in the APK
  jadx/     decompiled .java for the hook target classes and the auxiliary classes
            that were needed to root-cause behaviour (row builders, unit helpers...)

Usage:
  python tools/extract_target_apk.py --TargetApk <amap.apk> --HooksJson <hook-points.json>
                                     --OutDir <dir> [--Extra com.x.Y ...]
"""
import argparse
import hashlib
import json
import shutil
import struct
import subprocess
import sys
import time
import zipfile
from pathlib import Path

JADX = r"D:\Tools\jadx\bin\jadx.bat"

# Classes that are not hook targets themselves but were required to establish the
# root cause of reported bugs, so a reviewer can follow the same reasoning.
EXTRA_CLASSES = [
    "g13",                                              # HomeCompanyBuilderV3 (设置家 row)
    "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.v3.ToolboxPanelV3",  # its only caller
    "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.b",                  # toolbox grid builder
    "gu2",                                              # real card-name extractor
    "dx1",                                              # design-px unit helper
]


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def dex_type_descriptors(blob):
    """Every type descriptor in a DEX (type_ids -> string table). Read-only, fast."""
    if blob[:4] not in (b"dex\n", b"cdex"):
        raise ValueError("not a dex")
    str_size, str_off = struct.unpack_from("<II", blob, 0x38)
    type_size, type_off = struct.unpack_from("<II", blob, 0x40)

    def string_at(index):
        p = struct.unpack_from("<I", blob, str_off + index * 4)[0]
        while True:
            b = blob[p]
            p += 1
            if not b & 0x80:
                break
        end = blob.find(b"\x00", p)
        return blob[p:end].decode("utf-8", "replace")

    return {string_at(struct.unpack_from("<I", blob, type_off + i * 4)[0])
            for i in range(type_size)}


def extract_dex(apk, out_dir):
    """Copy every classesN.dex out of the target APK, verifying round-trip equality."""
    out_dir.mkdir(parents=True, exist_ok=True)
    records = []
    with zipfile.ZipFile(apk) as z:
        for name in sorted(n for n in z.namelist() if n.endswith(".dex")):
            data = z.read(name)
            target = out_dir / name
            target.write_bytes(data)
            written = target.read_bytes()
            if written != data:
                raise ValueError(f"dex round-trip mismatch: {name}")
            records.append({"file": name, "bytes": len(data),
                            "sha256": hashlib.sha256(data).hexdigest()})
    return records


def decompile_one(apk, java_name, out_dir):
    """Decompile a single class; returns (relative_path, status).

    Resumable: if the class was already decompiled into out_dir it is reused, so a
    previously prepared jadx/ tree survives re-runs (each class costs ~17 s).
    """
    stem = java_name.rsplit(".", 1)[-1] + ".java"
    existing = sorted(out_dir.rglob(stem), key=lambda p: len(str(p))) if out_dir.exists() else []
    if existing:
        return existing[0], "reused"
    before = {p for p in out_dir.rglob("*.java")} if out_dir.exists() else set()
    try:
        proc = subprocess.run([JADX, "--single-class", java_name, "-d", str(out_dir), str(apk)],
                              capture_output=True, timeout=300)
    except subprocess.TimeoutExpired:
        return None, "timeout"
    after = {p for p in out_dir.rglob("*.java")}
    new = after - before
    if new:
        return sorted(new, key=lambda p: len(str(p)))[0], "ok"
    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout).decode("utf-8", "replace").strip()[-160:]
        return None, f"exit={proc.returncode} {tail}"
    hits = sorted(out_dir.rglob(stem), key=lambda p: len(str(p)))
    return (hits[0], "ok") if hits else (None, "no-output")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--TargetApk", required=True, type=Path)
    ap.add_argument("--HooksJson", type=Path, required=True)
    ap.add_argument("--OutDir", required=True, type=Path)
    ap.add_argument("--App", default="amap")
    ap.add_argument("--SkipDecompile", action="store_true")
    args = ap.parse_args()

    apk = args.TargetApk.resolve(strict=True)
    out = args.OutDir.resolve()
    # Keep an existing jadx/ tree: re-extracting the DEX must not force a re-decompile.
    jadx_keep = out / "jadx"
    stash = None
    if jadx_keep.is_dir():
        stash = out.parent / (out.name + "-jadx-stash")
        if stash.exists():
            shutil.rmtree(stash)
        shutil.move(str(jadx_keep), str(stash))
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    if stash is not None:
        shutil.move(str(stash), str(jadx_keep))

    apk_sha = sha256_file(apk)
    print(f"target APK : {apk.name}")
    print(f"           : {apk.stat().st_size:,} B")
    print(f"           : {apk_sha}\n")

    print("== extracting DEX ==")
    dex_records = extract_dex(apk, out / "dex")
    total = 0
    for r in dex_records:
        total += r["bytes"]
        print(f"  {r['file']:14s} {r['bytes']:>12,} B  {r['sha256'][:16]}")
    print(f"  total {total:,} B ({total/1024/1024:.1f} MB)\n")

    hooks = json.loads(args.HooksJson.read_text(encoding="utf-8"))["hooks"]

    def app_of(name):
        if name.startswith("com.baidu."):
            return "baidu"
        if name.startswith(("com.tencent.", "com.qq.e.")):
            return "tencent"
        return "amap"

    targets = sorted({h["target"] for h in hooks
                      if not h["target"].startswith("defpackage") and app_of(h["target"]) == args.App})
    todo = targets + [c for c in EXTRA_CLASSES if c not in targets]

    # Which dex holds each target class (evidence that the hook point is real).
    print(f"== locating {len(targets)} hook target classes ==")
    dex_types = {}
    for rec in dex_records:
        dex_types[rec["file"]] = dex_type_descriptors((out / "dex" / rec["file"]).read_bytes())
    located = []
    for t in targets:
        want = "L" + t.replace(".", "/") + ";"
        exact = [n for n, s in sorted(dex_types.items()) if want in s]
        if exact:
            kind, where = "exact", exact
        else:
            inner = [n for n, s in sorted(dex_types.items())
                     if any(x.startswith(want[:-1] + "$") for x in s)]
            kind, where = ("inner", inner) if inner else ("absent", [])
        located.append({"target": t, "dex": where, "match": kind})
        mark = {"exact": "OK   ", "inner": "INNER", "absent": "ABSENT"}[kind]
        print(f"  {mark} {t:74s} {','.join(where)}")
    present = sum(1 for r in located if r["match"] != "absent")
    print(f"  present {present}/{len(located)}\n")

    results = []
    if not args.SkipDecompile:
        print(f"== decompiling {len(todo)} classes (resumable, ~17s each) ==")
        jadx_dir = out / "jadx"
        jadx_dir.mkdir(parents=True, exist_ok=True)
        for i, cls in enumerate(todo, 1):
            t0 = time.time()
            path, status = decompile_one(apk, cls, jadx_dir)
            rel = path.relative_to(out).as_posix() if path else None
            results.append({"class": cls, "file": rel, "status": status})
            mark = "OK " if status in ("ok", "reused") else "!! "
            print(f"  [{i:2d}/{len(todo)}] {mark}{cls:72s} {status:6s} {int(time.time()-t0):2d}s")
    else:
        for cls in todo:
            stem = cls.rsplit(".", 1)[-1] + ".java"
            hits = sorted((out / "jadx").rglob(stem), key=lambda p: len(str(p)))
            results.append({"class": cls,
                            "file": hits[0].relative_to(out).as_posix() if hits else None,
                            "status": "present" if hits else "skipped"})
    print()

    manifest = {
        "targetApk": apk.name,
        "targetApkBytes": apk.stat().st_size,
        "targetApkSha256": apk_sha,
        "app": args.App,
        "dex": dex_records,
        "hookTargets": len(targets),
        "targets": located,
        "decompiled": results,
        "note": ("DEX 与目标 APK 内文件字节一致；jadx/ 为对应类的反编译结果，"
                 "仅供阅读，不作为构建输入。"),
    }
    (out / "TARGET-MANIFEST.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    ok = sum(1 for r in results if r["status"] in ("ok", "reused", "present"))
    print(f"decompiled available: {ok}/{len(todo)}")
    print(f"output       : {out}")
    return 0 if ok == len(todo) or args.SkipDecompile else 1


if __name__ == "__main__":
    sys.exit(main())
