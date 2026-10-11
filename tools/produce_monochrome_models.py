"""Produce project-authored monochrome assets; no tests, benchmarks or Android build.

Artist-authored silver-negative/paper curves, not measured commercial stock data.
Uses the same three-stage transport as mobile film, with one scalar silver density
represented in all three channels. Run explicitly; never from a build hook.
SPDX-License-Identifier: GPL-3.0-only
"""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import shutil
import struct

ROOT = Path(__file__).resolve().parents[1]
VERSION = "lfm-silver-three-stage-v1"
RELATIVE = "core/media/src/main/assets/film/mobile-monochrome-v1"
STAGE = ROOT / "tools/.cache/monochrome-production" / VERSION
STOCKS = (
    ("lfm_silver_neutral", "Silver · Neutral", 1.8, 1.0),
    ("lfm_silver_contrast", "Silver · Contrast", 2.2, 1.4),
)


def write_json(path: Path, value) -> None:
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def sigmoid(value: float) -> float:
    return 1.0 / (1.0 + math.exp(-value))


def decode(value: float) -> float:
    return value / 12.92 if value <= .04045 else ((value + .055) / 1.055) ** 2.4


def encode(value: float) -> float:
    value = max(0.0, min(1.0, value))
    return value * 12.92 if value <= .0031308 else 1.055 * value ** (1 / 2.4) - .055


def table(folder: Path, name: str, size: int, minimum: float, maximum: float, function,
          input_space: str, output_space: str):
    payload = bytearray()
    axis = [minimum + (maximum - minimum) * i / (size - 1) for i in range(size)]
    # First-channel/x fastest. All output channels share a scalar silver value.
    for z in axis:
        for y in axis:
            for x in axis:
                value = function(x, y, z)
                if not math.isfinite(value):
                    raise ValueError(f"Cannot serialize non-finite {name} data")
                payload.extend(struct.pack("<fff", value, value, value))
    filename = f"{name}.f32"
    (folder / filename).write_bytes(payload)
    return {"file": filename, "size": size, "bytes": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(), "minimum": [minimum] * 3,
            "maximum": [maximum] * 3, "shaper": "linear",
            "inputSpace": input_space, "outputSpace": output_space}


def main() -> None:
    STAGE.mkdir(parents=True, exist_ok=True)
    entries, catalogue = [], []
    for stock_id, name, film_slope, grain_scale in STOCKS:
        print(f"Producing {stock_id} ...", flush=True)
        folder = STAGE / stock_id
        folder.mkdir(exist_ok=True)
        film_base, film_span, paper_base, paper_span, paper_slope = .08, 2.2, .015, 2.7, 3.0
        reference = .184
        film_mid = math.log10(reference)

        def density(log_exposure):
            return film_base + film_span * sigmoid(film_slope * (log_exposure - film_mid))

        midpoint, white_density = density(film_mid), density(0.0)

        def reflectance(silver_density, paper_center):
            # Light through the developed negative: log10(T) = -density.
            paper_density = paper_base + paper_span * sigmoid(paper_slope * (-silver_density - paper_center))
            return 10 ** (-paper_density)

        def scan_linear(silver_density, paper_center):
            black = reflectance(film_base, paper_center)
            white = reflectance(white_density, paper_center)
            return (reflectance(silver_density, paper_center) - black) / (white - black)

        # Production calibration: fixed paper exposure maps reference grey back
        # to 0.184 after fixed black/white scan normalization, never image-aware.
        low, high = -1.5, -.5
        for _ in range(40):
            center = (low + high) * .5
            if scan_linear(midpoint, center) < reference:
                low = center
            else:
                high = center
        paper_center = (low + high) * .5
        response = table(folder, "response", 33, 0.0, 1.0,
                         lambda r, g, b: .2126 * decode(r) + .7152 * decode(g) + .0722 * decode(b),
                         "srgb_encoded", "film_raw_linear")
        development = table(folder, "development", 33, -6.0, 2.0,
                            lambda r, g, b: density((r + g + b) / 3),
                            "film_log10_exposure", "film_cmy_density")
        scan = table(folder, "scan", 41, -.2, film_base + film_span + .2,
                     lambda r, g, b: encode(scan_linear((r + g + b) / 3, paper_center)),
                     "film_cmy_density", "srgb_encoded")
        metadata = {"schema": 1, "modelVersion": VERSION, "stockId": stock_id,
                    "printId": "lfm_silver_paper", "positive": False,
                    "source": {"origin": "project_authored", "author": "Lumix Film Mirror",
                               "implementation": "silver-negative-v1", "license": "GPL-3.0-only"},
                    "storage": "float32_le_rgb_x_fastest", "dir": "none_silver_density",
                    "spatialEffectsBaked": False, "grainBaked": False,
                    "tables": {"response": response, "development": development, "scan": scan},
                    "grainReference": {"active": True, "particle_area_um2": .45,
                                       "particle_scale": [grain_scale] * 3, "density_min": [.02] * 3,
                                       "uniformity": [.9] * 3},
                    "halationReference": {"halation_strength": [.06] * 3,
                                          "halation_first_sigma_um": [60.0] * 3},
                    "settingsSnapshot": {"camera": {"film_format_mm": 36.0}},
                    "silverModel": {"referenceLinearGrey": reference, "filmBase": film_base,
                                    "filmDensitySpan": film_span, "filmSlope": film_slope,
                                    "filmLogMidpoint": film_mid, "paperBase": paper_base,
                                    "paperDensitySpan": paper_span, "paperSlope": paper_slope,
                                    "paperLogMidpoint": paper_center, "scanWhiteDensity": white_density,
                                    "scanBlackDensity": film_base, "commercialStockCalibration": False}}
        write_json(folder / "model.json", metadata)
        entries.append({"stockId": stock_id, "printId": "lfm_silver_paper", "positive": False,
                        "modelVersion": VERSION, "manifestSha256": hashlib.sha256((folder / "model.json").read_bytes()).hexdigest(),
                        "tableBytes": response["bytes"] + development["bytes"] + scan["bytes"]})
        catalogue.append({"id": stock_id, "name": name, "category": "MONOCHROME",
                          "printId": "lfm_silver_paper", "positive": False,
                          "description": "本项目自有银盐黑白风格，非实测品牌胶卷模型。"})
        print(f"Produced {stock_id}: {entries[-1]['tableBytes']} table bytes", flush=True)
    write_json(STAGE / "index.json", {"schema": 1, "modelVersion": VERSION,
                                     "origin": "project_authored", "models": entries})
    write_json(STAGE / "catalogue.json", {"stocks": catalogue})
    shutil.copyfile(ROOT / "LICENSE", STAGE / "LICENSE.txt")
    (STAGE / "NOTICE.txt").write_bytes(
        ("Lumix Film Mirror project-authored Silver Neutral / Silver Contrast. GPL-3.0-only.\n"
         "Artist-authored panchromatic luminance, sigmoid negative and paper response.\n"
         "Not spektrafilm model data; not measured commercial-stock or filter calibration.\n"
         "Scalar silver density uses three equal channels for shared mobile-stage transport.\n"
         "Spatial effects and random grain are applied at runtime, not baked.\n"
         "No image, device, accuracy, performance or runtime validation was run.\n").encode("utf-8"))
    destination = ROOT / RELATIVE
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(STAGE, destination, dirs_exist_ok=True)
    print(f"Published {len(entries)} monochrome models to {destination}", flush=True)


if __name__ == "__main__":
    main()
