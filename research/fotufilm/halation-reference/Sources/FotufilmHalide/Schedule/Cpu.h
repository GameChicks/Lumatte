#ifndef FOTUFILM_HALIDE_SCHEDULE_CPU_H
#define FOTUFILM_HALIDE_SCHEDULE_CPU_H

#include "FotufilmHalide.h"
#include "../FotufilmHalideGeometry.h"
#include "../Stages/Sampling.h"
#include "../Stages/Halation.h"

#include <Halide.h>
#include <string>

namespace fotufilm {
namespace cpu {

using Halide::BoundaryConditions::constant_exterior;
using Halide::Expr;
using Halide::Func;
using Halide::Param;
using Halide::RDom;
using Halide::Var;

constexpr int kVectorWidth = 8;
constexpr int kStripHeight = 32;

inline Halide::Target reference_target() {
    return Halide::get_jit_target_from_environment().with_feature(Halide::Target::StrictFloat);
}

inline Expr typed_zero(const Func &function) {
    return Halide::cast(function.value().type(), 0);
}

/// Standard schedule for a full-frame pointwise stage: channels unrolled, rows in parallel,
/// vectorized across x.
inline void cpu_pointwise(Func function, Var x, Var y, Var c, int planes = 3) {
    function.compute_root().bound(c, 0, planes).reorder(c, x, y).unroll(c)
        .vectorize(x, kVectorWidth, Halide::TailStrategy::GuardWithIf).parallel(y);
}

/// Standard schedule for a separable blur's two passes: a pure Func whose taps accumulate in
/// registers (Halide's inline `sum`) rather than as a zero-fill pass plus a read-modify-write
/// update over a materialized buffer.
inline void cpu_separable(Func function, Var x, Var y, Var c, int channels) {
    function.compute_root().bound(c, 0, channels).reorder(c, x, y).unroll(c)
        .vectorize(x, kVectorWidth, Halide::TailStrategy::GuardWithIf).parallel(y);
}

/// A separable Gaussian carrying one sigma per channel, over the widest of the three radii — the
/// narrower kernels simply decay to nothing out there.
inline Func gaussian(Func source, Expr sigma0, Expr sigma1, Expr sigma2,
              Expr radius, Expr width, Expr height,
              const std::string &name, int channels = 3) {
    Var x("x"), y("y"), c("c"), k("k");
    Expr sigma = Halide::select(c == 0, sigma0, c == 1, sigma1, sigma2);
    Func kernel(name + "_kernel");
    kernel(k, c) = Halide::exp(-Halide::cast<float>(k * k) / (2.0f * sigma * sigma));
    kernel.compute_root();

    Func bounded = constant_exterior(source, typed_zero(source),
                                     {{0, width}, {0, height}, {0, channels}});
    RDom horizontal_taps(-radius, radius * 2 + 1, name + "_horizontal_taps");
    Func horizontal_weight(name + "_horizontal_weight");
    horizontal_weight(x, c) = Halide::sum(
        Halide::select(x + horizontal_taps.x >= 0
                           && x + horizontal_taps.x < width,
                       kernel(horizontal_taps.x, c), 0.0f),
        name + "_horizontal_weight_sum");
    horizontal_weight.compute_root().bound(c, 0, channels).reorder(c, x).unroll(c)
        .vectorize(x, kVectorWidth, Halide::TailStrategy::GuardWithIf);

    Func horizontal(name + "_horizontal");
    horizontal(x, y, c) = Halide::sum(
        bounded(x + horizontal_taps.x, y, c) * kernel(horizontal_taps.x, c),
        name + "_horizontal_sum") / Halide::max(horizontal_weight(x, c), 1.0e-12f);
    cpu_separable(horizontal, x, y, c, channels);

    RDom vertical_taps(-radius, radius * 2 + 1, name + "_vertical_taps");
    Func vertical_weight(name + "_vertical_weight");
    vertical_weight(y, c) = Halide::sum(
        Halide::select(y + vertical_taps.x >= 0
                           && y + vertical_taps.x < height,
                       kernel(vertical_taps.x, c), 0.0f),
        name + "_vertical_weight_sum");
    vertical_weight.compute_root().bound(c, 0, channels).reorder(c, y).unroll(c)
        .vectorize(y, kVectorWidth, Halide::TailStrategy::GuardWithIf);

    Func vertical(name);
    vertical(x, y, c) = Halide::sum(
        horizontal(x, y + vertical_taps.x, c) * kernel(vertical_taps.x, c),
        name + "_vertical_sum") / Halide::max(vertical_weight(y, c), 1.0e-12f);
    cpu_separable(vertical, x, y, c, channels);
    return vertical;
}

/// Three chained box blurs collapsed into one convolution per direction.
inline Func triple_box_blur(Func source, Expr radius, Expr width, Expr height,
                     const std::string &name, int channels = 3) {
    Var x("x"), y("y"), c("c"), k("k");
    Expr box_scale = 1.0f / Halide::cast<float>(radius * 2 + 1);
    Func box(name + "_box");
    box(k) = Halide::select(Halide::abs(k) <= radius, box_scale, 0.0f);
    RDom fold_once(-radius, radius * 2 + 1, name + "_fold_once");
    Func box_twice(name + "_box_twice");
    box_twice(k) = Halide::sum(box(k - fold_once.x), name + "_fold_once_sum")
        * box_scale;
    RDom fold_again(-radius, radius * 2 + 1, name + "_fold_again");
    Func kernel(name + "_kernel");
    kernel(k) = Halide::sum(box_twice(k - fold_again.x), name + "_fold_again_sum")
        * box_scale;
    box.compute_root();
    box_twice.compute_root();
    kernel.compute_root();

    Func bounded = constant_exterior(
        source, typed_zero(source), {{0, width}, {0, height}, {0, channels}});
    RDom horizontal_taps(-radius * 3, radius * 6 + 1, name + "_horizontal_taps");
    Func horizontal(name + "_horizontal");
    Expr horizontal_weight = Halide::sum(
        Halide::select(x + horizontal_taps.x >= 0
                           && x + horizontal_taps.x < width,
                       kernel(horizontal_taps.x), 0.0f),
        name + "_horizontal_weight");
    horizontal(x, y, c) = Halide::sum(
        bounded(x + horizontal_taps.x, y, c) * kernel(horizontal_taps.x),
        name + "_horizontal_sum") / Halide::max(horizontal_weight, 1.0e-12f);
    cpu_separable(horizontal, x, y, c, channels);

    RDom vertical_taps(-radius * 3, radius * 6 + 1, name + "_vertical_taps");
    Func vertical(name);
    Expr vertical_weight = Halide::sum(
        Halide::select(y + vertical_taps.x >= 0
                           && y + vertical_taps.x < height,
                       kernel(vertical_taps.x), 0.0f),
        name + "_vertical_weight");
    vertical(x, y, c) = Halide::sum(
        horizontal(x, y + vertical_taps.x, c) * kernel(vertical_taps.x),
        name + "_vertical_sum") / Halide::max(vertical_weight, 1.0e-12f);
    cpu_separable(vertical, x, y, c, channels);
    return vertical;
}

/// One box-blur pass computed with running (prefix) sums, so a wide radius costs O(1) per pixel
/// instead of O(radius).
inline Func box_blur(Func source, Param<int32_t> &radius,
              Param<int32_t> &width, Param<int32_t> &height,
              const std::string &name) {
    Var x("x"), y("y"), c("c"), xo("xo"), xi("xi"), yo("yo"), yi("yi");
    Func bounded = constant_exterior(
        source, typed_zero(source), {{0, width}, {0, height}, {0, 3}});

    Func hsum(name + "_hsum");
    hsum(x, y, c) = 0.0f;
    RDom rx(0, width + 2 * radius, name + "_rx");
    hsum(rx - radius, y, c) = hsum(rx - radius - 1, y, c) + bounded(rx - radius, y, c);
    Func horizontal(name + "_horizontal");
    Expr horizontal_count = Halide::cast<float>(
        Halide::max(0, Halide::min(width - 1, x + radius)
                           - Halide::max(0, x - radius) + 1));
    horizontal(x, y, c) = (hsum(x + radius, y, c) - hsum(x - radius - 1, y, c))
        / Halide::max(horizontal_count, 1.0f);

    Func hbounded = constant_exterior(horizontal, typed_zero(horizontal),
                                      {{0, width}, {0, height}, {0, 3}});
    Func vsum(name + "_vsum");
    vsum(x, y, c) = 0.0f;
    RDom ry(0, height + 2 * radius, name + "_ry");
    vsum(x, ry - radius, c) = vsum(x, ry - radius - 1, c) + hbounded(x, ry - radius, c);
    Func vertical(name);
    Expr vertical_count = Halide::cast<float>(
        Halide::max(0, Halide::min(height - 1, y + radius)
                           - Halide::max(0, y - radius) + 1));
    vertical(x, y, c) = (vsum(x, y + radius, c) - vsum(x, y - radius - 1, c))
        / Halide::max(vertical_count, 1.0f);

    horizontal.compute_root()
        .split(y, yo, yi, kStripHeight, Halide::TailStrategy::GuardWithIf)
        .reorder(c, x, yi, yo).bound(c, 0, 3).unroll(c)
        .vectorize(x, kVectorWidth, Halide::TailStrategy::GuardWithIf)
        .parallel(yo);
    hsum.compute_at(horizontal, yo);
    hsum.update().reorder(c, rx, y).unroll(c);

    vertical.compute_root()
        .split(x, xo, xi, kStripHeight, Halide::TailStrategy::GuardWithIf)
        .reorder(c, xi, y, xo).bound(c, 0, 3).unroll(c)
        .vectorize(xi, kVectorWidth, Halide::TailStrategy::GuardWithIf)
        .parallel(xo);
    vsum.compute_at(vertical, xo);
    vsum.update().reorder(c, x, ry).unroll(c);
    return vertical;
}

/// A Gaussian whose sigma spans many pixels, run on a frame-anchored decimated grid: box-average
/// down by a power-of-two stride, blur there with the rescaled sigma, and sample back up
/// bilinearly.
inline Func cpu_gaussian_decimated(Func source, Expr sigma, Expr radius,
                            Expr origin_x, Expr origin_y,
                            Expr width, Expr height, const std::string &name,
                            int channels = 3) {
    Var x("x"), y("y"), c("c");
    Expr stride = gaussian_stride(sigma);
    Expr phase_x = origin_x % stride;
    Expr phase_y = origin_y % stride;
    Expr down_width = (width + phase_x + stride - 1) / stride;
    Expr down_height = (height + phase_y + stride - 1) / stride;
    Func bounded_source = constant_exterior(
        source, typed_zero(source), {{0, width}, {0, height}, {0, channels}});
    RDom cell(0, stride, 0, stride, name + "_cell");
    Func down(name + "_down");
    Expr source_x = x * stride - phase_x + cell.x;
    Expr source_y = y * stride - phase_y + cell.y;
    Expr valid = Halide::select(source_x >= 0 && source_x < width
                                    && source_y >= 0 && source_y < height,
                                1.0f, 0.0f);
    Expr cell_count = Halide::sum(valid, name + "_down_weight");
    down(x, y, c) = Halide::sum(
        bounded_source(x * stride - phase_x + cell.x,
                       y * stride - phase_y + cell.y, c),
        name + "_down_sum") / Halide::max(cell_count, 1.0f);
    cpu_separable(down, x, y, c, channels);

    Expr small_sigma = decimated_gaussian_sigma(sigma, stride);
    Func blurred = gaussian(down, small_sigma, small_sigma, small_sigma,
                            decimated_gaussian_radius(radius, stride),
                            down_width, down_height, name + "_spread", channels);
    Func bounded_blur = constant_exterior(
        blurred, typed_zero(blurred),
        {{0, down_width}, {0, down_height}, {0, channels}});
    Expr sample_x = (Halide::cast<float>(x + phase_x) + 0.5f)
        / Halide::cast<float>(stride) - 0.5f;
    Expr sample_y = (Halide::cast<float>(y + phase_y) + 0.5f)
        / Halide::cast<float>(stride) - 0.5f;
    Expr x0 = Halide::cast<int32_t>(Halide::floor(sample_x));
    Expr y0 = Halide::cast<int32_t>(Halide::floor(sample_y));
    Expr fx = sample_x - Halide::floor(sample_x);
    Expr fy = sample_y - Halide::floor(sample_y);
    Func up(name);
    Expr w00 = (1.0f - fx) * (1.0f - fy), w01 = (1.0f - fx) * fy;
    Expr w10 = fx * (1.0f - fy), w11 = fx * fy;
    auto valid_sample = [&](Expr sx, Expr sy) {
        return Halide::select(sx >= 0 && sx < down_width
                                  && sy >= 0 && sy < down_height, 1.0f, 0.0f);
    };
    Expr sample_weight = w00 * valid_sample(x0, y0) + w01 * valid_sample(x0, y0 + 1)
        + w10 * valid_sample(x0 + 1, y0) + w11 * valid_sample(x0 + 1, y0 + 1);
    up(x, y, c) = (w00 * bounded_blur(x0, y0, c)
                       + w01 * bounded_blur(x0, y0 + 1, c)
                       + w10 * bounded_blur(x0 + 1, y0, c)
                       + w11 * bounded_blur(x0 + 1, y0 + 1, c))
        / Halide::max(sample_weight, 1.0e-12f);
    return up;
}

/// One halation scale, evaluated on its own decimated grid: box-average down by `stride`, run the
/// collapsed triple box there with the rescaled radius, and sample back up bilinearly.
inline Expr halation_scale(Func light, Expr stride, Expr strided_radius,
                    Expr width, Expr height, Expr origin_x, Expr origin_y,
                    Var x, Var y, Var c, Expr ring_radius, bool annular,
                    const std::string &name, int channels = 3) {
    Expr phase_x = origin_x % stride;
    Expr phase_y = origin_y % stride;
    Expr down_width = (width + phase_x + stride - 1) / stride;
    Expr down_height = (height + phase_y + stride - 1) / stride;
    Func bounded_source = constant_exterior(
        light, typed_zero(light), {{0, width}, {0, height}, {0, channels}});
    RDom cell(0, stride, 0, stride, name + "_cell");
    Func down(name + "_down");
    Expr source_x = x * stride - phase_x + cell.x;
    Expr source_y = y * stride - phase_y + cell.y;
    Expr valid = Halide::select(source_x >= 0 && source_x < width
                                    && source_y >= 0 && source_y < height,
                                1.0f, 0.0f);
    Expr cell_count = Halide::sum(valid, name + "_down_weight");
    down(x, y, c) = Halide::sum(
        bounded_source(x * stride - phase_x + cell.x,
                       y * stride - phase_y + cell.y, c),
        name + "_down_sum") / Halide::max(cell_count, 1.0f);
    cpu_separable(down, x, y, c, channels);

    Func blurred = triple_box_blur(down, strided_radius, down_width, down_height,
                                   name + "_spread", channels);
    Func bounded_blur = constant_exterior(
        blurred, typed_zero(blurred),
        {{0, down_width}, {0, down_height}, {0, channels}});
    Expr sample_x = (Halide::cast<float>(x + phase_x) + 0.5f)
        / Halide::cast<float>(stride) - 0.5f;
    Expr sample_y = (Halide::cast<float>(y + phase_y) + 0.5f)
        / Halide::cast<float>(stride) - 0.5f;
    Expr x0 = Halide::cast<int32_t>(Halide::floor(sample_x));
    Expr y0 = Halide::cast<int32_t>(Halide::floor(sample_y));
    Expr fx = sample_x - Halide::floor(sample_x);
    Expr fy = sample_y - Halide::floor(sample_y);
    Expr w00 = (1.0f - fx) * (1.0f - fy), w01 = (1.0f - fx) * fy;
    Expr w10 = fx * (1.0f - fy), w11 = fx * fy;
    auto valid_sample = [&](Expr sx, Expr sy) {
        return Halide::select(sx >= 0 && sx < down_width
                                  && sy >= 0 && sy < down_height, 1.0f, 0.0f);
    };
    Expr sample_weight = w00 * valid_sample(x0, y0) + w01 * valid_sample(x0, y0 + 1)
        + w10 * valid_sample(x0 + 1, y0) + w11 * valid_sample(x0 + 1, y0 + 1);
    Expr center = (w00 * bounded_blur(x0, y0, c)
                       + w01 * bounded_blur(x0, y0 + 1, c)
                       + w10 * bounded_blur(x0 + 1, y0, c)
                       + w11 * bounded_blur(x0 + 1, y0 + 1, c))
        / Halide::max(sample_weight, 1.0e-12f);
    if (!annular) return center;
    auto at = [&](Expr sx, Expr sy) { return bounded_blur(sx, sy, c); };
    auto valid_ring_sample = [&](Expr sx, Expr sy) {
        return Halide::select(sx >= 0 && sx < down_width
                                  && sy >= 0 && sy < down_height, 1.0f, 0.0f);
    };
    Expr radius_on_grid = ring_radius / Halide::cast<float>(stride);
    return annular_sample(at, sample_x, sample_y, radius_on_grid)
        / Halide::max(annular_sample(valid_ring_sample, sample_x, sample_y,
                                    radius_on_grid), 1.0e-12f);
}

}
}

#endif
