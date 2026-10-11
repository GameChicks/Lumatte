"""Locate candidate request-bean callers in the supplied APK without running it.

Outputs class names only, no account state or values. Instruction-word matching
is a locator, not a complete disassembler; confirm candidates with selected-class
JADX recovery before drawing protocol conclusions. No build/test hook.
"""
from pathlib import Path
import argparse
import json
import struct
import zipfile

APK = Path(__file__).resolve().parents[1] / "reference/base.apk"
BEANS = {"CommunityListPostBean", "CommunityGenresPostBean", "CommunityDownloadPostBean"}


def uleb(data, pos):
    value = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
        shift += 7


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--member", choices=("setKey", "setLanguage", "setLimit", "<init>"), default="setKey")
    args = parser.parse_args()
    results = []
    with zipfile.ZipFile(APK) as archive:
        for name in ("classes.dex", "classes2.dex"):
            data = archive.read(name)
            count, offset = struct.unpack_from("<II", data, 56)
            strings = []
            for index in range(count):
                pos = struct.unpack_from("<I", data, offset + index * 4)[0]
                _, pos = uleb(data, pos)
                strings.append(data[pos:data.find(b"\0", pos)].decode("utf-8", "ignore"))
            count, offset = struct.unpack_from("<II", data, 64)
            types = [strings[struct.unpack_from("<I", data, offset + index * 4)[0]] for index in range(count)]
            count, offset = struct.unpack_from("<II", data, 88)
            targets = {}
            for index in range(count):
                owner, _, method = struct.unpack_from("<HHI", data, offset + index * 8)
                if strings[method] == args.member and any(types[owner].endswith("/" + bean + ";") for bean in BEANS):
                    targets[index] = types[owner][1:-1].replace("/", ".")
            count, offset = struct.unpack_from("<II", data, 96)
            for index in range(count):
                start = offset + index * 32
                owner = struct.unpack_from("<I", data, start)[0]
                pos = struct.unpack_from("<I", data, start + 24)[0]
                if not pos:
                    continue
                sizes = []
                for _ in range(4):
                    size, pos = uleb(data, pos)
                    sizes.append(size)
                for _ in range(sizes[0] + sizes[1]):
                    _, pos = uleb(data, pos)
                    _, pos = uleb(data, pos)
                hits = set()
                for _ in range(sizes[2] + sizes[3]):
                    _, pos = uleb(data, pos)
                    _, pos = uleb(data, pos)
                    code, pos = uleb(data, pos)
                    if not code:
                        continue
                    units = struct.unpack_from("<I", data, code + 12)[0]
                    words = struct.unpack_from("<" + "H" * units, data, code + 16)
                    for k in range(units - 2):
                        if words[k] & 255 in {0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78}:
                            target = targets.get(words[k + 1])
                            if target:
                                hits.add(target)
                if hits:
                    results.append({"dex": name, "candidateClass": types[owner][1:-1].replace("/", "."),
                                    "requestBeans": sorted(hits)})
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
