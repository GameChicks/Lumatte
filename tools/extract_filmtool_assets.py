"""Extract named artwork/fonts from the user's APK as reference material only."""
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    source = json.loads(args.manifest.read_text(encoding="utf-8"))
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != source["apk_sha256"]:
        raise ValueError("APK differs from indexed reference")
    output = args.output.resolve()
    records = []
    with zipfile.ZipFile(args.apk) as z:
        for r in source["resources"]:
            selected = r["type"] == "font" or (r["type"] == "drawable" and
                re.match(r"(?:film_(?!ic_|camera)|cartridge_blank|spool_120|print_cotton)", r["name"]))
            if not selected or "sha256" not in r:
                continue
            if not re.fullmatch(r"[a-z0-9_]+", r["name"]):
                raise ValueError("Unexpected resource name")
            target = output / r["type"] / (r["name"] + Path(r["value"]).suffix)
            data = z.read(r["value"])
            if hashlib.sha256(data).hexdigest() != r["sha256"]:
                raise ValueError("Resource differs from indexed reference")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            records.append({**r, "extracted_path": target.relative_to(output).as_posix(),
                            "scope": "reference; not automatically licensed for redistribution"})
        for name in z.namelist():
            if name.startswith("assets/licenses/") and not name.endswith("/"):
                relative = Path(name).relative_to("assets")
                target = (output / relative).resolve()
                if not target.is_relative_to(output):
                    raise ValueError("Unsafe license path")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(name))
    (output / "manifest.json").write_text(json.dumps({"apk_sha256": source["apk_sha256"],
                                                      "resources": records}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"resources": len(records), "bytes": sum(r["bytes"] for r in records)}, indent=2))


if __name__ == "__main__":
    main()
