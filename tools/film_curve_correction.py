"""Pinned 5207 characteristic-curve adaptation; explicit asset production only.

Fotu 250D's separated-base dye amounts are not SpektraFilm's density coordinates.
Project the formed spectral density onto the receiving model's dye basis instead
of copying those numbers. This is a bounded mobile approximation, not engine parity.
"""
import hashlib
import json
from pathlib import Path

import numpy as np
from scipy.optimize import nnls

REFERENCE = Path(__file__).resolve().parents[1] / "research/fotufilm/curve-reference-0798443"
COMMIT = "079844387659045a39ef4e5005191b0d77b81606"


def profile(name):
    data = (REFERENCE / f"{name}.json").read_bytes()
    index = json.loads((REFERENCE / "source-index.json").read_text())
    if hashlib.sha256(data).hexdigest() != index["files"][f"{name}.json"]:
        raise ValueError("Curve reference bytes changed")
    return json.loads(data)


def apply_vision250d(params):
    source = profile("vision250d")
    axis = np.asarray(params.film.data.log_exposure)
    records = np.stack([np.interp(axis, c["sampled"]["logExposure"], c["sampled"]["density"])
                        - c["dMin"] for c in source["curves"]], axis=1)
    source_dyes = np.asarray(source["spectral"]["imageDyeDensity"])
    receiving_dyes = np.asarray(params.film.data.channel_density)
    if receiving_dyes.shape != (81, 3) or not np.allclose(params.film.data.wavelengths, np.arange(380, 781, 5)):
        raise ValueError("Unsupported spectral grid for 5207 correction")
    formed = np.maximum(records, 0) @ source_dyes
    # Each point is a neutral sensitometric wedge; off-axis spectra still use the existing
    # capture/dye model and local DIR. The two engines' spectral bases are not identical.
    # Spektra preserves NaNs outside a manufacturer's published dye measurements.
    # Fit only wavelengths measured in both sources; never invent missing spectral samples.
    valid = np.isfinite(receiving_dyes).all(axis=1) & np.isfinite(source_dyes).all(axis=0)
    if np.count_nonzero(valid) < 24:
        raise ValueError("Insufficient common measured dye wavelengths")
    converted = np.stack([nnls(receiving_dyes[valid], row[valid])[0] for row in formed])
    params.film.data.density_curves = converted
    return {"commit": COMMIT, "profile": "vision250d.json", "license": "CC-BY-SA-4.0",
            "adaptation": "nonnegative-spectral-density-basis-projection",
            "fullEngineParity": False,
            "commonMeasuredWavelengths": int(np.count_nonzero(valid)),
            "spectralDensityRmsResidual": float(np.sqrt(np.mean((converted @ receiving_dyes[valid].T - formed[:, valid]) ** 2)))}


def apply_cinestill400d(pack):
    source = profile("cinestill400d")
    # The public pack film LUT is indexed in its ORIGINAL density coordinates. Keep that
    # normalization when new development curves feed it; changing both would undo the fix.
    pack.scan_base = pack.get("CURVES", 18).reshape(3, 6)[:, 0].copy()
    pack.scan_span = pack.curve_range().copy()
    for channel, curve in enumerate(source["curves"]):
        pack.c[channel * 6:channel * 6 + 6] = [curve[key] for key in
            ("dMin", "gamma", "toe", "toeWidth", "shoulder", "shoulderWidth")]
        secondary = curve["secondary"]
        start = pack.k["CURVE_SECONDARY"] + channel * 5
        pack.c[start:start + 5] = [secondary[key] for key in
            ("gamma", "toe", "toeWidth", "shoulder", "shoulderWidth")]
        pack.c[pack.k["SAMPLED_CURVES"] + channel * 3073] = 0
    return {"commit": COMMIT, "profile": "cinestill400d.json", "license": "CC-BY-SA-4.0",
            "adaptation": "two-group-curves-with-original-pack-spectral-coordinates", "fullEngineParity": False}
