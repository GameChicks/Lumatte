#ifndef FOTUFILM_HALIDE_STAGES_HALATION_H
#define FOTUFILM_HALIDE_STAGES_HALATION_H

#include "FotufilmHalide.h"
#include "../FotufilmHalideGeometry.h"

#include <Halide.h>
#include <cmath>

namespace fotufilm {

/// One source record's returned light at a pixel: the scale mixture for record `channel`, less
/// its direct light. On a flat field this is zero, which is what keeps sensitometry — where the
/// uniform return is already inside the characteristic curves — untouched by halation.
inline Halide::Expr halation_returned(Halide::ImageParam &configuration,
                                      Halide::Expr channel, Halide::Expr direct,
                                      Halide::Expr blurred_0, Halide::Expr blurred_1,
                                      Halide::Expr blurred_2) {
    Halide::Expr kernel = FOTUFILM_CONFIG_HALATION_KERNEL + channel * 3;
    Halide::Expr scattered = configuration(kernel) * blurred_0
        + configuration(kernel + 1) * blurred_1
        + configuration(kernel + 2) * blurred_2;
    return scattered - direct;
}

/// Folds the base-reflected light back into a receiver's exposure through the spectral return
/// matrix: `direct_c + sum_j M[c][j] * returned_j`. `returned` maps a source record index to
/// that record's `halation_returned` value at this pixel. A diagonal matrix of the legacy
/// shares reproduces the old `(1 - s) * direct + s * scattered` mix exactly; the off-diagonal
/// entries are the cross-record returns the per-wavelength stack transmission routes — the
/// orange mask handing a green- or blue-lit source's surviving deep red to the red record.
template <typename Returned>
inline Halide::Expr halation_mix(Halide::ImageParam &configuration,
                                 Halide::Expr channel, Halide::Expr direct,
                                 Returned returned) {
    Halide::Expr row = FOTUFILM_CONFIG_HALATION_MATRIX + channel * 3;
    return direct + configuration(row) * returned(0)
        + configuration(row + 1) * returned(1)
        + configuration(row + 2) * returned(2);
}

inline Halide::Expr veiling_glare(Halide::Expr direct, Halide::Expr mean,
                                  Halide::Expr fraction) {
    return (1.0f - fraction) * direct + fraction * mean;
}

inline Halide::Expr veiling_glare(Halide::ImageParam &configuration,
                                  Halide::Expr direct, Halide::Expr mean) {
    return veiling_glare(direct, mean, configuration(FOTUFILM_CONFIG_FLARE));
}

/// The lens diffusion filter's mix, at one pixel of one record.
///
/// Unlike halation's, this is not a blend between a direct and a scattered term: the two shares
/// are independent, because the light that met a particle and the light that did not are
/// different light. What is missing from `direct + sum(kernel)` is what the particles absorbed
/// and what scattered past the widest scale, and neither of those belongs at this pixel — the
/// first is gone and the second is already in the glare stage.
inline Halide::Expr diffusion_mix(Halide::ImageParam &configuration,
                                  Halide::Expr channel, Halide::Expr direct,
                                  Halide::Expr blurred_0, Halide::Expr blurred_1,
                                  Halide::Expr blurred_2) {
    Halide::Expr kernel = Halide::select(
        channel == 3, FOTUFILM_CONFIG_DONOR_DIFFUSION_KERNEL,
        FOTUFILM_CONFIG_DIFFUSION_KERNEL + channel * 3);
    return configuration(FOTUFILM_CONFIG_DIFFUSION_DIRECT) * direct
        + configuration(kernel) * blurred_0
        + configuration(kernel + 1) * blurred_1
        + configuration(kernel + 2) * blurred_2;
}

/// Largest power of two keeping a Gaussian's decimated sigma at one sample or more.
inline Halide::Expr gaussian_stride(Halide::Expr sigma) {
    return gaussian_grid_stride(sigma, [](Halide::Expr condition, Halide::Expr yes, Halide::Expr no) {
        return Halide::select(condition, yes, no);
    });
}

/// Sigma to blur with on a grid decimated by `stride`.
inline Halide::Expr decimated_gaussian_sigma(Halide::Expr sigma,
                                             Halide::Expr stride) {
    return Halide::select(
        stride == 1, sigma,
        Halide::sqrt(Halide::max(
            sigma * sigma / Halide::cast<float>(stride * stride) - 0.25f,
            0.0625f)));
}

inline Halide::Expr decimated_gaussian_radius(Halide::Expr radius,
                                              Halide::Expr stride) {
    return gaussian_grid_radius(radius, stride, [](Halide::Expr a, Halide::Expr b) {
        return Halide::max(a, b);
    });
}

}

#endif
