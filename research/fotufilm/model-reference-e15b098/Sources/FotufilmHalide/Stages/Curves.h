#ifndef FOTUFILM_HALIDE_STAGES_CURVES_H
#define FOTUFILM_HALIDE_STAGES_CURVES_H

#include "FotufilmHalide.h"
#include "Math.h"

#include <Halide.h>
#include <cmath>

namespace fotufilm {

/// Analytic H&D density at `log_exposure` for the six-parameter curve stored
/// at `base` in the packed configuration.
inline Halide::Expr curve_density(Halide::ImageParam &configuration,
                                  Halide::Expr base, Halide::Expr log_exposure,
                                  bool approximate = false) {
    Halide::Expr d_min = configuration(base);
    Halide::Expr gamma = configuration(base + 1);
    Halide::Expr toe = configuration(base + 2);
    // Guarded like `curve_component_density`'s widths, so a curve left uncoated —
    // the donor block is zeroed outright for most stocks — tabulates a finite zero
    // rather than `0 * softplus(±Inf)`, the NaN that then rides the release row
    // into the densities.
    Halide::Expr toe_width = Halide::max(configuration(base + 3), 1.0e-6f);
    Halide::Expr shoulder = configuration(base + 4);
    Halide::Expr shoulder_width = Halide::max(configuration(base + 5), 1.0e-6f);
    Halide::Expr toe_term = toe_width
        * softplus((log_exposure - toe) / toe_width, approximate);
    Halide::Expr shoulder_term = shoulder_width
        * softplus((log_exposure - shoulder) / shoulder_width, approximate);
    return d_min + gamma * Halide::min(
        Halide::max(toe_term - shoulder_term, 0.0f), shoulder - toe);
}

/// Density contributed by the optional second coated speed group. Its five slots omit dMin;
/// density populations add above the same support and fog floor.
inline Halide::Expr curve_component_density(Halide::ImageParam &configuration,
                                            Halide::Expr base,
                                            Halide::Expr log_exposure,
                                            bool approximate = false) {
    Halide::Expr gamma = configuration(base);
    Halide::Expr toe = configuration(base + 1);
    Halide::Expr toe_width = Halide::max(configuration(base + 2), 1.0e-6f);
    Halide::Expr shoulder = configuration(base + 3);
    Halide::Expr shoulder_width = Halide::max(configuration(base + 4), 1.0e-6f);
    Halide::Expr toe_term = toe_width
        * softplus((log_exposure - toe) / toe_width, approximate);
    Halide::Expr shoulder_term = shoulder_width
        * softplus((log_exposure - shoulder) / shoulder_width, approximate);
    return gamma * Halide::min(
        Halide::max(toe_term - shoulder_term, 0.0f), shoulder - toe);
}

inline Halide::Expr sampled_film_density(Halide::ImageParam &configuration,
                                        Halide::Expr channel, Halide::Expr x) {
    using namespace Halide;
    Expr base = FOTUFILM_CONFIG_SAMPLED_CURVES + channel * FOTUFILM_SAMPLED_CURVE_STRIDE;
    Expr count = clamp(cast<int32_t>(configuration(base)), 2, FOTUFILM_SAMPLED_CURVE_MAX_SAMPLES);
    Expr low = 0;
    // Binary lifting tracks only the lower knot. Carrying both bounds through ten
    // nested selects makes Halide's let substitution expand an exponential DAG
    // in several AOT schedules. Constant strides keep the same exact interval
    // search while greatly reducing the expression passed to the compiler.
    for (int stride = FOTUFILM_SAMPLED_CURVE_MAX_SAMPLES / 2; stride > 0; stride /= 2) {
        Expr candidate = min(low + stride, count - 1);
        low = unsafe_promise_clamped(
            select(configuration(base + 1 + candidate * 3) <= x, candidate, low),
            0, FOTUFILM_SAMPLED_CURVE_MAX_SAMPLES - 1);
    }
    Expr high = min(low + 1, count - 1);
    Expr i = base + 1 + low * 3, j = base + 1 + high * 3;
    Expr h = max(configuration(j) - configuration(i), 1.0e-12f);
    Expr t = clamp((x - configuration(i)) / h, 0.0f, 1.0f);
    Expr y0 = configuration(i + 1), y1 = configuration(j + 1);
    Expr a = h * configuration(i + 2), b = h * configuration(j + 2);
    Expr delta = y1 - y0;
    Expr y = y0 + t * (a + t * (3 * delta - 2 * a - b + t * (-2 * delta + a + b)));
    return select(x <= configuration(base + 1), configuration(base + 2),
                  x >= configuration(base + 1 + (count - 1) * 3),
                  configuration(base + 2 + (count - 1) * 3), y);
}

inline Halide::Expr has_sampled_film_curve(Halide::ImageParam &configuration,
                                          Halide::Expr channel) {
    return configuration(FOTUFILM_CONFIG_SAMPLED_CURVES
        + channel * FOTUFILM_SAMPLED_CURVE_STRIDE) >= 2.0f;
}

inline Halide::Expr film_density(Halide::ImageParam &configuration,
                                 Halide::Expr channel, Halide::Expr log_exposure,
                                 bool approximate = false) {
    Halide::Expr analytic = curve_density(configuration, FOTUFILM_CONFIG_CURVES + channel * 6,
                         log_exposure, approximate)
        + curve_component_density(
            configuration, FOTUFILM_CONFIG_CURVE_SECONDARY + channel * 5,
            log_exposure, approximate);
    return Halide::select(has_sampled_film_curve(configuration, channel),
                           sampled_film_density(configuration, channel, log_exposure), analytic);
}

/// Sampling grid for the tabulated H&D curves below.
constexpr int kCurveSamples = 2048;
constexpr float kCurveMin = -8.0f;
constexpr float kCurveMax = 8.0f;

/// Tabulates a six-parameter H&D curve so the per-pixel cost is one load pair and a lerp instead of
/// the analytic form's two exponentials and two logarithms.
inline Halide::Func curve_table(Halide::ImageParam &configuration, int offset,
                                int stride, int channels,
                                const std::string &name,
                                Halide::DeviceAPI gpu = Halide::DeviceAPI::None,
                                bool approximate = false) {
    Halide::Var i(name + "_i"), c(name + "_c");
    Halide::Func table(name);
    Halide::Expr log_exposure = kCurveMin
        + (kCurveMax - kCurveMin) * (Halide::cast<float>(i)
                                     / float(kCurveSamples - 1));
    table(i, c) = curve_density(configuration, offset + c * stride,
                                log_exposure, approximate);
    table.compute_root().bound(i, 0, kCurveSamples).bound(c, 0, channels);
    if (gpu != Halide::DeviceAPI::None) {
        Halide::Var block_i(name + "_block_i"), thread_i(name + "_thread_i");
        table.reorder(c, i).unroll(c)
            .gpu_tile(i, block_i, thread_i, 64,
                      Halide::TailStrategy::GuardWithIf,
                      gpu);
    } else {
        table.reorder(i, c).unroll(c);
    }
    return table;
}

/// The three film curves, indexed (sample, layer).
inline Halide::Func film_curve_table(Halide::ImageParam &configuration,
                                     const std::string &name,
                                Halide::DeviceAPI gpu = Halide::DeviceAPI::None,
                                bool approximate = false) {
    Halide::Var i(name + "_i"), c(name + "_c");
    Halide::Func table(name);
    Halide::Expr log_exposure = kCurveMin
        + (kCurveMax - kCurveMin) * (Halide::cast<float>(i)
                                     / float(kCurveSamples - 1));
    table(i, c) = film_density(configuration, c, log_exposure, approximate);
    table.compute_root().bound(i, 0, kCurveSamples).bound(c, 0, 3);
    if (gpu != Halide::DeviceAPI::None) {
        Halide::Var block_i(name + "_block_i"), thread_i(name + "_thread_i");
        table.reorder(c, i).unroll(c)
            .gpu_tile(i, block_i, thread_i, 64,
                      Halide::TailStrategy::GuardWithIf, gpu);
    } else {
        table.reorder(i, c).unroll(c);
    }
    return table;
}

/// Where one paper record's six curve parameters sit: the green record in the
/// legacy FOTUFILM_CONFIG_PAPER slot, red and blue in the appended ones.
inline Halide::Expr paper_curve_base(Halide::Expr channel) {
    return Halide::select(channel == 0, FOTUFILM_CONFIG_PAPER_RED,
                          channel == 1, FOTUFILM_CONFIG_PAPER,
                          FOTUFILM_CONFIG_PAPER_BLUE);
}

/// One paper record's calibrated midpoint — the log exposure its anchor
/// density sits at, so a neutral mid-grey prints neutral through three
/// records that do not share a curve.
inline Halide::Expr paper_midpoint(Halide::ImageParam &configuration,
                                   Halide::Expr channel) {
    return Halide::select(
        channel == 0, configuration(FOTUFILM_CONFIG_PAPER_MIDPOINT_RED),
        channel == 1, configuration(FOTUFILM_CONFIG_PAPER_MIDPOINT),
        configuration(FOTUFILM_CONFIG_PAPER_MIDPOINT_BLUE));
}

/// The spectral LUT has already integrated the filtered printer lamp through the negative.
/// Midpoint slots include any uniform printer exposure shift; the calibration and film
/// densities remain fixed. Shared by CPU and GPU, including packed-LUT AOT variants.
inline Halide::Expr paper_exposure(Halide::ImageParam &configuration,
                                   Halide::Expr channel, Halide::Expr relative,
                                   bool approximate = false) {
    Halide::Expr flash = configuration(FOTUFILM_CONFIG_PRINTER_PREFLASH);
    Halide::Expr flashed_rel = fs_log10(
        Halide::max(fs_pow10(relative, approximate) + flash, 1.0e-12f),
        approximate);
    Halide::Expr effective_rel = Halide::select(flash > 0.0f, flashed_rel, relative);
    return paper_midpoint(configuration, channel)
        + configuration(FOTUFILM_CONFIG_MASKING + channel) * effective_rel;
}

/// The paper's three records, indexed (sample, channel). The bases are not
/// evenly strided, so this lays out `curve_table`'s body over
/// `paper_curve_base` rather than sharing its offset arithmetic.
inline Halide::Func paper_curve_table(Halide::ImageParam &configuration,
                                      const std::string &name,
                                Halide::DeviceAPI gpu = Halide::DeviceAPI::None,
                                bool approximate = false) {
    Halide::Var i(name + "_i"), c(name + "_c");
    Halide::Func table(name);
    Halide::Expr log_exposure = kCurveMin
        + (kCurveMax - kCurveMin) * (Halide::cast<float>(i)
                                     / float(kCurveSamples - 1));
    table(i, c) = curve_density(configuration, paper_curve_base(c),
                                log_exposure, approximate);
    table.compute_root().bound(i, 0, kCurveSamples).bound(c, 0, 3);
    if (gpu != Halide::DeviceAPI::None) {
        Halide::Var block_i(name + "_block_i"), thread_i(name + "_thread_i");
        table.reorder(c, i).unroll(c)
            .gpu_tile(i, block_i, thread_i, 64,
                      Halide::TailStrategy::GuardWithIf,
                      gpu);
    } else {
        table.reorder(i, c).unroll(c);
    }
    return table;
}

/// Reads a table built by `curve_table`.
inline Halide::Expr sample_curve(Halide::Func table, Halide::Expr log_exposure,
                                 Halide::Expr channel) {
    Halide::Expr q = Halide::clamp(
        (log_exposure - kCurveMin)
            * (float(kCurveSamples - 1) / (kCurveMax - kCurveMin)),
        0.0f, float(kCurveSamples - 1));
    Halide::Expr index = Halide::min(Halide::cast<int32_t>(q), kCurveSamples - 2);
    Halide::Expr frac = q - Halide::cast<float>(index);
    Halide::Expr low = table(index, channel);
    return low + frac * (table(index + 1, channel) - low);
}

/// dMax - dMin for the curve at `base`: gamma * (shoulder - toe).
inline Halide::Expr sample_film_curve(Halide::ImageParam &configuration,
                                     Halide::Func table, Halide::Expr x,
                                     Halide::Expr channel) {
    return Halide::select(has_sampled_film_curve(configuration, channel),
        sampled_film_density(configuration, channel, x), sample_curve(table, x, channel));
}

inline Halide::Expr curve_range(Halide::ImageParam &configuration, Halide::Expr base) {
    return configuration(base + 1) * (configuration(base + 4) - configuration(base + 2));
}

/// dMax - dMin for one dye-forming film record, including its optional second population.
inline Halide::Expr film_curve_range(Halide::ImageParam &configuration,
                                     Halide::Expr channel) {
    Halide::Expr base = FOTUFILM_CONFIG_CURVES + channel * 6;
    Halide::Expr secondary = FOTUFILM_CONFIG_CURVE_SECONDARY + channel * 5;
    return curve_range(configuration, base)
        + configuration(secondary)
            * (configuration(secondary + 3) - configuration(secondary + 1));
}

}

#endif
