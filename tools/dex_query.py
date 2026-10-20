#!/usr/bin/env python3
"""Minimal DEX reader for hook-target verification.

Answers the questions that keep coming up when a hook installs but never fires:

  * which DEX file defines a class (globally, not just in the first one found)
  * the exact method list of a class, with access flags and descriptors
  * which classes a method body actually calls

Scope is deliberately narrow: enough to confirm that a hook target exists with the
signature the module expects. It is not a disassembler.

DEX layout note (this is where hand-rolled parsers usually break -- see the offsets below,
straight from the format spec; the header is 0x70 bytes and the id tables follow in a fixed
order, so string_ids_off lives at 0x3c, type_ids at 0x44, proto at 0x4c, field at 0x54,
method at 0x5c and class_defs at 0x64):

  0x38 string_ids_size   0x3c string_ids_off
  0x40 type_ids_size     0x44 type_ids_off
  0x48 proto_ids_size    0x4c proto_ids_off
  0x50 field_ids_size    0x54 field_ids_off
  0x58 method_ids_size   0x5c method_ids_off
  0x60 class_defs_size   0x64 class_defs_off

Usage:
  python tools/dex_query.py --Apk <apk> --Class Lcom/x/Y;            # where is it defined
  python tools/dex_query.py --Apk <apk> --Class Lcom/x/Y; --Methods # list its methods
  python tools/dex_query.py --Apk <apk> --Find substring            # search type descriptors
"""
from __future__ import annotations

import argparse
import bisect
import struct
import sys
import zipfile
from pathlib import Path

# ---- access flags we care about ------------------------------------------------
ACC_PUBLIC = 0x1
ACC_STATIC = 0x8
ACC_NATIVE = 0x100
ACC_ABSTRACT = 0x400

NO_INDEX = 0xFFFFFFFF

# Instruction width in 16-bit code units, indexed by opcode. Payload pseudo-instructions
# (opcode 0x00 with a non-zero high byte) are handled separately in Dex.code().
WIDTH = bytes([
    1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, 1,   # 0x00 nop .. 0x0f return
    1, 1, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2, 1, 1, 2,   # 0x10 .. 0x1f check-cast
    2, 1, 2, 2, 3, 3, 3, 1, 1, 2, 3, 3, 3, 2, 2, 2,   # 0x20 instance-of .. 0x2f cmpg-double
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1,   # 0x30 cmpl-double .. 0x3f unused
    1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,   # 0x40 .. 0x4f aget/aput
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,   # 0x50 aput .. 0x5f iput
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3,   # 0x60 sget .. 0x6f invoke-interface
    3, 3, 3, 3, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,   # 0x70 invoke-*/range .. 0x7f
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,   # 0x80 neg-int .. 0x8f
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,   # 0x90 add-int .. 0x9f
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,   # 0xa0 .. 0xaf
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,   # 0xb0 add-int/lit16 .. 0xbf
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,   # 0xc0 .. 0xcf
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,   # 0xd0 .. 0xdf
    2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,   # 0xe0 .. 0xef (0xe3+ unused)
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4, 3, 3, 2, 2,   # 0xf0 .. 0xff polymorphic/custom
])

INVOKE_KINDS = {0x6E: "virtual", 0x6F: "super", 0x70: "direct", 0x71: "static",
                0x72: "interface", 0x74: "virtual/range", 0x75: "super/range",
                0x76: "direct/range", 0x77: "static/range", 0x78: "interface/range",
                0xFA: "polymorphic", 0xFB: "polymorphic/range",
                0xFC: "custom", 0xFD: "custom/range"}
FIELD_OPS = {0x52: "iget", 0x53: "iget-wide", 0x54: "iget-object", 0x55: "iget-boolean",
             0x56: "iget-byte", 0x57: "iget-char", 0x58: "iget-short",
             0x59: "iput", 0x5A: "iput-wide", 0x5B: "iput-object", 0x5C: "iput-boolean",
             0x5D: "iput-byte", 0x5E: "iput-char", 0x5F: "iput-short",
             0x60: "sget", 0x61: "sget-wide", 0x62: "sget-object", 0x63: "sget-boolean",
             0x64: "sget-byte", 0x65: "sget-char", 0x66: "sget-short",
             0x67: "sput", 0x68: "sput-wide", 0x69: "sput-object", 0x6A: "sput-boolean",
             0x6B: "sput-byte", 0x6C: "sput-char", 0x6D: "sput-short"}


class Dex:
    def __init__(self, data: bytes, name: str):
        self.data = data
        self.name = name
        if data[:4] != b"dex\n":
            raise ValueError(f"{name}: not a DEX file")
        (self.n_string, self.off_string,
         self.n_type, self.off_type,
         self.n_proto, self.off_proto,
         self.n_field, self.off_field,
         self.n_method, self.off_method,
         self.n_class, self.off_class) = struct.unpack_from("<12I", data, 0x38)
        self._strings = None
        self._classes = None
        self._methods = {}
        self._ranges = None

    # -- primitives ----------------------------------------------------------
    def uleb(self, offset: int) -> tuple[int, int]:
        result = 0
        shift = 0
        while True:
            byte = self.data[offset]
            offset += 1
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result, offset
            shift += 7

    @property
    def strings(self) -> list[str]:
        if self._strings is None:
            out = []
            data = self.data
            for i in range(self.n_string):
                so = struct.unpack_from("<I", data, self.off_string + i * 4)[0]
                # The declared uleb128 length is a count of UTF-16 code units, not bytes.
                # Slicing the MUTF-8 payload by it truncates every non-ASCII string, so the
                # terminator is what actually delimits it (MUTF-8 escapes NUL as C0 80 and
                # therefore never contains a raw 0x00).
                _, pos = self.uleb(so)
                end = data.find(b"\x00", pos)
                out.append(data[pos:end].decode("utf-8", "replace"))
            self._strings = out
        return self._strings

    def type_desc(self, index: int) -> str:
        si = struct.unpack_from("<I", self.data, self.off_type + index * 4)[0]
        return self.strings[si]

    def method(self, index: int) -> tuple[str, str, int]:
        """-> (defining class descriptor, method name, proto index)"""
        base = self.off_method + index * 8
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", self.data, base)
        return self.type_desc(class_idx), self.strings[name_idx], proto_idx

    def proto(self, index: int) -> tuple[str, str, int]:
        """-> (shorty, return type, parameters-offset)"""
        base = self.off_proto + index * 12
        shorty_idx, return_idx, params_off = struct.unpack_from("<III", self.data, base)
        return self.strings[shorty_idx], self.type_desc(return_idx), params_off

    def params(self, proto_index: int) -> list[str]:
        _, _, off = self.proto(proto_index)
        if off == 0:
            return []
        count = struct.unpack_from("<I", self.data, off)[0]
        return [self.type_desc(struct.unpack_from("<H", self.data, off + 4 + i * 2)[0])
                for i in range(count)]

    def signature(self, proto_index: int) -> str:
        _, ret, _ = self.proto(proto_index)
        return f"({''.join(self.params(proto_index))}){ret}"

    # -- classes -------------------------------------------------------------
    def classes(self) -> dict[str, dict]:
        # Cached: an xref scan walks every class of every DEX, and rebuilding the table
        # per class turns that into quadratic work (this used to take minutes).
        if self._classes is not None:
            return self._classes
        out = {}
        for i in range(self.n_class):
            base = self.off_class + i * 32
            (class_idx, access, super_idx, _interfaces, _src,
             _anno, class_data_off, _static) = struct.unpack_from("<8I", self.data, base)
            desc = self.type_desc(class_idx)
            super_desc = self.type_desc(super_idx) if super_idx != NO_INDEX else None
            out[desc] = {
                "access": access,
                "super": super_desc,
                "class_data_off": class_data_off,
            }
        self._classes = out
        return out

    def methods(self, desc: str) -> list[dict]:
        """All declared methods (direct + virtual) of one class, in DEX order.

        class_data_item layout, in order:
          uleb static_fields_size, instance_fields_size, direct_methods_size,
          virtual_methods_size; then that many encoded_field, then that many
          encoded_method. Indices are stored as deltas against the previous entry, and
          encoded_field entries must be walked past before the methods start.
        """
        info = self.classes().get(desc)
        if info is None:
            raise KeyError(desc)
        cached = self._methods.get(desc)
        if cached is not None:
            return cached
        off = info["class_data_off"]
        if off == 0:
            self._methods[desc] = []
            return []
        static_fields, off = self.uleb(off)
        instance_fields, off = self.uleb(off)
        direct, off = self.uleb(off)
        virtual, off = self.uleb(off)

        # Skip the two encoded_field blocks: each is (field_idx_diff, access_flags).
        for _ in range(static_fields + instance_fields):
            _, off = self.uleb(off)
            _, off = self.uleb(off)

        out = []
        # method_idx_diff is relative to the previous method *in the same list*, so the
        # direct and virtual runs each restart from zero. Carrying one cursor across both
        # walks past the end of method_ids and blows up on an out-of-range string index.
        for count in (direct, virtual):
            cursor = 0
            for _ in range(count):
                index_diff, off = self.uleb(off)
                access, off = self.uleb(off)
                code_off, off = self.uleb(off)
                cursor += index_diff
                cls, name, proto_idx = self.method(cursor)
                out.append({"access": access, "code_off": code_off, "index": cursor,
                            "class": cls, "name": name,
                            "signature": self.signature(proto_idx)})
        self._methods[desc] = out
        return out

    def fields(self, desc: str) -> list[dict]:
        """Declared fields (static + instance) of one class."""
        info = self.classes().get(desc)
        if info is None:
            raise KeyError(desc)
        off = info["class_data_off"]
        if off == 0:
            return []
        static_count, off = self.uleb(off)
        instance_count, off = self.uleb(off)
        # The class-data header also includes direct/virtual method counts.
        # Field entries begin only after all four counts, even for --Fields.
        _, off = self.uleb(off)
        _, off = self.uleb(off)
        out = []
        for kind, count in (("static", static_count), ("instance", instance_count)):
            cursor = 0
            for _ in range(count):
                diff, off = self.uleb(off)
                access, off = self.uleb(off)
                cursor += diff
                base = self.off_field + cursor * 8
                class_idx, type_idx, name_idx = struct.unpack_from("<HHI", self.data, base)
                out.append({"kind": kind, "access": access,
                            "owner": self.type_desc(class_idx),
                            "type": self.type_desc(type_idx),
                            "name": self.strings[name_idx]})
        return out

    def method_calls(self, code_off: int) -> list[str]:
        """Method references appearing in a method body's instruction stream."""
        if code_off == 0:
            return []
        if len(self.data) < code_off + 16:
            return []
        insns_size = struct.unpack_from("<I", self.data, code_off + 12)[0]
        base = code_off + 16
        calls = []
        i = 0
        while i < insns_size:
            op = self.data[base + i * 2]
            if op == 0x00:              # nop / payload marker
                if i + 1 < insns_size:
                    hi = self.data[base + i * 2 + 1]
                    if hi == 0x00 or hi == 0x01 or hi == 0x02 or hi == 0x03:
                        # payload: skip to a 4-byte boundary past its size
                        size = struct.unpack_from("<H", self.data, base + i * 2 + 2)[0]
                        i += (size * 2) + 4
                        continue
                i += 1
            elif op in (0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
                # invoke-kind, format 35c
                idx = struct.unpack_from("<H", self.data, base + i * 2 + 2)[0]
                try:
                    cls, name, proto_idx = self.method(idx)
                    calls.append(f"{cls}->{name}{self.signature(proto_idx)}")
                except Exception:
                    pass
                i += 3
            elif op in (0x6B, 0x6C, 0x6D):   # 3rc
                idx = struct.unpack_from("<H", self.data, base + i * 2 + 2)[0]
                try:
                    cls, name, proto_idx = self.method(idx)
                    calls.append(f"{cls}->{name}{self.signature(proto_idx)}")
                except Exception:
                    pass
                i += 3
            elif op in (0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32,
                        0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D,
                        0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44):
                i += 1
            elif op in (0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
                        0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
                        0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2A):
                i += 1
            else:
                i += 1
        return calls

    # -- code items ----------------------------------------------------------
    def code(self, code_off: int) -> list[tuple[int, int, int]]:
        """Decode a code_item into (unit_index, opcode, width) triples.

        Payload pseudo-instructions (opcode 0x00 with a non-zero high byte) are stepped
        over using their own declared size, otherwise a packed-switch table would be
        misread as a run of nops and every later instruction would drift.
        """
        if code_off == 0 or len(self.data) < code_off + 16:
            return []
        insns_size = struct.unpack_from("<I", self.data, code_off + 12)[0]
        base = code_off + 16
        out: list[tuple[int, int, int]] = []
        i = 0
        while i < insns_size:
            word = struct.unpack_from("<H", self.data, base + i * 2)[0]
            op = word & 0xFF
            kind = word >> 8
            if op == 0x00 and kind in (0x01, 0x02, 0x03):
                if kind == 0x01:                                    # packed-switch
                    size = struct.unpack_from("<H", self.data, base + i * 2 + 2)[0]
                    step = size * 2 + 4
                elif kind == 0x02:                                  # sparse-switch
                    size = struct.unpack_from("<H", self.data, base + i * 2 + 2)[0]
                    step = size * 4 + 4
                else:                                               # fill-array-data
                    elem = struct.unpack_from("<H", self.data, base + i * 2 + 2)[0]
                    size = struct.unpack_from("<I", self.data, base + i * 2 + 4)[0]
                    step = ((size * elem + 1) // 2) * 2 + 8
                out.append((i, -1, step))
                i += step
                continue
            w = WIDTH[op]
            out.append((i, op, w))
            i += w
        return out

    def field(self, index: int) -> tuple[str, str, str]:
        """-> (defining class descriptor, field name, field type)"""
        base = self.off_field + index * 8
        class_idx, type_idx, name_idx = struct.unpack_from("<HHI", self.data, base)
        return self.type_desc(class_idx), self.strings[name_idx], self.type_desc(type_idx)

    def const_strings(self, code_off: int) -> list[tuple[int, str]]:
        """Every const-string / const-string/jumbo literal in a method body."""
        out: list[tuple[int, str]] = []
        for i, op, _ in self.code(code_off):
            if op not in (0x1A, 0x1B):
                continue
            base = code_off + 16 + i * 2
            idx = (struct.unpack_from("<H", self.data, base + 2)[0] if op == 0x1A
                   else struct.unpack_from("<I", self.data, base + 2)[0])
            if idx < self.n_string:
                out.append((i, self.strings[idx]))
        return out

    def invokes(self, code_off: int) -> list[tuple[int, str, str, str]]:
        """Every invoke-* -> (unit_index, kind, target, signature)."""
        out: list[tuple[int, str, str, str]] = []
        for i, op, _ in self.code(code_off):
            if op not in INVOKE_KINDS:
                continue
            base = code_off + 16 + i * 2
            idx = struct.unpack_from("<H", self.data, base + 2)[0]
            if idx >= self.n_method:
                continue
            cls, name, proto_idx = self.method(idx)
            out.append((i, INVOKE_KINDS[op], f"{cls}->{name}", self.signature(proto_idx)))
        return out

    def field_accesses(self, code_off: int) -> list[tuple[int, str, str, str, str]]:
        """Every iget/iput/sget/sput -> (unit_index, op name, owner, field, type)."""
        out: list[tuple[int, str, str, str, str]] = []
        for i, op, _ in self.code(code_off):
            if op not in FIELD_OPS:
                continue
            base = code_off + 16 + i * 2
            idx = struct.unpack_from("<H", self.data, base + 2)[0]
            if idx >= self.n_field:
                continue
            owner, name, ftype = self.field(idx)
            out.append((i, FIELD_OPS[op], owner, name, ftype))
        return out

    # -- fast byte-level location --------------------------------------------
    def code_ranges(self) -> list[tuple[int, int, str, dict]]:
        """Sorted [(insn_start, insn_end, class, method)] for every method with a body.

        Built once so a raw opcode scan can be resolved back to an owning method by
        binary search instead of decoding every instruction stream in the DEX.
        """
        if self._ranges is not None:
            return self._ranges
        out: list[tuple[int, int, str, dict]] = []
        for desc in self.classes():
            try:
                methods = self.methods(desc)
            except Exception:
                continue
            for m in methods:
                code_off = m["code_off"]
                if not code_off or len(self.data) < code_off + 16:
                    continue
                size = struct.unpack_from("<I", self.data, code_off + 12)[0]
                out.append((code_off + 16, code_off + 16 + size * 2, desc, m))
        out.sort()
        self._ranges = out
        return out

    def method_at(self, offset: int):
        """Which (class, method) body contains this file offset, if any."""
        ranges = self.code_ranges()
        i = bisect.bisect_right(ranges, (offset, 1 << 62)) - 1
        if i >= 0 and ranges[i][0] <= offset < ranges[i][1]:
            return ranges[i][2], ranges[i][3]
        return None, None

    def disasm(self, code_off: int) -> list[str]:
        """Linear listing of a method body: the ops that matter for hook design.

        Only constant loads, invokes, field accesses and class/instance construction are
        rendered with resolved operands -- that is what identifies the exact branch a hook
        has to intercept. Everything else prints as a bare mnemonic so the listing stays
        readable.
        """
        if code_off == 0:
            return []
        base = code_off + 16
        out = []
        for i, op, width in self.code(code_off):
            at = base + i * 2
            if op == -1:
                out.append(f"  [{i:5d}] payload ({width} units)")
                continue
            w0 = struct.unpack_from("<H", self.data, at)[0]
            text = None
            if op in (0x1A, 0x1B):
                idx = (struct.unpack_from("<H", self.data, at + 2)[0] if op == 0x1A
                       else struct.unpack_from("<I", self.data, at + 2)[0])
                if idx < self.n_string:
                    text = f'const-string "{self.strings[idx]}"'
            elif op in (0x1C, 0x22):
                idx = struct.unpack_from("<H", self.data, at + 2)[0]
                if idx < self.n_type:
                    text = ("const-class " if op == 0x1C else "new-instance ") + self.type_desc(idx)
            elif op in INVOKE_KINDS:
                idx = struct.unpack_from("<H", self.data, at + 2)[0]
                if idx < self.n_method:
                    cls, name, proto = self.method(idx)
                    text = f"invoke-{INVOKE_KINDS[op]} {cls}->{name}{self.signature(proto)}"
            elif op in FIELD_OPS:
                idx = struct.unpack_from("<H", self.data, at + 2)[0]
                if idx < self.n_field:
                    owner, name, ftype = self.field(idx)
                    text = f"{FIELD_OPS[op]} {owner}->{name}:{ftype}"
            elif op in (0x28, 0x29, 0x2A):
                text = "goto"
            elif 0x32 <= op <= 0x3D:
                text = "if-*"
            elif 0x0E <= op <= 0x11:
                text = {0x0E: "return-void", 0x0F: "return", 0x10: "return-wide",
                        0x11: "return-object"}[op]
            if text is None:
                text = f"op_{op:02x}"
            out.append(f"  [{i:5d}] {text}")
        return out


def xref(dexes, text, package=None, exact=False, mode="string", limit=200, dex_name=None):
    """Find method bodies that reference `text`.

    mode='string' -> const-string literal; mode='invoke' -> invoke target `Class->name`.

    The string mode scans raw DEX bytes for the const-string opcode carrying a matching
    string index and resolves the hit back to its owning method by binary search over
    code_item ranges. Decoding every instruction stream instead takes minutes per DEX;
    this takes under a second and stays exact because a hit is only reported when the
    offset really falls inside a method body.
    """
    hits = []
    seen = set()
    for d in dexes:
        if dex_name and d.name != dex_name:
            continue
        if mode == "string":
            want = {i for i, s in enumerate(d.strings) if (s == text if exact else text in s)}
            if not want:
                continue
            data = d.data
            for op, width in ((0x1A, 2), (0x1B, 4)):
                pos = data.find(bytes([op]))
                while pos >= 0:
                    if pos % 2 == 0 and pos + 2 + width <= len(data):
                        if width == 2:
                            idx = data[pos + 2] | (data[pos + 3] << 8)
                        else:
                            idx = (data[pos + 2] | (data[pos + 3] << 8)
                                   | (data[pos + 4] << 16) | (data[pos + 5] << 24))
                        if idx in want:
                            desc, m = d.method_at(pos)
                            if desc and (not package or desc.startswith(package)):
                                key = (desc, m["name"], m["signature"])
                                if key not in seen:
                                    seen.add(key)
                                    hits.append((d.name, desc, m, d.strings[idx]))
                                    if len(hits) >= limit:
                                        return hits
                    pos = data.find(bytes([op]), pos + 1)
        elif mode == "field":
            # Same raw-opcode trick as const-string: every iget/iput/sget/sput form puts the
            # field index in the second code unit, so a byte scan plus a code-range lookup
            # replaces decoding every method body in the DEX.
            want = set()
            for fi in range(d.n_field):
                owner, name, ftype = d.field(fi)
                probe = f"{owner}->{name}:{ftype}"
                if (f"{owner}->{name}" == text) if exact else (text in probe):
                    want.add(fi)
            if not want:
                continue
            data = d.data
            for op in FIELD_OPS:
                pos = data.find(bytes([op]))
                while pos >= 0:
                    if pos % 2 == 0 and pos + 4 <= len(data):
                        idx = data[pos + 2] | (data[pos + 3] << 8)
                        if idx in want:
                            desc, m = d.method_at(pos)
                            if desc and (not package or desc.startswith(package)):
                                key = (desc, m["name"], m["signature"])
                                if key not in seen:
                                    seen.add(key)
                                    owner, name, ftype = d.field(idx)
                                    hits.append((d.name, desc, m,
                                                 f"{FIELD_OPS[op]} {owner}->{name}:{ftype}"))
                                    if len(hits) >= limit:
                                        return hits
                    pos = data.find(bytes([op]), pos + 1)
        else:
            for desc in d.classes():
                if package and not desc.startswith(package):
                    continue
                try:
                    methods = d.methods(desc)
                except Exception:
                    continue
                for m in methods:
                    if not m["code_off"]:
                        continue
                    if mode == "invoke":
                        refs = [(kind, target, sig)
                                for _, kind, target, sig in d.invokes(m["code_off"])]
                    else:
                        refs = [(op, owner + "->" + name, ":" + ftype)
                                for _, op, owner, name, ftype in d.field_accesses(m["code_off"])]
                    for kind, target, sig in refs:
                        probe = target if exact else target + sig
                        if (target == text) if exact else (text in probe):
                            key = (desc, m["name"], m["signature"])
                            if key not in seen:
                                seen.add(key)
                                hits.append((d.name, desc, m, f"{kind} {target}{sig}"))
                                if len(hits) >= limit:
                                    return hits
                            break
    return hits


def open_apk(path: Path) -> list[Dex]:
    if path.suffix.lower() == ".dex":
        return [Dex(path.read_bytes(), path.name)]
    with zipfile.ZipFile(path) as z:
        names = sorted(n for n in z.namelist() if n.endswith(".dex"))
        return [Dex(z.read(n), n) for n in names]


def flags(access: int) -> str:
    out = []
    if access & ACC_PUBLIC: out.append("public")
    if access & ACC_STATIC: out.append("static")
    if access & ACC_NATIVE: out.append("native")
    if access & ACC_ABSTRACT: out.append("abstract")
    return " ".join(out) or "package"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--Apk", type=Path, required=True)
    ap.add_argument("--Class", help="type descriptor, e.g. Lcom/x/Y;")
    ap.add_argument("--Methods", action="store_true")
    ap.add_argument("--Calls", action="store_true", help="with --Methods, list invoked methods")
    ap.add_argument("--Find", help="substring to search for in type descriptors")
    ap.add_argument("--Fields", action="store_true", help="list declared fields instead")
    ap.add_argument("--Filter", help="with --Methods, only methods whose name contains this")
    ap.add_argument("--Strings", action="store_true",
                    help="with --Methods, also print each method's const-string literals")
    ap.add_argument("--Xref", help="find every method whose body loads a matching string literal")
    ap.add_argument("--InvokeXref", help="find every method whose body invokes a matching target")
    ap.add_argument("--FieldXref", help="find every method whose body reads/writes a matching field")
    ap.add_argument("--Package", help="restrict --Xref/--InvokeXref to a class descriptor prefix")
    ap.add_argument("--Dex", help="restrict --Xref/--InvokeXref to one DEX entry, e.g. classes4.dex")
    ap.add_argument("--Dump", help="with --Class, linear listing of the named method body")
    ap.add_argument("--Exact", action="store_true", help="exact match for --Xref/--InvokeXref")
    args = ap.parse_args()

    dexes = open_apk(args.Apk)
    print(f"{len(dexes)} dex file(s) in {args.Apk.name}\n")

    if args.Xref or args.InvokeXref or args.FieldXref:
        text = args.Xref or args.InvokeXref or args.FieldXref
        mode = "string" if args.Xref else ("field" if args.FieldXref else "invoke")
        hits = xref(dexes, text, package=args.Package, exact=args.Exact, mode=mode,
                    dex_name=args.Dex)
        print(f"{len(hits)} method(s) referencing {text!r} ({mode}):\n")
        for dex_name, desc, m, found in hits:
            print(f"  {dex_name:15s} {desc}")
            print(f"      {flags(m['access'])} {m['name']}{m['signature']}"
                  f"   code_off=0x{m['code_off']:x}")
            print(f"      << {found}")
        return 0

    if args.Find:
        needle = args.Find.lower()
        hits = 0
        for d in dexes:
            for desc in d.classes():
                if needle in desc.lower():
                    print(f"  {d.name:16s} {desc}")
                    hits += 1
        print(f"\n{hits} match(es)")
        return 0

    if not args.Class:
        ap.error("--Class or --Find is required")
    desc = args.Class
    if not desc.startswith("L"):
        desc = "L" + desc.replace(".", "/") + ";"

    if args.Dump:
        for d in dexes:
            if desc not in d.classes():
                continue
            for m in d.methods(desc):
                if m["name"] != args.Dump:
                    continue
                print(f"=== {d.name}  {desc}->{m['name']}{m['signature']}"
                      f"  code_off=0x{m['code_off']:x}")
                for line in d.disasm(m["code_off"]):
                    print(line)
        return 0

    owners = [d for d in dexes if desc in d.classes()]
    if not owners:
        print(f"NOT FOUND: {desc}")
        return 1
    print(f"{desc} defined in: {[d.name for d in owners]}"
          + ("   <-- DUPLICATE across dex files" if len(owners) > 1 else ""))

    if args.Fields:
        for d in owners:
            fs = d.fields(desc)
            print(f"\n=== {d.name}  {len(fs)} fields ===")
            for f in fs:
                print(f"  {f['kind']:8s} {f['type']:58s} {f['name']}")
        return 0

    if args.Methods or args.Calls:
        for d in owners:
            info = d.classes()[desc]
            methods = d.methods(desc)
            print(f"\n=== {d.name}  super={info['super']}  {len(methods)} methods ===")
            for m in methods:
                name = m["name"]
                if args.Filter and args.Filter.lower() not in name.lower():
                    continue
                line = f"  {flags(m['access']):26s} {name}{m['signature']}"
                calls = d.method_calls(m["code_off"]) if args.Calls else []
                if calls:
                    line += f"   [{len(calls)} calls]"
                print(line)
                for c in calls:
                    print(f"        -> {c}")
                if args.Strings:
                    for _, s in d.const_strings(m["code_off"]):
                        print(f"        \"{s}\"")
    return 0


if __name__ == "__main__":
    sys.exit(main())
