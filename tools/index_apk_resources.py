"""Read an APK resource table into an inspectable name/path manifest without executing it."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def u16(data, offset):
    return struct.unpack_from("<H", data, offset)[0]


def u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def chunks(data, start, end):
    at = start
    while at + 8 <= end:
        kind, header, size = struct.unpack_from("<HHI", data, at)
        if size < header or size < 8 or at + size > end:
            raise ValueError(f"Invalid resource chunk at {at}")
        yield kind, header, size, at
        at += size


def pool(data, at):
    header = u16(data, at + 2)
    count, flags, strings = u32(data, at + 8), u32(data, at + 16), u32(data, at + 20)
    utf8 = bool(flags & 0x100)

    def length(pos, wide):
        first = u16(data, pos) if wide else data[pos]
        pos += 2 if wide else 1
        mask = 0x8000 if wide else 0x80
        if first & mask:
            second = u16(data, pos) if wide else data[pos]
            return ((first & ~mask) << (16 if wide else 8)) | second, pos + (2 if wide else 1)
        return first, pos

    result = []
    for i in range(count):
        pos = at + strings + u32(data, at + header + i * 4)
        if utf8:
            _, pos = length(pos, False)
            size, pos = length(pos, False)
            result.append(data[pos:pos + size].decode("utf-8"))
        else:
            size, pos = length(pos, True)
            result.append(data[pos:pos + size * 2].decode("utf-16-le"))
    return result


def index(data, *, names=None, configurations=False):
    global_strings = []
    resources = []
    for kind, header, size, at in chunks(data, u16(data, 2), len(data)):
        if kind == 1:
            global_strings = pool(data, at)
        elif kind == 0x200:
            package_id = u32(data, at + 8)
            types = pool(data, at + u32(data, at + 268))
            keys = pool(data, at + u32(data, at + 276))
            for ck, ch, cs, ca in chunks(data, at + header, at + size):
                if ck != 0x201:
                    continue
                type_id, flags = data[ca + 8], data[ca + 9]
                count, entries_start = u32(data, ca + 12), u32(data, ca + 16)
                offsets = []
                for i in range(count):
                    if flags & 1:
                        entry_id, off = struct.unpack_from("<HH", data, ca + ch + i * 4)
                        offsets.append((entry_id, off * 4))
                    elif flags & 2:
                        off = u16(data, ca + ch + i * 2)
                        if off != 0xFFFF:
                            offsets.append((i, off * 4))
                    else:
                        off = u32(data, ca + ch + i * 4)
                        if off != 0xFFFFFFFF:
                            offsets.append((i, off))
                for entry_id, off in offsets:
                    entry = ca + entries_start + off
                    entry_size, entry_flags, key = struct.unpack_from("<HHI", data, entry)
                    if names is not None and keys[key] not in names:
                        continue
                    if entry_flags & 1:
                        continue  # Complex maps do not directly reference a file.
                    value = entry + entry_size
                    value_type, value_data = data[value + 3], u32(data, value + 4)
                    if value_type == 3:
                        resource = {"id": f"0x{package_id:02x}{type_id:02x}{entry_id:04x}",
                                    "type": types[type_id - 1], "name": keys[key],
                                    "value": global_strings[value_data]}
                        if configurations:
                            config = ca + 20
                            config_size = u32(data, config)
                            if config_size < 12 or config + config_size > ca + ch:
                                raise ValueError("Invalid resource configuration")
                            def code(offset, length):
                                raw = data[config + offset:config + offset + length]
                                # Retain packed codes as hex. This focused recovery only needs ASCII locales.
                                return raw.rstrip(b"\0").decode("ascii") if all(c < 128 for c in raw) else "hex:" + raw.hex()
                            resource["configuration"] = {"language": code(8, 2), "region": code(10, 2),
                                "script": code(36, 4) if config_size >= 40 else "",
                                "rawHex": data[config:config + config_size].hex()}
                        resources.append(resource)
    return sorted({(r["id"], r["value"], r.get("configuration", {}).get("rawHex")): r for r in resources}.values(),
                  key=lambda r: (r["type"], r["name"], r["value"], r.get("configuration", {}).get("rawHex", "")))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    with zipfile.ZipFile(args.apk) as z:
        resources = index(z.read("resources.arsc"))
        names = set(z.namelist())
        for r in resources:
            if r["value"] in names:
                data = z.read(r["value"])
                r.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
    manifest = {"apk_name": args.apk.name, "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                "resources": resources}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"resources": len(resources), "files": sum("sha256" in r for r in resources),
                      "selected": [r for r in resources if r["type"] == "font" or
                                   any(s in r["name"] for s in ("cartridge", "spool_120", "cotton_paper", "film_gold"))]},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
