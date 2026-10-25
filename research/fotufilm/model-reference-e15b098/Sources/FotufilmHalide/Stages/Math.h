#ifndef FOTUFILM_HALIDE_STAGES_MATH_H
#define FOTUFILM_HALIDE_STAGES_MATH_H

#include "FotufilmHalide.h"

#include <Halide.h>
#include <cmath>

namespace fotufilm {

/// Transcendental policy.
inline Halide::Expr fs_exp(Halide::Expr value, bool approximate) {
    return approximate ? Halide::fast_exp(value) : Halide::exp(value);
}

inline Halide::Expr fs_log(Halide::Expr value, bool approximate) {
    return approximate ? Halide::fast_log(value) : Halide::log(value);
}

inline Halide::Expr fs_pow(Halide::Expr base, Halide::Expr exponent,
                           bool approximate) {
    return approximate
        ? Halide::fast_pow(Halide::max(base, 1.0e-8f), exponent)
        : Halide::pow(base, exponent);
}

/// Preserve the CPU's division when converting natural log to photographic density.
/// Multiplying by a rounded reciprocal introduces another float32 rounding difference.
inline Halide::Expr fs_log10(Halide::Expr value, bool approximate) {
    return approximate ? fs_log(value, true) * (1.0f / 2.3025851f)
                       : Halide::log(value) / Halide::log(10.0f);
}

inline Halide::Expr fs_pow10(Halide::Expr value, bool approximate) {
    return approximate ? fs_exp(value * 2.3025851f, true)
                       : Halide::pow(10.0f, value);
}

inline Halide::Expr fs_cos(Halide::Expr value, bool approximate) {
    return approximate ? Halide::fast_cos(value) : Halide::cos(value);
}

inline Halide::Expr softplus(Halide::Expr value, bool approximate = false) {
    return Halide::select(value > 20.0f, value,
                          value < -20.0f, fs_exp(value, approximate),
                          fs_log(1.0f + fs_exp(value, approximate), approximate));
}

/// Soft display shoulder: rolls a display-linear channel off toward the gamut
/// ceiling instead of hard-clamping it at 1. A knee at 1 leaves every value
/// below display white alone; the bounded denominator keeps the arm the select
/// discards finite there.
inline Halide::Expr display_shoulder(Halide::Expr x, Halide::Expr knee) {
    Halide::Expr room = 1.0f - knee;
    Halide::Expr over = x - knee;
    Halide::Expr rolled = knee + room * over / Halide::max(over + room, 1.0e-20f);
    return Halide::select(x > knee, rolled, x);
}

}

#endif
