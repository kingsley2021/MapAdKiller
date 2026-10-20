"""Locate the module's hook target classes inside the target app's DEX set.

Read-only. For every hook target this answers: does the class actually exist in the
installed 高德 build, and if so which classesN.dex holds it. That mapping is the
evidence that a hook point is real rather than inherited from an older version.

Also provides a jadx single-class decompile helper so the delivery pack can ship the
decompiled form of exactly the classes the module touches.
"""
import json
import re
import struct
import subprocess
import zipfile
from pathlib import Path

JADX = r"D:\Tools\jadx\bin\jadx.bat"


# ------------------------------------------------------------------ DEX reading

def dex_type_descriptors(blob):
    """Every type descriptor in a DEX, as a set (fast: type_ids -> string table)."""
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


def descriptor(java_name):
    """com.x.Y -> Lcom/x/Y;   (nested classes keep their $ form)"""
    return "L" + java_name.replace(".", "/") + ";"


def scan_apk(apk):
    """Return {dex_name: (size, sha256, descriptor_set)} for every dex in the APK."""
    import hashlib
    out = {}
    with zipfile.ZipFile(apk) as z:
        for name in sorted(n for n in z.namelist() if n.endswith(".dex")):
            blob = z.read(name)
            out[name] = {
                "size": len(blob),
                "sha256": hashlib.sha256(blob).hexdigest(),
                "types": dex_type_descriptors(blob),
            }
    return out


def locate(dexes, java_name):
    """Which dex files declare this class? Handles nested classes via prefix match."""
    want = descriptor(java_name)
    exact = [n for n, d in sorted(dexes.items()) if want in d["types"]]
    if exact:
        return exact, "exact"
    # A hook may target an outer class while the method lives on an inner one.
    prefix = want[:-1] + "$"
    inner = [n for n, d in sorted(dexes.items())
             if any(t.startswith(prefix) for t in d["types"])]
    return inner, "inner" if inner else "absent"


# ---------------------------------------------------------------- decompilation

def decompile(apk, java_name, out_dir, timeout=240):
    """Decompile one class with jadx. Returns the produced .java path or None."""
    try:
        proc = subprocess.run(
            [JADX, "--single-class", java_name, "-d", str(out_dir), str(apk)],
            capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return None, "timeout"
    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout).decode("utf-8", "replace")[-200:]
        return None, f"exit={proc.returncode} {tail}"
    stem = java_name.rsplit(".", 1)[-1] + ".java"
    hits = list(Path(out_dir).rglob(stem))
    return (hits[0], "ok") if hits else (None, "no-output")


def app_of(java_name):
    """Which map app does this hook target belong to?

    The target DEX set only contains 高德 classes, so Baidu/Tencent targets are
expected to be absent here -- they must be resolved against their own APK.
    """
    if java_name.startswith(("com.baidu.",)):
        return "baidu"
    if java_name.startswith(("com.tencent.", "com.qq.e.")):
        return "tencent"
    return "amap"


def main():
    import argparse
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--TargetApk", required=True, type=Path)
    ap.add_argument("--HooksJson", type=Path, required=True)
    ap.add_argument("--App", default="amap",
                    help="only verify hooks belonging to this app (amap/baidu/tencent)")
    ap.add_argument("--OutJson", type=Path)
    args = ap.parse_args()

    hooks = json.loads(args.HooksJson.read_text(encoding="utf-8"))["hooks"]
    allt = sorted({h["target"] for h in hooks if not h["target"].startswith("defpackage")})
    targets = [t for t in allt if app_of(t) == args.App]
    skipped = len(allt) - len(targets)
    dexes = scan_apk(args.TargetApk)

    print(f"target APK : {args.TargetApk.name} ({args.TargetApk.stat().st_size:,} B)")
    print(f"dex files  : {len(dexes)}")
    for n, d in sorted(dexes.items()):
        print(f"  {n:14s} {d['size']:>12,} B  {d['sha256'][:16]}")
    print(f"\nhook targets for '{args.App}': {len(targets)}"
          f"  (skipped {skipped} belonging to other apps)\n")

    rows = []
    for t in targets:
        where, kind = locate(dexes, t)
        rows.append({"target": t, "dex": where, "match": kind})
        flag = {"exact": "OK   ", "inner": "INNER", "absent": "ABSENT"}[kind]
        print(f"  {flag} {t:78s} {','.join(where)}")

    absent = [r["target"] for r in rows if r["match"] == "absent"]
    print(f"\npresent: {len(rows) - len(absent)} / {len(rows)}")
    if absent:
        print("ABSENT (hook would MISS on this build):")
        for a in absent:
            print(f"  - {a}")

    if args.OutJson:
        args.OutJson.parent.mkdir(parents=True, exist_ok=True)
        args.OutJson.write_text(json.dumps({
            "targetApk": args.TargetApk.name,
            "targetApkBytes": args.TargetApk.stat().st_size,
            "targetApkSha256": __import__("hashlib").sha256(
                args.TargetApk.read_bytes()).hexdigest(),
            "app": args.App,
            "dex": {n: {"size": d["size"], "sha256": d["sha256"]}
                    for n, d in sorted(dexes.items())},
            "targets": rows,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"\nwrote {args.OutJson}")


if __name__ == "__main__":
    main()
