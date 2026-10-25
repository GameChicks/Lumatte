#ifndef FOTUFILM_HALIDE_STAGES_PRINT_H
#define FOTUFILM_HALIDE_STAGES_PRINT_H

#include "FotufilmHalide.h"
#include "Math.h"
#include "Curves.h"

#include <Halide.h>

namespace fotufilm {

inline Halide::Expr transmittance_of(Halide::Expr density, bool approximate = false) {
    return fs_pow10(-density, approximate);
}

inline Halide::Expr density_of(Halide::Expr transmittance, bool approximate = false) {
    return -fs_log10(Halide::max(transmittance, 1.0e-6f), approximate);
}

inline Halide::Expr print_mtf_read(Halide::ImageParam &configuration,
                                   Halide::Expr transmittance, Halide::Expr spread) {
    Halide::Expr keep = configuration(FOTUFILM_CONFIG_PRINT_SHARPEN);
    return Halide::select(keep > 0.0f, spread + keep * (transmittance - spread), spread);
}

inline Halide::Expr paper_activation(Halide::ImageParam &configuration,
                                     Halide::Func paper_curve, Halide::Expr channel,
                                     Halide::Expr relative,
                                     bool approximate = false) {
    Halide::Expr base = paper_curve_base(channel);
    Halide::Expr exposure = paper_exposure(configuration, channel, relative, approximate);
    return (sample_curve(paper_curve, exposure, channel) - configuration(base))
        / curve_range(configuration, base);
}

inline Halide::Expr texture_carry(Halide::Expr source, Halide::Expr developed,
                                  Halide::Expr flat, Halide::Expr reversal) {
    Halide::Expr difference = developed - flat;
    Halide::Expr signed_difference = Halide::select(reversal != 0, -difference, difference);
    return source * Halide::exp(signed_difference * 2.3025851f);
}

/// A scanned negative's light controls on display-linear print RGB (FOTUFILM_CONFIG_PRINT_FINISH):
/// gains, then highlights and shadows moving the ends of the print's luminance in stops about
/// mid-grey, then saturation and vibrance. Mirrors `PrintFinish.apply` expression for expression.
/// All-zero slots return `red`, `green` and `blue` untouched, bit for bit.
inline Halide::Expr print_finish(Halide::ImageParam &configuration, Halide::Expr channel,
                                 Halide::Expr red, Halide::Expr green, Halide::Expr blue,
                                 bool approximate = false) {
    using Halide::Expr;
    auto slot = [&](int index) { return configuration(FOTUFILM_CONFIG_PRINT_FINISH + index); };
    // Display P3 luminance, PrintFinish.luminance.
    constexpr float kR = 0.2289746f, kG = 0.6917385f, kB = 0.0792869f;
    Expr r = red * (1.0f + slot(0)), g = green * (1.0f + slot(1)), b = blue * (1.0f + slot(2));
    Expr luminance = kR * r + kG * g + kB * b;
    Expr stops = fs_log(Halide::max(luminance, 1.0e-6f) * (1.0f / 0.18f), approximate)
        * (1.0f / 0.6931472f);
    auto ease = [](Expr t) {
        Expr x = Halide::clamp(t, 0.0f, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    };
    // PrintFinish.endStops over highlightReach above mid-grey and shadowReach below.
    Expr moved = slot(3) * 0.75f * ease(stops * (1.0f / 3.0f))
        + slot(4) * 0.75f * ease(-stops * (1.0f / 4.0f));
    Expr tone = Halide::select(luminance > 1.0e-6f,
                               fs_exp(moved * 0.6931472f, approximate), 1.0f);
    Expr r1 = r * tone, g1 = g * tone, b1 = b * tone;
    Expr luma = kR * r1 + kG * g1 + kB * b1;
    Expr peak = Halide::max(r1, Halide::max(g1, b1));
    Expr colourfulness = (peak - Halide::min(r1, Halide::min(g1, b1)))
        / Halide::max(peak, 1.0e-6f);
    Expr chroma = (1.0f + slot(5)) * (1.0f + slot(6) * (1.0f - colourfulness));
    Expr finished = luma + chroma * (Halide::mux(channel, {r1, g1, b1}) - luma);
    Expr neutral = slot(0) == 0.0f && slot(1) == 0.0f && slot(2) == 0.0f && slot(3) == 0.0f
        && slot(4) == 0.0f && slot(5) == 0.0f && slot(6) == 0.0f;
    return Halide::select(neutral, Halide::mux(channel, {red, green, blue}), finished);
}

}

#endif
