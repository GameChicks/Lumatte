#ifndef FOTUFILM_HALIDE_STAGES_DEVELOPMENT_H
#define FOTUFILM_HALIDE_STAGES_DEVELOPMENT_H

#include "FotufilmHalide.h"
#include "Curves.h"
#include "Couplers.h"

#include <Halide.h>

namespace fotufilm {

inline Halide::Expr film_activation(Halide::ImageParam &configuration,
                                    Halide::Expr channel, Halide::Expr formed) {
    Halide::Expr base = FOTUFILM_CONFIG_CURVES + channel * 6;
    return (formed - configuration(base)) / film_curve_range(configuration, channel);
}

inline Halide::Expr donor_inhibition(Halide::ImageParam &configuration,
                                     Halide::Expr channel, Halide::Expr donor_released) {
    return configuration(FOTUFILM_CONFIG_DONOR_RELEASE + channel) * donor_released
        * configuration(FOTUFILM_CONFIG_COUPLER_SCALE);
}

inline Halide::Expr fringe_inhibition(Halide::ImageParam &configuration,
                                      Halide::Expr channel, Halide::Expr inhibition,
                                      Halide::Expr fringe_radius,
                                      Halide::Expr delta0, Halide::Expr delta1,
                                      Halide::Expr delta2) {
    Halide::Expr fringe = chromatic_fringe_inhibition(
        configuration, channel, delta0, delta1, delta2);
    return Halide::select(fringe_radius > 0, inhibition + fringe, inhibition);
}

inline Halide::Expr inhibited_log_exposure(Halide::ImageParam &configuration,
                                           Halide::Expr channel, Halide::Expr log_exposure,
                                           Halide::Expr inhibition) {
    Halide::Expr u = log_exposure - inhibition;
    return u + coupler_warp(configuration, channel, u);
}

inline Halide::Expr adjacency_shift(Halide::ImageParam &configuration,
                                    Halide::Expr residual) {
    return Halide::select(
        configuration(FOTUFILM_CONFIG_ADJACENCY_MODEL) > 0.5f, 0.0f,
        -configuration(FOTUFILM_CONFIG_ADJACENCY_STRENGTH) * residual);
}

inline Halide::Expr developed_density(Halide::ImageParam &configuration,
                                      Halide::Expr channel, Halide::Expr formed) {
    Halide::Expr base = FOTUFILM_CONFIG_CURVES + channel * 6;
    Halide::Expr d_min = configuration(base);
    Halide::Expr range = film_curve_range(configuration, channel);
    return Halide::select(
        configuration(FOTUFILM_CONFIG_DEVELOP_COMPLEMENT) > 0.5f,
        d_min + range - (formed - d_min), formed);
}

struct DensityPosition {
    Halide::Expr amount, net;
};

inline DensityPosition density_position(Halide::ImageParam &configuration,
                                        Halide::Expr channel, Halide::Expr density) {
    Halide::Expr base = FOTUFILM_CONFIG_CURVES + channel * 6;
    Halide::Expr range = film_curve_range(configuration, channel);
    Halide::Expr amount = Halide::clamp(
        (density - configuration(base)) / range, 0.0f, 1.0f);
    return {amount, amount * range};
}

}

#endif
