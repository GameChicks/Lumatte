"""Produce only app_lang resource evidence from the supplied Lab APK; never run the APK."""
from pathlib import Path
import hashlib
import json
import zipfile
from index_apk_resources import index

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "reference/base.apk"
OUTPUT = ROOT / "research/lumix-lab/languages-source-snapshot.json"


def main():
    with zipfile.ZipFile(APK) as archive:
        table = archive.read("resources.arsc")
    selected = [r for r in index(table, names={"app_lang"}, configurations=True) if r["type"] == "string"]
    snapshot = {"apk": "reference/base.apk", "apkSha256": hashlib.sha256(APK.read_bytes()).hexdigest(),
        "resourceTableSha256": hashlib.sha256(table).hexdigest(), "evidenceLevel": "static compiled resource values only",
        "configurationLayoutSource": "https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/libs/androidfw/include/androidfw/ResourceTypes.h",
        "resources": selected}
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps([{ "locale": r["configuration"], "value": r["value"] } for r in selected], indent=2))


if __name__ == "__main__":
    main()
