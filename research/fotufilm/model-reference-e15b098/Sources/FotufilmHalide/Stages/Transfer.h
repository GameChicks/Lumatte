#ifndef FOTUFILM_HALIDE_STAGES_TRANSFER_H
#define FOTUFILM_HALIDE_STAGES_TRANSFER_H

#include "FotufilmHalide.h"
#include "Math.h"

#include <Halide.h>
#include <cmath>

namespace fotufilm {

/// The sRGB transfer's slope where it reaches white, which is what continues it above white.
#define FOTUFILM_SRGB_SLOPE_AT_WHITE (1.055f / 2.4f)

/// The sRGB transfer, forward and back — the signal a grading suite's three-way corrector works on.
///
/// Over 0…1 this is sRGB exactly, so the three-way lands where a colourist expects. Outside it the
/// curve is continued by its own end slopes rather than clamped, which keeps the pair an exact
/// inverse over the whole range: a neutral grade has to stay a neutral grade, and the light a print
/// carries above display white has to survive to meet the shoulder that rolls it.
/// Mirrors ColorScience.gradingEncode / gradingDecode expression for expression.
inline Halide::Expr srgb_encode(Halide::Expr linear, bool approximate = false) {
    Halide::Expr curve =
        1.055f * fs_pow(Halide::max(linear, 0.0031308f), 1.0f / 2.4f,
                        approximate) - 0.055f;
    return Halide::select(
        linear <= 0.0031308f, linear * 12.92f,
        linear >= 1.0f, 1.0f + (linear - 1.0f) * FOTUFILM_SRGB_SLOPE_AT_WHITE,
        curve);
}

inline Halide::Expr srgb_decode(Halide::Expr coded, bool approximate = false) {
    Halide::Expr curve = fs_pow(
        Halide::max((coded + 0.055f) / 1.055f, 0.0f), 2.4f, approximate);
    return Halide::select(
        coded <= 0.04045f, coded / 12.92f,
        coded >= 1.0f, 1.0f + (coded - 1.0f) / FOTUFILM_SRGB_SLOPE_AT_WHITE,
        curve);
}

/// Host transfer for FOTUFILM_FRAME_ENCODE_OUT, the shape given as an index: 0 linear, 1 power,
/// 2 logarithmic. Logarithmic coefficients include the change of base. A constant index folds
/// to its one arm; a runtime one evaluates every shape and selects, which is what the
/// coefficient-driven reference kernel does.
inline Halide::Expr host_transfer_encode_shaped(Halide::ImageParam &configuration,
                                                Halide::Expr value, bool approximate,
                                                Halide::Expr shape_index) {
    auto coefficient = [&](int index) {
        return configuration(FOTUFILM_CONFIG_OUTPUT_COEFFICIENTS + index);
    };
    Halide::Expr magnitude = Halide::abs(value);
    Halide::Expr power = Halide::select(value < 0.0f, -1.0f, 1.0f)
        * Halide::select(magnitude <= coefficient(4),
                         magnitude * coefficient(0),
                         coefficient(1) * fs_pow(magnitude, coefficient(2), approximate)
                             + coefficient(3));
    Halide::Expr logarithmic = Halide::select(
        value <= coefficient(4),
        value * coefficient(0) + coefficient(3),
        coefficient(1) * fs_log(value + coefficient(5), approximate) + coefficient(2));
    return Halide::select(shape_index == 0, value, shape_index == 1, power, logarithmic);
}

/// The coefficient-driven shape: the configuration names it.
inline Halide::Expr host_transfer_shape(Halide::ImageParam &configuration) {
    Halide::Expr shape = configuration(FOTUFILM_CONFIG_OUTPUT_TRANSFER);
    return Halide::select(shape < 0.5f, 0, shape < 1.5f, 1, 2);
}

/// Host transfer for FOTUFILM_FRAME_ENCODE_OUT. Coefficient-driven transfer shapes avoid evaluating
/// one GPU branch per colour space; a compile-time `transfer_shape` keeps only that arm.
inline Halide::Expr host_transfer_encode(Halide::ImageParam &configuration,
                                         Halide::Expr value,
                                         bool approximate = false,
                                         int transfer_shape = -1) {
    return host_transfer_encode_shaped(
        configuration, std::move(value), approximate,
        transfer_shape >= 0 ? Halide::Expr(transfer_shape)
                            : host_transfer_shape(configuration));
}

/// `host_transfer_encode` with the shape named at run time: a pipeline compiled once per shape
/// folds each of these to the one arm, and with none named takes the coefficient-driven choice.
inline Halide::Expr host_transfer_encode(Halide::ImageParam &configuration,
                                         Halide::Expr value, bool approximate,
                                         Halide::Expr linear, Halide::Expr power,
                                         Halide::Expr logarithmic) {
    return host_transfer_encode_shaped(
        configuration, std::move(value), approximate,
        Halide::select(linear, 0, power, 1, logarithmic, 2,
                       host_transfer_shape(configuration)));
}

/// The host's own transfer undone, applied to an arriving channel on its way into the engine —
/// `host_transfer_encode` read backwards, and coefficient-driven for the same reason: both sides
/// of a Halide `select` are evaluated, so a branch per colour space would have the GPU compute
/// every space's transcendental to keep one. See `fotufilm::inputTransformFor`, which is the only
/// definition of which coefficients a space takes, for the shapes.
///
/// Reference schedules use exact transcendentals. Realtime schedules may request the same bounded
/// approximations used by their film stages; the shape and coefficients remain identical.
inline Halide::Expr host_transfer_decode(Halide::ImageParam &parameters,
                                         Halide::Expr value,
                                         bool approximate = false) {
    auto coefficient = [&](int index) {
        return parameters(FOTUFILM_DECODE_COEFFICIENTS + index);
    };
    Halide::Expr magnitude = Halide::abs(value);
    Halide::Expr power = Halide::select(value < 0.0f, -1.0f, 1.0f)
        * Halide::select(magnitude <= coefficient(4),
                         magnitude * coefficient(0),
                         fs_pow(coefficient(1) * magnitude + coefficient(3),
                                coefficient(2), approximate));
    // The branch is on the signed value, not its magnitude: a negative DaVinci Intermediate code
    // stays on the linear toe rather than being reflected through the log arm.
    Halide::Expr exponential = Halide::select(
        value <= coefficient(4),
        (value - coefficient(3)) * coefficient(0),
        fs_exp(value * coefficient(1) - coefficient(2), approximate) - coefficient(5));
    Halide::Expr shape = parameters(FOTUFILM_DECODE_TRANSFER);
    return Halide::select(shape < 0.5f, value, shape < 1.5f, power, exponential);
}

/// The host's own last step as one expression: out of the print's Display P3 delivery basis into
/// the host's primaries, through the SDR shoulder that delivery asked for, and into the host's
/// transfer. `row` selects the output channel, so the caller muxes three of these rather than
/// evaluating a matrix per channel.
///
/// The shoulder sits between the matrix and the transfer because that is where the host takes it:
/// `FilmOutputConversion.sRGBSDR` shoulders the converted sRGB value, not the P3 one it started
/// from, and `FilmDisplayP3SDRConversion` — whose matrix is the identity — cannot tell the
/// difference. A negative knee is no shoulder at all, which is what the linear and unshouldered
/// spaces want; every other value rolls off toward 1 exactly as `ColorScience.displayShoulder`
/// does, because it is the same curve.
///
/// The single definition of the step, shared by the fused GPU pipeline and the staged CPU one, so
/// that a delivery cannot mean two things depending on which road developed it.
inline Halide::Expr host_output_shouldered(Halide::ImageParam &configuration,
                                           Halide::Expr r, Halide::Expr g,
                                           Halide::Expr b, int row) {
    Halide::Expr host[3];
    for (int c = 0; c < 3; ++c) {
        host[c] = configuration(FOTUFILM_CONFIG_OUTPUT_MATRIX + 3 * c) * r
            + configuration(FOTUFILM_CONFIG_OUTPUT_MATRIX + 3 * c + 1) * g
            + configuration(FOTUFILM_CONFIG_OUTPUT_MATRIX + 3 * c + 2) * b;
    }
    // Same neutral-axis fit as the plugin's CPU fallback (fotufilm::fitToGamut).
    // Select operands are evaluated eagerly: bound unused denominators away from zero.
    Halide::Expr y = configuration(FOTUFILM_CONFIG_OUTPUT_GAMUT + 1) * host[0]
        + configuration(FOTUFILM_CONFIG_OUTPUT_GAMUT + 2) * host[1]
        + configuration(FOTUFILM_CONFIG_OUTPUT_GAMUT + 3) * host[2];
    Halide::Expr scale = 1.0f;
    for (const Halide::Expr &v : host) {
        scale = Halide::min(scale, Halide::select(
            v > 1.0f, (1.0f - y) / Halide::max(v - y, 1.0e-20f),
            v < 0.0f, y / Halide::max(y - v, 1.0e-20f), 1.0f));
    }
    Halide::Expr fitted = Halide::select(
        !(y > 0.0f && y < 1.0f), Halide::max(host[row], 0.0f),
        scale >= 1.0f, host[row],
        y + (host[row] - y) * Halide::max(scale, 0.0f));
    Halide::Expr inside = Halide::min(host[0], Halide::min(host[1], host[2])) >= 0.0f
        && Halide::max(host[0], Halide::max(host[1], host[2])) <= 1.0f;
    Halide::Expr in_host_primaries = Halide::select(
        configuration(FOTUFILM_CONFIG_OUTPUT_GAMUT) == 0.0f || inside,
        host[row], fitted);
    Halide::Expr knee = configuration(FOTUFILM_CONFIG_OUTPUT_SHOULDER);
    return Halide::select(
        knee < 0.0f, in_host_primaries,
        display_shoulder(in_host_primaries,
                         Halide::clamp(knee, 0.0f, 1.0f)));
}

inline Halide::Expr host_output_encode(Halide::ImageParam &configuration,
                                       Halide::Expr r, Halide::Expr g,
                                       Halide::Expr b, int row,
                                       bool approximate = false,
                                       int transfer_shape = -1) {
    return host_transfer_encode(configuration,
                                host_output_shouldered(configuration, r, g, b, row),
                                approximate, transfer_shape);
}

inline Halide::Expr host_output_encode(Halide::ImageParam &configuration,
                                       Halide::Expr r, Halide::Expr g,
                                       Halide::Expr b, int row, bool approximate,
                                       Halide::Expr linear, Halide::Expr power,
                                       Halide::Expr logarithmic) {
    return host_transfer_encode(configuration,
                                host_output_shouldered(configuration, r, g, b, row),
                                approximate, std::move(linear), std::move(power),
                                std::move(logarithmic));
}

/// The print grade: lift, gamma and gain applied per channel.
///
/// By default it works on the display-linear print value. When
/// FOTUFILM_CONFIG_GRADE_SPACE is set it instead works on the sRGB-encoded signal and hands linear
/// light back, which is where a grading suite's three-way corrector operates — the encode is the
/// grading space, not the output transfer, so SDR and HDR still grade alike.
inline Halide::Expr color_grade(Halide::ImageParam &configuration,
                                Halide::Expr channel, Halide::Expr value,
                                bool approximate = false) {
    Halide::Expr lift = configuration(FOTUFILM_CONFIG_GRADE_LIFT + channel);
    Halide::Expr gain = configuration(FOTUFILM_CONFIG_GRADE_GAIN + channel);
    Halide::Expr inverse_gamma =
        configuration(FOTUFILM_CONFIG_GRADE_INV_GAMMA + channel);
    Halide::Expr encoded = configuration(FOTUFILM_CONFIG_GRADE_SPACE) != 0.0f;
    Halide::Expr working =
        Halide::select(encoded, srgb_encode(value, approximate), value);
    Halide::Expr lifted = working * (gain - lift) + lift;
    Halide::Expr graded =
        Halide::select(inverse_gamma == 1.0f, lifted,
                       fs_pow(Halide::max(lifted, 0.0f), inverse_gamma,
                              approximate));
    return Halide::select(encoded, srgb_decode(graded, approximate), graded);
}

}

#endif
