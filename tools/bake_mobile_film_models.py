"""Produce Android film assets, not tests or a performance/visual comparison.

Uses a clean Git export of the pinned spektrafilm source. Does not modify the
user's deployment, execute its launchers, or import its GUI/session settings.
Run explicitly with that deployment's Python; never run from a build hook.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

COMMIT = "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc"
ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "tools/.cache/mobile-film-bake"
VERSION = "sf-3bb2c2d-three-stage-v1"


def write_json(path: Path, value) -> None:
    # Exact manifest hashes must survive Windows/Linux Git checkouts.
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def frozen_source(repository: Path) -> Path:
    target = CACHE / COMMIT
    # Export committed objects, even when the deployment has local GUI changes.
    archive = subprocess.run(
        ["git", "-C", str(repository), "archive", "--format=zip", COMMIT,
         "src/spektrafilm", "SPEKTRAFILM_LICENSE.txt", "LICENSE"],
        check=True, stdout=subprocess.PIPE,
    ).stdout
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        for entry in source.infolist():
            destination = (target / entry.filename).resolve()
            if not destination.is_relative_to(target.resolve()):
                raise ValueError("Source archive escapes its cache directory")
            source.extract(entry, target)
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Read-only spektrafilm Git checkout")
    args = parser.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    # Desktop libraries' generated caches stay on the project drive.
    for key, directory in (("NUMBA_CACHE_DIR", "numba"), ("MPLCONFIGDIR", "matplotlib"),
                           ("TMP", "temp"), ("TEMP", "temp")):
        folder = CACHE / directory
        folder.mkdir(exist_ok=True)
        os.environ[key] = str(folder)
    os.environ["MPLBACKEND"] = "Agg"
    source = frozen_source(args.source)
    sys.path.insert(0, str(source / "src"))
    import numpy as np
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    destination = ROOT / "core/media/src/main/assets/film/mobile-v1"
    stage = CACHE / "generated" / VERSION
    stage.mkdir(parents=True, exist_ok=True)
    catalogue = json.loads((ROOT / "core/media/src/main/assets/film/catalogue.json").read_text(encoding="utf-8"))
    entries = []

    def grid(size: int, low, high):
        # x/first-channel fastest, then y/second, then z/third. Interleaved RGB/CMY.
        axes = [np.linspace(low[c], high[c], size) for c in range(3)]
        z, y, x = np.meshgrid(axes[2], axes[1], axes[0], indexing="ij")
        return np.stack((x, y, z), axis=-1).reshape(size * size, size, 3)

    def table(folder: Path, name: str, values, size: int, low, high, input_space: str, output_space: str):
        values = np.asarray(values, dtype="<f4")
        if values.size != size ** 3 * 3 or not np.isfinite(values).all():
            raise ValueError(f"Cannot serialize {name}: non-finite or incorrectly shaped model data")
        payload = values.tobytes(order="C")
        (folder / f"{name}.f32").write_bytes(payload)
        return {"file": f"{name}.f32", "size": size, "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(), "minimum": list(map(float, low)),
                "maximum": list(map(float, high)), "shaper": "linear",
                "inputSpace": input_space, "outputSpace": output_space}

    for stock in catalogue["stocks"]:
        stock_id = stock["id"]
        print(f"Producing {stock_id} ...", flush=True)
        folder = stage / stock_id
        folder.mkdir(exist_ok=True)
        params = init_params(film_profile=stock_id, print_profile=stock["printId"])
        params.io.input_color_space = "sRGB"
        params.io.output_color_space = "sRGB"
        params.io.input_cctf_decoding = True
        params.io.output_cctf_encoding = True
        params.io.scan_film = stock["positive"]
        params.io.crop = False
        params.io.upscale_factor = 1.0
        params = digest_params(params)
        grain_reference = asdict(params.film_render.grain)
        halation_reference = asdict(params.film_render.halation)
        params.debug.lut_mode = True
        params = digest_params(params, apply_stocks_specifics=False)
        pipeline = SimulationPipeline(params)
        # Named public taps keep black/white reference exposure correction in raw.
        log_raw = pipeline.process(grid(33, [0] * 3, [1] * 3), collect="log_e_film")
        raw = np.maximum(np.power(10.0, log_raw) - 1e-10, 0.0)
        response = table(folder, "response", raw, 33, [0] * 3, [1] * 3,
                         "srgb_encoded", "film_raw_linear")
        log_axis = np.asarray(params.film.data.log_exposure, dtype=float)
        gamma = np.broadcast_to(np.asarray(params.film_render.density_curve_gamma), (3,))
        log_low = np.repeat(np.nanmin(log_axis), 3) / gamma - 0.5
        log_high = np.repeat(np.nanmax(log_axis), 3) / gamma + 0.5
        # Local DIR chemistry is baked exactly once. No spatial/stochastic terms.
        density = pipeline.process(grid(33, log_low, log_high), inject="log_e_film", collect="cmy_film")
        development = table(folder, "development", density, 33, log_low, log_high,
                            "film_log10_exposure", "film_cmy_density")
        curves = np.asarray(params.film.data.density_curves)
        density_low = np.repeat(-0.2, 3)
        density_high = np.maximum(np.nanmax(curves, axis=0) - np.nanmin(curves, axis=0),
                                  np.max(density, axis=(0, 1))) + 0.2
        rgb = pipeline.process(grid(41, density_low, density_high), inject="cmy_film", collect="rgb_out")
        scan = table(folder, "scan", rgb, 41, density_low, density_high,
                     "film_cmy_density", "srgb_encoded")
        metadata = {"schema": 1, "modelVersion": VERSION, "stockId": stock_id,
                    "printId": stock["printId"], "positive": stock["positive"],
                    "source": {"repository": "https://github.com/andreavolpato/spektrafilm",
                               "commit": COMMIT, "author": "Andrea Volpato", "license": "CC-BY-SA-4.0"},
                    "storage": "float32_le_rgb_x_fastest", "dir": "baked_local_only",
                    "spatialEffectsBaked": False, "grainBaked": False,
                    "tables": {"response": response, "development": development, "scan": scan},
                    "grainReference": grain_reference, "halationReference": halation_reference,
                    "settingsSnapshot": {"camera": asdict(params.camera), "enlarger": asdict(params.enlarger),
                                         "scanner": asdict(params.scanner), "io": asdict(params.io),
                                         "settings": asdict(params.settings), "debug": asdict(params.debug),
                                         "dir": asdict(params.film_render.dir_couplers)}}
        write_json(folder / "model.json", metadata)
        model_bytes = (folder / "model.json").read_bytes()
        entries.append({"stockId": stock_id, "printId": stock["printId"], "positive": stock["positive"],
                        "modelVersion": VERSION, "manifestSha256": hashlib.sha256(model_bytes).hexdigest(),
                        "tableBytes": response["bytes"] + development["bytes"] + scan["bytes"]})
        print(f"Produced {stock_id}: {entries[-1]['tableBytes']} table bytes", flush=True)

    shutil.copyfile(source / "SPEKTRAFILM_LICENSE.txt", stage / "SPEKTRAFILM_LICENSE.txt")
    (stage / "CHANGELOG.txt").write_text(
        f"Lumix Film Mirror: {VERSION}\nSource: spektrafilm {COMMIT}, Andrea Volpato.\n"
        "Derived data licensed CC BY-SA 4.0. Generated float32, x-fastest, per-stock tables.\n"
        "sRGB -> linear film response (33^3), log exposure -> density/local DIR (33^3),\n"
        "density -> fixed paper/positive scan sRGB (41^3). Exposure is zero at bake.\n"
        "Spatial effects, random grain and automatic exposure are disabled during bake.\n"
        "DIR spatial diffusion is omitted; local chemistry is baked once.\n"
        "Sampling and float32 encoding are modifications. No accuracy/performance validation was run.\n",
        encoding="utf-8", newline="\n")
    write_json(stage / "index.json", {"schema": 1, "modelVersion": VERSION, "sourceCommit": COMMIT, "models": entries})
    # Publish only after the entire production finishes; incomplete output stays in the cache.
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(stage, destination, dirs_exist_ok=True)
    print(f"Published {len(entries)} models to {destination}", flush=True)


if __name__ == "__main__":
    main()
