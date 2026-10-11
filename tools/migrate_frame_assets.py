"""Copy recovered print materials and OFL fonts into backend assets; never renders or builds."""
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "research/filmtool/assets"
TARGET = ROOT / "core/media/src/main/assets/frames"
FONT_LICENSES = {
    "film_sans_regular.otf": "SourceHanSans-OFL.txt",
    "print_typewriter.ttf": "cutivemono-OFL.txt",
    "print_script.ttf": "dancingscript-OFL.txt",
    "print_serif.ttf": "playfairdisplay-OFL.txt",
    "print_display.ttf": "abrilfatface-OFL.txt",
    "print_spaced.ttf": "juliussansone-OFL.txt",
    "print_great_vibes.ttf": "greatvibes-OFL.txt",
}


def main():
    source_manifest = json.loads((SOURCE / "manifest.json").read_text(encoding="utf-8"))
    resources = {entry["extracted_path"]: entry for entry in source_manifest["resources"]}
    records = []

    def copy(relative, folder, license_name=None):
        original = SOURCE / relative
        destination = TARGET / folder / original.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, destination)
        record = {
            "file": destination.relative_to(TARGET).as_posix(),
            "source": original.relative_to(ROOT).as_posix(),
            "sha256": hashlib.sha256(original.read_bytes()).hexdigest(),
            "bytes": original.stat().st_size,
        }
        entry = resources.get(relative)
        if entry:
            record["apk_entry"] = entry["value"]
        record["license"] = f"OFL-1.1; licenses/{license_name}" if license_name else "Recovered user-provided Film Companion artwork; see docs/engineering/frame-export.md"
        records.append(record)

    names = {"cartridge_blank_v1.png", "spool_120_blank_v1.png", "print_cotton_paper.png",
             "film_gold_200_image2.png", "film_ektar_100.png", "film_ultramax_400.png"}
    names.update(file.name for file in (SOURCE / "drawable").glob("film_pkg_*.png"))
    for name in sorted(names):
        copy(f"drawable/{name}", "art")
    for name, license_name in FONT_LICENSES.items():
        copy(f"font/{name}", "fonts", license_name)
        destination = TARGET / "licenses" / license_name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SOURCE / "licenses" / license_name, destination)
    manifest = {"source_apk_sha256": source_manifest["apk_sha256"], "assets": records}
    (TARGET / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Copied {len(records)} recovered print assets; no rendering, tests or packaging.")


if __name__ == "__main__":
    main()
