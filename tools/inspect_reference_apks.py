"""Inventory supplied APK versions and hashes without executing APK code."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path
from index_apk_resources import chunks, pool, u16, u32


def manifest(data):
    strings = []
    result = {}
    for kind, header, size, at in chunks(data, u16(data, 2), len(data)):
        if kind == 1:
            strings = pool(data, at)
        elif kind == 0x102:
            ext = at + 16
            tag = strings[u32(data, ext + 4)]
            if tag not in ("manifest", "uses-sdk"):
                continue
            attributes = {}
            count = u16(data, ext + 12)
            stride = u16(data, ext + 10)
            for i in range(count):
                attribute = ext + u16(data, ext + 8) + i * stride
                name, raw = u32(data, attribute + 4), u32(data, attribute + 8)
                value_type, value = data[attribute + 15], u32(data, attribute + 16)
                if raw != 0xffffffff:
                    value = strings[raw]
                elif value_type == 3:
                    value = strings[value]
                attributes[strings[name]] = value
            result[tag] = attributes
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--downloads", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--additional", nargs=2, action="append", default=[],
                   metavar=("LABEL", "FILE"), help="Additional supplied APK in downloads")
    args = p.parse_args()
    records = []
    references = [("Film Companion", "base(1).apk"), ("LUMIX Sync", "base(2).apk"), ("LUMIX Lab", "base.apk")]
    for label, name in references + args.additional:
        path = args.downloads / name
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
        with zipfile.ZipFile(path) as z:
            values = manifest(z.read("AndroidManifest.xml"))
        records.append({"label": label, "file": name, "bytes": path.stat().st_size,
                        "sha256": digest.hexdigest(), "manifest": values})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(records, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
