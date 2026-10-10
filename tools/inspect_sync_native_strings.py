"""Read selected protocol strings from supplied Flutter AOT code; never run APK code."""
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    entry = "lib/arm64-v8a/libapp.so"
    terms = ("cam.cgi", "req_acc", "X-SESSION", "CDS_control", "Server0/ddd",
             "LumixLink", "PANASONIC.COM_PN", "ContentDirectory", "CAM_ORG",
             "CAM_RAW", "raw_img_send", "BrowseDirectChildren", "X_FromCP",
             "StartingIndex", "RequestedCount", "Content-Range", "Range:")
    with zipfile.ZipFile(args.apk) as archive:
        data = archive.read(entry)
    matches = []
    for match in re.finditer(rb"[\x20-\x7e]{4,}", data):
        value = match.group().decode("ascii")
        if any(term in value for term in terms):
            # AOT strings may adjoin constants. Bound output to protocol context.
            positions = [value.index(term) for term in terms if term in value]
            at = min(positions)
            matches.append({"offset": match.start() + max(0, at - 120),
                            "text": value[max(0, at - 120):at + 700]})
    report = {"entry": entry, "entrySha256": hashlib.sha256(data).hexdigest(),
              "evidence": "Printable constants only; not recovered Dart source or verified behavior",
              "matches": matches}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
