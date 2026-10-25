#ifndef FOTUFILM_HALIDE_STAGES_COUPLERS_H
#define FOTUFILM_HALIDE_STAGES_COUPLERS_H

#include "FotufilmHalide.h"
#include "Curves.h"

#include <Halide.h>
#include <cmath>

namespace fotufilm {

/// Normalized Hill law for development-inhibitor release. It is applied before diffusion because
/// the released species diffuses, not the donor layer's latent activation. Gamma 1 preserves the
/// historical linear path exactly.
inline Halide::Expr inhibitor_release(Halide::Expr activation, Halide::Expr gamma) {
    Halide::Expr a = Halide::clamp(activation, 0.0f, 1.0f);
    Halide::Expr released = Halide::pow(a, gamma);
    Halide::Expr retained = Halide::pow(1.0f - a, gamma);
    Halide::Expr nonlinear = released / Halide::max(released + retained, 1.0e-8f);
    return Halide::select(gamma == 1.0f, a, nonlinear);
}

/// Positive Gaussian mixture for the isotropic screened-diffusion transport kernel.
inline Halide::Expr adjacency_transport(Halide::ImageParam &configuration,
                                        Halide::Expr primary, Halide::Expr secondary) {
    constexpr float share = 0.2753401713f;
    return Halide::select(configuration(FOTUFILM_CONFIG_ADJACENCY_MODEL) > 0.5f,
                          share * primary + (1.0f - share) * secondary, primary);
}

/// Nelson's density-weighted response, with a normalized source activation and the stock's
/// finite development capacity. Applied before reversal complementation and grain. Local DIR
/// inhibition is already in `formed`; only its spatial adjacency residual enters here.
inline Halide::Expr adjacency_density(Halide::ImageParam &configuration,
                                      Halide::Expr channel, Halide::Expr formed,
                                      Halide::Expr residual) {
    Halide::Expr base = configuration(FOTUFILM_CONFIG_CURVES + channel * 6);
    Halide::Expr net = Halide::max(formed - base, 0.0f);
    Halide::Expr corrected = base + Halide::clamp(
        net + configuration(FOTUFILM_CONFIG_ADJACENCY_STRENGTH) * net * residual,
        0.0f, film_curve_range(configuration, channel));
    return Halide::select(configuration(FOTUFILM_CONFIG_ADJACENCY_MODEL) > 0.5f,
                          corrected, formed);
}

inline Halide::Expr coupler_release(Halide::ImageParam &configuration,
                                    Halide::Expr donor, Halide::Expr activation) {
    return inhibitor_release(
        activation, configuration(FOTUFILM_CONFIG_COUPLER_RELEASE_GAMMA + donor));
}

inline Halide::Expr donor_release(Halide::ImageParam &configuration,
                                  Halide::Expr activation) {
    return inhibitor_release(
        activation, configuration(FOTUFILM_CONFIG_DONOR_RELEASE_GAMMA));
}

/// DIR coupler inhibition applied to `channel`, in log-exposure units: the inhibitor released by
/// each donor layer, weighted by K[channel][donor].
inline Halide::Expr coupler_inhibition(Halide::ImageParam &configuration,
                                       Halide::Expr channel, Halide::Expr donor0,
                                       Halide::Expr donor1, Halide::Expr donor2) {
    Halide::Expr base = FOTUFILM_CONFIG_COUPLER + channel * 3;
    Halide::Expr released = configuration(base) * donor0
                          + configuration(base + 1) * donor1
                          + configuration(base + 2) * donor2;
    return released * configuration(FOTUFILM_CONFIG_COUPLER_SCALE);
}

/// Redistribute only inter-layer inhibitor transport. Normalized core and broad fields agree
/// on constants, so the DC matrix and neutral anchor remain unchanged.
inline Halide::Expr chromatic_fringe_inhibition(Halide::ImageParam &configuration,
                                                Halide::Expr channel,
                                                Halide::Expr delta0, Halide::Expr delta1,
                                                Halide::Expr delta2) {
    Halide::Expr base = FOTUFILM_CONFIG_COUPLER + channel * 3;
    Halide::Expr residual = Halide::select(channel != 0, configuration(base) * delta0, 0.0f)
                         + Halide::select(channel != 1, configuration(base + 1) * delta1, 0.0f)
                         + Halide::select(channel != 2, configuration(base + 2) * delta2, 0.0f);
    return residual * configuration(FOTUFILM_CONFIG_COUPLER_SCALE)
                    * configuration(FOTUFILM_CONFIG_CHROMATIC_FRINGE_AMOUNT);
}

/// Neutral anchor for the coupler stage: the log-exposure offset that undoes the inhibition a
/// neutral subject of the same luminance would have released.
inline Halide::Expr coupler_warp(Halide::ImageParam &configuration,
                                 Halide::Expr channel, Halide::Expr u) {
    constexpr int samples = FOTUFILM_COUPLER_WARP_SAMPLES;
    constexpr float low = float(FOTUFILM_COUPLER_WARP_MIN);
    constexpr float high = float(FOTUFILM_COUPLER_WARP_MAX);
    Halide::Expr q = Halide::clamp((u - low) * (float(samples - 1) / (high - low)),
                                   0.0f, float(samples - 1));
    Halide::Expr index = Halide::min(Halide::cast<int32_t>(q), samples - 2);
    Halide::Expr frac = q - Halide::cast<float>(index);
    Halide::Expr base = FOTUFILM_CONFIG_COUPLER_WARP + channel * samples;
    Halide::Expr low_sample = configuration(base + index);
    Halide::Expr high_sample = configuration(base + index + 1);
    Halide::Expr linear = low_sample + frac * (high_sample - low_sample);

    // The nonlinear release curve bends the inverse more strongly between samples. A monotone
    // cubic keeps the 128-sample ABI precise enough for the neutral anchor, while gamma 1 takes
    // the original linear interpolation bit-for-bit.
    Halide::Expr previous = configuration(base + Halide::max(index - 1, 0));
    Halide::Expr following = configuration(base + Halide::min(index + 2, samples - 1));
    Halide::Expr delta = Halide::max(high_sample - low_sample, 0.0f);
    Halide::Expr low_slope = Halide::clamp(
        0.5f * (high_sample - previous), 0.0f, 3.0f * delta);
    Halide::Expr high_slope = Halide::clamp(
        0.5f * (following - low_sample), 0.0f, 3.0f * delta);
    Halide::Expr frac2 = frac * frac;
    Halide::Expr frac3 = frac2 * frac;
    Halide::Expr cubic = (2.0f * frac3 - 3.0f * frac2 + 1.0f) * low_sample
                       + (frac3 - 2.0f * frac2 + frac) * low_slope
                       + (-2.0f * frac3 + 3.0f * frac2) * high_sample
                       + (frac3 - frac2) * high_slope;
    Halide::Expr nonlinear =
        configuration(FOTUFILM_CONFIG_COUPLER_RELEASE_GAMMA) != 1.0f
        || configuration(FOTUFILM_CONFIG_COUPLER_RELEASE_GAMMA + 1) != 1.0f
        || configuration(FOTUFILM_CONFIG_COUPLER_RELEASE_GAMMA + 2) != 1.0f
        || configuration(FOTUFILM_CONFIG_DONOR_RELEASE_GAMMA) != 1.0f;
    return Halide::select(nonlinear, cubic, linear);
}

}

#endif
