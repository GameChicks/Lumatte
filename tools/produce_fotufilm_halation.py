"""Produce Fotufilm's default physical halation kernels, not a photo render or test.

Equations adapted from pinned Halation.swift (Apache-2.0, MUAStudio Inc.).
Stock parameters and derived kernels: CC-BY-SA-4.0. Original sources/notices in
research/fotufilm. Solve invariant optics once during asset production, not on phones.
"""
from __future__ import annotations
import hashlib
import itertools
import json
import math
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "research/fotufilm"
ASSETS = ROOT / "core/media/src/main/assets/film/mobile-fotufilm-v1"
ALGORITHM = "fotufilm-legacy-physical-v1"


def simplex_fit(basis, target):
    """All seven faces of the nonnegative sum-to-one simplex; same upstream solve."""
    best = None
    residual = math.inf
    for mask in range(1, 8):
        active = [j for j in range(3) if mask & (1 << j)]
        last, free = active[-1], active[:-1]
        weights = np.zeros(3)
        if not free:
            weights[last] = 1
        else:
            delta = basis[free] - basis[last]
            normal = delta @ delta.T
            rhs = delta @ (target - basis[last])
            # Partial-pivot Gaussian elimination, matching solveSymmetric's degeneracy rule.
            n = len(free)
            valid = True
            for pivot in range(n):
                row = pivot + int(np.argmax(abs(normal[pivot:, pivot])))
                if abs(normal[row, pivot]) <= 1e-20:
                    valid = False
                    break
                normal[[pivot, row]], rhs[[pivot, row]] = normal[[row, pivot]], rhs[[row, pivot]]
                for below in range(pivot + 1, n):
                    factor = normal[below, pivot] / normal[pivot, pivot]
                    normal[below, pivot:] -= factor * normal[pivot, pivot:]
                    rhs[below] -= factor * rhs[pivot]
            if not valid:
                continue
            solution = np.zeros(n)
            for row in reversed(range(n)):
                solution[row] = (rhs[row] - normal[row, row+1:] @ solution[row+1:]) / normal[row, row]
            if np.any(solution < -1e-6):
                continue
            weights[free] = np.maximum(solution, 0)
            used = weights.sum()
            if used > 1 + 1e-6:
                continue
            weights[last] = 1 - used
        error = weights @ basis - target
        score = float(error @ error)
        if score < residual:
            residual, best = score, weights
    if best is None:
        raise ValueError("No physical halation simplex fit")
    return best


def gaussian_edge(distances, sigma):
    return np.array([.5 * math.erfc(float(d) / (sigma * math.sqrt(2))) for d in distances])


def physical_kernel(stock):
    """Complete supplied-profile branch of Halation.kernel at the native 35mm gauge.

    Float64 offline fitting avoids phone startup/quadrature cost. Same model/equations,
    not a claim of bitwise Float32 Swift equivalence or full-engine colour parity.
    """
    profile = stock["halationProfile"]
    thickness = .13  # FilmFormat.still35, not the experimental layered construction.
    indices = np.array([1.474, 1.480, 1.489])
    depths = np.maximum(profile.get("recordDepthMM", [0, 0, 0]), 0)
    radius = float(np.max(2 * (thickness + depths) * np.tan(np.arcsin(1 / indices))))
    distances = np.concatenate((np.linspace(0, 4 * radius, 161),
        4 * radius * np.exp(np.arange(1, 49) / 48 * math.log(4))))
    angles = (np.arange(256) + .5) * math.pi / 2 / 256
    cosine, sine = np.cos(angles), np.sin(angles)
    targets = []
    for channel, index in enumerate(indices):
        sin_out = index * sine
        cos_out = np.sqrt(np.maximum(0, 1 - sin_out**2))
        rs = np.where(sin_out >= 1, 1, ((index*cosine-cos_out)/(index*cosine+cos_out))**2)
        rp = np.where(sin_out >= 1, 1, ((index*cos_out-cosine)/(index*cos_out+cosine))**2)
        q = max(0, profile["angularExponent"][channel])
        depth = max(0, profile["roundTripOpticalDepth"][channel])
        retention = np.clip(profile["bounceRetention"][channel], 0, 1)
        angular = (q + 1) * cosine**q * sine
        radii, weights = [], []
        total = 0
        for bounce in range(1, 6):
            exponent = -bounce * depth / np.maximum(cosine, 1e-6)
            path = np.where(exponent < -80, 0, np.exp(exponent))
            rear = .5 * (rs**bounce + rp**bounce)
            current = angular * path * rear * (1 if bounce == 1 else retention**(bounce-1))
            radii.extend(2*bounce*(thickness+depths[channel])*sine/cosine)
            weights.extend(current)
            bounce_total = float(current.sum())
            total += bounce_total
            if bounce > 1 and bounce_total < total * 1e-3:
                break
        radii, weights = np.array(radii), np.array(weights)
        if total > 0:
            weights /= total
        share = float(np.clip(profile["diffuseShare"][channel], 0, 1))
        sigma = max(profile["diffuseSigmaMM"][channel], 1e-6)
        reflex = np.array([np.sum(weights[radii > d] * np.arccos(np.minimum(d/radii[radii > d], 1))) / math.pi for d in distances])
        targets.append(share * gaussian_edge(distances, sigma) + (1-share) * reflex)
    candidates = list(radius * np.exp(np.linspace(math.log(.02), math.log(4), 14)))
    candidates.extend(profile["diffuseSigmaMM"][c] for c in range(3) if profile["diffuseShare"][c] > 0)
    unique = []
    for candidate in sorted(candidates):
        if not unique or abs(unique[-1]-candidate) > 1e-6*radius:
            unique.append(candidate)
    basis = np.array([gaussian_edge(distances, s) for s in unique])
    targets = np.array(targets)
    best = None
    best_residual = math.inf
    for ids in itertools.combinations(range(len(unique)), 3):
        b = basis[list(ids)]
        weights = np.array([simplex_fit(b, t) for t in targets])
        residual = float(np.sum((weights @ b - targets)**2))
        if residual < best_residual:
            best_residual, best = residual, (ids, weights)
    ids, weights = best
    haze = max(stock.get("halationHazeMM", 0), 0)
    sigmas = np.sqrt(np.array(unique)[list(ids)]**2 + haze**2)
    strengths = np.maximum(stock["halationStrength"], 0) * max(stock.get("halationLookScale", 1), 0)
    mix = strengths / (1+strengths)
    matrix = np.zeros((3,3))
    supplied = stock.get("halationReturnMatrix")
    for c in range(3):
        row = np.maximum(supplied[c], 0) if supplied is not None else np.zeros(3)
        if row.sum() > 0:
            matrix[c] = mix[c] * row / row.sum()
        else:
            matrix[c,c] = mix[c]
    if not all(np.isfinite(a).all() for a in (sigmas, weights, matrix)):
        raise ValueError("Nonfinite physical halation kernel")
    return {"algorithm":ALGORITHM, "sigma_mm":sigmas.tolist(), "weights":weights.tolist(),
        "mix_matrix":matrix.tolist(), "strength":strengths.tolist(), "haze_mm":haze,
        "base_thickness_mm":thickness, "record_refractive_indices":indices.tolist(),
        "format_frame_height_mm":24, "angle_samples":256, "max_bounces":5,
        "fit_distance_count":209, "source_profile":profile,
        "source_return_matrix":supplied, "fit_arithmetic":"float64-offline",
        "analysis_long_edge":1536}


def halation_reference(stock):
    kernel = physical_kernel(stock)
    return {"halation_strength":kernel["strength"], "halation_first_sigma_um":[s*1000 for s in kernel["sigma_mm"]],
        "halation_n_bounces":3, "halation_bounce_decay":.5, "halation_renormalize":True,
        "fotufilm_kernel":kernel}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False)+"\n", encoding="utf-8", newline="\n")


def main():
    index = json.loads((ASSETS/"index.json").read_text(encoding="utf-8"))
    sources = json.loads((SOURCE/"source-index.json").read_text(encoding="utf-8"))
    for source_id, stock_id in [("cinestill800t","cinestill_800t"),("cinestill400d","cinestill_400d")]:
        profile_path = SOURCE/"profiles"/f"{source_id}.json"
        if hashlib.sha256(profile_path.read_bytes()).hexdigest() != sources["files"][f"profiles/{source_id}.json"]:
            raise ValueError("Original stock profile changed")
        stock = json.loads(profile_path.read_text(encoding="utf-8"))
        path = ASSETS/stock_id/"model.json"
        model = json.loads(path.read_text(encoding="utf-8"))
        model["halationReference"] = halation_reference(stock)
        model["mobileAdaptation"]["halationModel"] = ALGORITHM
        write_json(path,model)
        next(m for m in index["models"] if m["stockId"]==stock_id)["manifestSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        print(f"Produced {stock_id} physical kernel: {model['halationReference']['fotufilm_kernel']['sigma_mm']}",flush=True)
    write_json(ASSETS/"index.json",index)


if __name__ == "__main__":
    main()
