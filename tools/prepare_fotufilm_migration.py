"""Explicit, pinned input acquisition for model production; never a build/test hook."""
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "e15b09815a83b0e00ba9381b65d31d253667a1b1"
SOURCE = ROOT / "research/fotufilm/model-reference-e15b098"
CACHE = ROOT / "tools/.cache/fotufilm-profile-builder"
BASE = "https://fotufilm.com/demo/engine/profile/"
BUILDER_SHA256 = "d14b767d2a21ae82792d678d90f74160f370bcc13f8de17348709639321c607e"
MAPPING = {
    "fujifilm_c200": "c200", "fujifilm_pro_400h": "pro400h",
    "fujifilm_xtra_400": "superia400", "fujifilm_provia_100f": "provia100f",
    "kodak_ektachrome_100": "ektachromee100", "kodak_ektar_100": "ektar100",
    "kodak_gold_200": "gold200", "kodak_kodachrome_64": "kodachrome64",
    "kodak_portra_400": "portra400", "kodak_ultramax_400": "ultramax400",
    "kodak_verita_200d": "verita200d", "kodak_vision3_250d": "vision250d",
    "kodak_vision3_500t": "vision500t", "cinestill_800t": "cinestill800t",
    "cinestill_400d": "cinestill400d", "fujifilm_acros_100_ii": "acros100",
    "kodak_tri_x_400": "trix400", "ilford_hp5_plus_400": "hp5plus400",
    "ilford_delta_3200": "delta3200",
}


def upstream(path):
    return subprocess.check_output(["git", "-C", str(ROOT / "tools/.cache/fotufilm-engine"),
                                    "show", f"{COMMIT}:{path}"])


def write_json(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode())


def download_builder():
    # Bounded range requests avoid a single long transfer, retaining resumable cache chunks.
    size = 55_603_660
    step = 4 * 1024 * 1024
    chunks = CACHE / "download-chunks"
    chunks.mkdir(exist_ok=True)
    def read(start):
        end = min(size, start + step) - 1
        path = chunks / str(start)
        if path.exists() and path.stat().st_size == end - start + 1:
            return path
        request = urllib.request.Request(BASE + f"builder.wasm?part={start}",
                                         headers={"Range": f"bytes={start}-{end}"})
        with urllib.request.urlopen(request, timeout=60) as response:
            if response.status != 206 or response.headers.get("Content-Range") != f"bytes {start}-{end}/{size}":
                raise ValueError("Unexpected public builder byte range")
            data = response.read(end - start + 2)
        if len(data) != end - start + 1:
            raise ValueError("Incomplete public builder byte range")
        path.write_bytes(data)
        print(f"Downloaded builder range {start}-{end}", flush=True)
        return path
    with ThreadPoolExecutor(max_workers=8) as executor:
        paths = list(executor.map(read, range(0, size, step)))
    return b"".join(path.read_bytes() for path in paths)


def main():
    SOURCE.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    # Published builder has no advertised source commit. Pin its digest separately from
    # source profiles; profile requests below always use the reviewed Git objects.
    manifest_bytes = urllib.request.urlopen(BASE + "index.json", timeout=45).read()
    published = json.loads(manifest_bytes)
    if published["builder.wasm"] != BUILDER_SHA256:
        raise ValueError("Published builder changed; pin and review the replacement deliberately")
    previous = SOURCE / "source-index.json"
    if previous.exists():
        pinned = json.loads(previous.read_bytes())["publicAssets"]
        if published["builder.wasm"] != pinned["builder.wasm"]:
            raise ValueError("Published builder changed; review and pin a new production version")
    hashes = {}
    differences = []
    for name in ("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "LICENSING.md",
                 "licenses/CC-BY-SA-4.0.txt", "licenses/FILM-PROFILES.txt",
                 "licenses/FILM-PROFILES.json", "licenses/SWIFT-APACHE-2.0-RUNTIME.txt",
                 "Sources/FotufilmCore/WebFilmProfile.swift", "web/src/engine-constants.js",
                 "Sources/FotufilmHalide/config-layout.json",
                 "Sources/FotufilmHalide/Stages/Curves.h", "Sources/FotufilmHalide/Stages/Couplers.h",
                 "Sources/FotufilmHalide/Stages/Development.h", "Sources/FotufilmHalide/Stages/Print.h",
                 "Sources/FotufilmHalide/Stages/Exposure.h", "Sources/FotufilmHalide/Stages/Transfer.h",
                 "Sources/FotufilmHalide/Stages/Math.h", "Sources/FotufilmHalide/Graph/Frame.h"):
        payload = upstream(name)
        path = SOURCE / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
        hashes[name] = hashlib.sha256(payload).hexdigest()
    for stock in MAPPING.values():
        name = f"Sources/FotufilmCore/Stocks/{stock}.json"
        payload = upstream(name)
        digest = hashlib.sha256(payload).hexdigest()
        if digest != published[f"stocks/{stock}.json"]:
            differences.append(stock)
        path = SOURCE / "profiles" / f"{stock}.json"
        path.parent.mkdir(exist_ok=True)
        path.write_bytes(payload)
        hashes[f"profiles/{stock}.json"] = digest
    public = {}
    for name in ("builder.wasm", "rec2020-reflectance-prior.coeff", "catalogue.json"):
        path = CACHE / name
        expected = published[name]
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            data = download_builder() if name == "builder.wasm" else urllib.request.urlopen(BASE + name, timeout=90).read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError(f"Public asset digest mismatch: {name}")
            path.write_bytes(data)
        public[name] = expected
        print(f"Prepared {name}: {path.stat().st_size} bytes", flush=True)
    (SOURCE / "published-index.json").write_bytes(manifest_bytes)
    before = SOURCE / "catalogue-before-migration.json"
    if not before.exists():
        stocks = []
        for path in ("catalogue.json", "mobile-fotufilm-v1/catalogue.json"):
            stocks += json.loads((ROOT / "core/media/src/main/assets/film" / path).read_bytes())["stocks"]
        write_json(before, {"stocks": stocks})
    hashes["catalogue-before-migration.json"] = hashlib.sha256(before.read_bytes()).hexdigest()
    effects = SOURCE / "existing-effects"
    effects.mkdir(exist_ok=True)
    for stock in MAPPING:
        destination = effects / f"{stock}.json"
        if not destination.exists():
            paths = [ROOT / f"core/media/src/main/assets/film/{folder}/{stock}/model.json"
                     for folder in ("mobile-v1", "mobile-fotufilm-v1")]
            destination.write_bytes(next(p for p in paths if p.exists()).read_bytes())
        hashes[f"existing-effects/{stock}.json"] = hashlib.sha256(destination.read_bytes()).hexdigest()
    text = (SOURCE / "web/src/engine-constants.js").read_text()
    config = json.JSONDecoder().raw_decode(text.split("export const CONFIG = ", 1)[1])[0]
    write_json(SOURCE / "config-layout.json", config)
    write_json(SOURCE / "source-index.json", dict(repository="https://github.com/DhaliwalX/fotufilm-engine",
        profileCommit=COMMIT, builderCommitNotPublished=True, publicBase=BASE, publicAssets=public,
        stockMapping=MAPPING, profilesDifferingFromPublishedDemo=differences, files=hashes))


if __name__ == "__main__":
    main()
