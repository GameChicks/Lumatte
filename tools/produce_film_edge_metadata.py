"""Extract stock-specific edge records from pinned Fotufilm source; explicit asset production, no tests."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "e15b09815a83b0e00ba9381b65d31d253667a1b1"
REPOSITORY = ROOT / "tools/.cache/fotufilm-engine"
SOURCE = ROOT / "research/fotufilm/edge-reference-e15b098"
DESTINATION = ROOT / "core/media/src/main/assets/frames/stock-edges"
MAPPING = {
    "fujifilm_c200": "c200", "fujifilm_pro_400h": "pro400h", "fujifilm_xtra_400": "superia400",
    "fujifilm_provia_100f": "provia100f", "kodak_ektachrome_100": "ektachromee100",
    "kodak_ektar_100": "ektar100", "kodak_gold_200": "gold200", "kodak_kodachrome_64": "kodachrome64",
    "kodak_portra_400": "portra400", "kodak_ultramax_400": "ultramax400", "kodak_verita_200d": "verita200d",
    "kodak_vision3_250d": "vision250d", "kodak_vision3_500t": "vision500t",
    "cinestill_800t": "cinestill800t", "cinestill_400d": "cinestill400d",
    "fujifilm_acros_100_ii": "acros100", "kodak_tri_x_400": "trix400",
    "ilford_hp5_plus_400": "hp5plus400", "ilford_delta_3200": "delta3200",
}


def source(path):
    return subprocess.check_output(["git", "-C", str(REPOSITORY), "show", f"{COMMIT}:{path}"])


def write(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def main():
    SOURCE.mkdir(parents=True, exist_ok=True)
    DESTINATION.mkdir(parents=True, exist_ok=True)
    originals = ROOT / "tools/.cache/film-edges/profiles"
    originals.mkdir(parents=True, exist_ok=True)
    stocks = []
    for catalogue in ("film/catalogue.json", "film/mobile-fotufilm-v1/catalogue.json"):
        stocks += json.loads((ROOT / "core/media/src/main/assets" / catalogue).read_bytes())["stocks"]
    records = []
    sources = {}
    for stock in stocks:
        upstream = MAPPING.get(stock["id"])
        profile = None
        if upstream:
            path = f"Sources/FotufilmCore/Stocks/{upstream}.json"
            data = source(path)
            (originals / f"{upstream}.json").write_bytes(data)
            sources[path] = hashlib.sha256(data).hexdigest()
            profile = json.loads(data)
        edges = (profile or {}).get("edgePrinting", [])
        edge = next((entry for entry in edges if entry["formatID"] == "35mm"), None)
        records.append({"stockId": stock["id"], "name": stock["name"], "positive": stock["positive"],
            "monochrome": stock["category"] == "MONOCHROME", "motionPicture": stock["category"] == "CINEMA" and not stock["id"].startswith("cinestill"),
            "sourceProfile": upstream, "edge": edge})
        print(f"{stock['id']}: {len((edge or {}).get('marks', []))} marks, DX={(edge or {}).get('dx') is not None}")
    for path, name in (("Sources/FotufilmCore/PrintFrame.swift", "PrintFrame.swift"),
                       ("docs/print-frame-model.md", "print-frame-model.md")):
        data = source(path)
        (SOURCE / name).write_bytes(data)
        sources[path] = hashlib.sha256(data).hexdigest()
    for name in ("LICENSE", "NOTICE", "licenses/CC-BY-SA-4.0.txt"):
        data = source(name)
        (DESTINATION / Path(name).name).write_bytes(data)
        sources[name] = hashlib.sha256(data).hexdigest()
    write(SOURCE / "source-index.json", {"repository": "https://github.com/DhaliwalX/fotufilm-engine",
        "commit": COMMIT, "originalProfileCache": "tools/.cache/film-edges/profiles", "files": sources})
    write(DESTINATION / "catalogue.json", {"schema": 1, "sourceCommit": COMMIT,
        "author": "Fotufilm / MUAStudio Inc.", "license": "CC-BY-SA-4.0", "stocks": records})
    (DESTINATION / "ATTRIBUTION.txt").write_text(
        "Stock edge records: Fotufilm / MUAStudio Inc., CC BY-SA 4.0.\n"
        f"https://github.com/DhaliwalX/fotufilm-engine/tree/{COMMIT}\n"
        "DX encoding adapted from PrintFrame.swift, Apache-2.0; original NOTICE and LICENSE preserved.\n"
        "Changes: extracted 135 records, Android vector lettering, decorative developed-film colours, original-photo aspect without crop.\n"
        "Undocumented stock edges and DX codes stay blank; no emulsion or roll serial is invented.\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
