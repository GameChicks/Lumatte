#ifndef FOTUFILM_HALIDE_GRAPH_FRAME_H
#define FOTUFILM_HALIDE_GRAPH_FRAME_H

#include "FotufilmHalide.h"
#include "../FotufilmHalideShared.h"
#include "../FotufilmHalideFrameParams.h"

#include <Halide.h>
#include <array>
#include <functional>
#include <set>
#include <string>
#include <vector>

namespace fotufilm {
namespace graph {

using Halide::Expr;
using Halide::Func;
using Halide::Var;

/// The points at which the frame sequence materialises a Func. A backend decides at each one
/// whether to store at all and in which precision; the sequence never schedules anything itself.
enum class Store {
    Exposure,
    Diffused,
    Light,
    TextureLight,
    MtfMixed,
    MtfLumaDirect,
    MtfSeparated,
    MtfSelected,
    HalationReturned,
    LogExposure,
    Activation,
    Released,
    DonorLog,
    DonorActivation,
    DonorReleased,
    FlatLog,
    FlatActivation,
    FlatReleased,
    FlatDensity,
    Density,
    Noise,
    MottleNoise,
    Inhibition,
    FilmDensity,
    FilmGrain,
    Transmittance,
    FlatTransmittance,
    Printed,
    FlatPrinted,
    PrintMtfInput,
    PrintActivation,
    PaperActivation,
    Display,
    Output,
};

struct GrainFields {
    Func grain;
    Func mottle;
    int channels = 3;
};

struct Backend {
    virtual ~Backend() = default;

    virtual bool approximate() const { return false; }
    virtual bool half_lut_math() const { return false; }
    virtual bool tabulated_curves() const { return true; }
    virtual bool realtime() const { return false; }
    virtual bool windowed() const { return false; }
    virtual bool merged_luma() const { return false; }
    virtual bool grain_on_density_input() const { return false; }
    virtual Halide::DeviceAPI device() const { return Halide::DeviceAPI::None; }

    /// `branch` is the gate of a stage select the values read; a backend may compile the pass
    /// once per side of it.
    virtual Func store(Func values, Store point, int channels, Expr branch) = 0;
    Func store(Func values, Store point, int channels = 3) {
        return store(std::move(values), point, channels, Expr());
    }

    virtual Func gaussian(Func source, Expr sigma0, Expr sigma1, Expr sigma2, Expr radius,
                          Expr width, Expr height, const std::string &name,
                          int channels = 3, Expr luma_sigma = Expr(),
                          Expr luma_radius = Expr()) = 0;

    virtual Func gaussian_decimated(Func source, Expr sigma, Expr radius, Expr origin_x,
                                    Expr origin_y, Expr width, Expr height,
                                    const std::string &name, int channels = 3) = 0;

    virtual std::array<Expr, 3> scattered(Func light, std::array<Expr, 3> strides,
                                          std::array<Expr, 3> strided_radii,
                                          Expr width, Expr height, Expr origin_x, Expr origin_y,
                                          Var x, Var y, Var c, int ring_config_base,
                                          bool annular, const std::string &name,
                                          int channels) = 0;

    virtual Func frame_mean(Func light, int channels, Expr width, Expr height,
                            const std::string &name) = 0;

    virtual Expr film_curve(Halide::ImageParam &configuration, Func table, Expr channel,
                            Expr log_exposure) {
        return sample_film_curve(configuration, table, log_exposure, channel);
    }

    virtual Expr donor_curve(Halide::ImageParam &configuration, Func table, Expr log_exposure) {
        return sample_curve(table, log_exposure, 0);
    }

    virtual GrainFields grain_fields(Halide::ImageParam &configuration, FrameParams &p,
                                     Expr monochrome, bool use_mottle, Var x, Var y, Var c,
                                     Expr width, Expr height, const std::string &prefix,
                                     const std::string &suffix) = 0;

    virtual Expr film_lut_sample(Expr ar, Expr ag, Expr ab, Expr channel) = 0;
    virtual Expr paper_lut_sample(Expr ax, Expr ay, Expr az, Expr channel) = 0;
};

struct Inputs {
    Halide::ImageParam &configuration;
    Halide::ImageParam &exposure_lut;
    FrameParams &p;
    Halide::Param<int32_t> &features;
    int32_t compiled;
    std::function<Expr(int)> decoded;
    std::function<Expr(Expr)> density_source;
    std::function<Expr(Expr)> texture_source;
    Expr monochrome;
    Expr grain_mode;
    bool texture;
    bool density_in;
    bool record_in;
    bool light_out;
    bool runtime_gates;
    std::string prefix;
    std::string suffix;
    /// The film grain model's tiles (grain mode 1), when this pipeline carries them, and whether
    /// this frame lays them — a scalar, so the grain stages either side are skipped rather than
    /// computed and discarded.
    Halide::ImageParam *film_tiles = nullptr;
    Expr film_on = Expr();
    /// Optional photographic record exposure arriving behind the camera gate (RGB records + donor).
    /// Coordinates are local to this tile. This light is excluded from lens-glare measurement.
    std::function<Expr(Expr, Expr, Expr)> additional_record_exposure;

};

struct Developed {
    Func light;
    Func developed;
    Func flat_density;
};

inline Expr gate(const Inputs &in, int32_t bit) {
    if ((in.compiled & bit) == 0) return Halide::Internal::const_false();
    if (!in.runtime_gates) return Halide::Internal::const_true();
    return (in.features & bit) != 0;
}

// Every read of a gated stage's field sits directly under a select on that stage's gate, and
// the gate of a stage nested in another carries the outer gate too: common-subexpression
// elimination lifts a repeated read out to a let, and only the select it is lifted with tells
// the skip-stages pass when the field is needed.
inline Expr gated(Expr on, Expr staged, Expr bypass) {
    if (Halide::Internal::is_const_one(on)) return staged;
    if (Halide::Internal::is_const_zero(on)) return bypass;
    return Halide::select(std::move(on), std::move(staged), std::move(bypass));
}

inline Func select_stage(Expr on, Func staged, Func bypass, Var x, Var y, Var c,
                         const std::string &name) {
    if (Halide::Internal::is_const_one(on)) return staged;
    if (Halide::Internal::is_const_zero(on)) return bypass;
    Func selected(name);
    selected(x, y, c) = gated(on, staged(x, y, c), bypass(x, y, c));
    return selected;
}

inline Func widen_with_luminance(Func planes, Var x, Var y, Var c, const std::string &name) {
    Func wide(name);
    wide(x, y, c) = Halide::select(c == 3, record_neutral(planes, x, y),
                                   planes(x, y, Halide::min(c, 2)));
    return wide;
}

inline Developed build_develop(Backend &b, const Inputs &in, Var x, Var y, Var c) {
    Halide::ImageParam &configuration = in.configuration;
    FrameParams &p = in.p;
    const std::string &suffix = in.suffix;
    auto name = [&](const char *stage) { return in.prefix + stage + suffix; };
    const bool approximate = b.approximate();

    const bool density_in = in.density_in;
    const int32_t compiled = in.compiled;
    const bool use_flare = !density_in && (compiled & FOTUFILM_FRAME_FLARE);
    const bool use_mtf = !density_in && (compiled & FOTUFILM_FRAME_MTF);
    const bool use_mtf_luma = use_mtf && (compiled & FOTUFILM_FRAME_MTF_LUMA);
    const bool use_diffusion = !density_in && (compiled & FOTUFILM_FRAME_DIFFUSION);
    const bool use_halation = !density_in && (compiled & FOTUFILM_FRAME_HALATION);
    const bool use_annular = use_halation && (compiled & FOTUFILM_FRAME_HALATION_ANNULAR);
    const bool use_couplers = !density_in && (compiled & FOTUFILM_FRAME_COUPLERS);
    const bool use_donor = !density_in && (compiled & FOTUFILM_FRAME_DONOR_LAYER);
    const bool use_coupler_diffusion =
        !density_in && (compiled & FOTUFILM_FRAME_COUPLER_DIFFUSION);
    const bool use_adjacency = !density_in && (compiled & FOTUFILM_FRAME_ADJACENCY);
    const bool use_grain = (compiled & FOTUFILM_FRAME_GRAIN)
        && (!density_in || b.grain_on_density_input());
    const bool use_mottle = use_grain && (compiled & FOTUFILM_FRAME_GRAIN_MOTTLE);
    const bool use_print_mtf = compiled & FOTUFILM_FRAME_PRINT_MTF;

    Expr on_flare = gate(in, FOTUFILM_FRAME_FLARE);
    Expr on_mtf = gate(in, FOTUFILM_FRAME_MTF);
    Expr on_mtf_luma = on_mtf && gate(in, FOTUFILM_FRAME_MTF_LUMA);
    Expr on_diffusion = gate(in, FOTUFILM_FRAME_DIFFUSION);
    Expr on_halation = gate(in, FOTUFILM_FRAME_HALATION);
    Expr on_couplers = gate(in, FOTUFILM_FRAME_COUPLERS);
    Expr on_donor = gate(in, FOTUFILM_FRAME_DONOR_LAYER);
    Expr on_coupler_diffusion = on_couplers && gate(in, FOTUFILM_FRAME_COUPLER_DIFFUSION);
    Expr on_adjacency = gate(in, FOTUFILM_FRAME_ADJACENCY);
    Expr on_grain = gate(in, FOTUFILM_FRAME_GRAIN);
    Expr on_mottle = on_grain && gate(in, FOTUFILM_FRAME_GRAIN_MOTTLE);
    Expr on_print_mtf = gate(in, FOTUFILM_FRAME_PRINT_MTF);

    Func curves;
    if (b.tabulated_curves()) {
        curves = film_curve_table(configuration, name("curve_table"), b.device(), approximate);
    }
    auto film_curve = [&](Expr channel, Expr log_exposure) {
        return b.film_curve(configuration, curves, channel, log_exposure);
    };

    // A record input's fourth channel is a donor stock's fourth record.
    Expr donor_record = use_donor ? in.decoded(3) : Expr();
    Func exposure(name("exposure"));
    exposure(x, y, c) = in.record_in
        ? (use_donor ? Halide::mux(c, {in.decoded(0), in.decoded(1), in.decoded(2), donor_record})
                     : Halide::mux(c, {in.decoded(0), in.decoded(1), in.decoded(2)}))
        : scene_exposure(configuration, in.exposure_lut, in.decoded(0), in.decoded(1),
                         in.decoded(2), c, x + p.origin_x_, y + p.origin_y_,
                         approximate, b.half_lut_math(), donor_record);

    const int exposure_channels = use_donor ? 4 : 3;
    Func light = exposure;
    Expr light_branch;
    auto store_light = [&](Func values, Store point, int channels) {
        Func view = b.store(values, point, channels, light_branch);
        light_branch = Expr();
        return view;
    };
    if (use_diffusion) {
        Func base = b.store(exposure, Store::Exposure, exposure_channels);
        std::array<Expr, 3> scattered = b.scattered(
            base,
            {p.diffusion_stride_0_, p.diffusion_stride_1_, p.diffusion_stride_2_},
            {p.diffusion_strided_radius_0_, p.diffusion_strided_radius_1_,
             p.diffusion_strided_radius_2_},
            p.width_, p.height_, p.origin_x_, p.origin_y_, x, y, c, -1, false,
            name("diffusion_"), exposure_channels);
        Func diffused(name("diffused"));
        diffused(x, y, c) = diffusion_mix(configuration, c, base(x, y, c),
                                          scattered[0], scattered[1], scattered[2]);
        Func diffused_view = b.store(diffused, Store::Diffused, exposure_channels);
        // The bypass recomputes the exposure where the next pass stores it, so the exposure
        // pass exists only for the diffusion that reads it.
        light = select_stage(on_diffusion, diffused_view, exposure, x, y, c,
                             name("diffusion_selected"));
        light_branch = on_diffusion;
    }

    Func donor_exposure(name("donor_exposure"));
    if (use_donor) {
        if (use_diffusion) {
            donor_exposure(x, y, c) = light(x, y, 3);
            // The donor still reads the fourth diffused record. Give subsequent RGB
            // materialisations their own function, so scheduling them with three
            // channels cannot narrow the shared donor exposure to three as well.
            Func rgb(name("diffusion_rgb"));
            rgb(x, y, c) = light(x, y, Halide::min(c, 2));
            light = rgb;
        } else {
            donor_exposure(x, y, c) = scene_exposure(
                configuration, in.exposure_lut, in.decoded(0), in.decoded(1),
                in.decoded(2), 3, x + p.origin_x_, y + p.origin_y_, approximate,
                b.half_lut_math(), donor_record);
        }
    }

    // The light the lens formed — the image, a diffusion filter's halo, a preflash, and veiling
    // glare below — reaches the film only through the camera gate. Everything after it is the
    // film's own and runs over the whole frame, the part the gate shades included.
    Expr gate_pass = gate_transmission(configuration, x + p.origin_x_, y + p.origin_y_);
    auto through_gate = [&](Func planes, const char *stage, bool donor = false, bool luma = false) {
        Func gated(name(stage));
        Expr value = planes(x, y, c) * gate_pass;
        if (in.additional_record_exposure) {
            auto &extra = in.additional_record_exposure;
            Expr added = extra(x, y, donor ? Expr(3) : Halide::min(c, 2));
            if (luma) added = Halide::select(c == 3,
                (extra(x, y, 0) + extra(x, y, 1) + extra(x, y, 2)) / 3.0f, added);
            value += added;
        }
        gated(x, y, c) = value;
        return gated;
    };
    // Glare is metered on the lens's image as formed, before the gate.
    Func lens_light = light;
    light = through_gate(lens_light, "through_gate");
    if (use_donor) donor_exposure = through_gate(donor_exposure, "donor_through_gate", true);

    const bool merged_luma = use_mtf_luma && b.merged_luma();
    const int light_channels = merged_luma ? 4 : 3;
    auto widened = [&](Func planes, const char *stage) {
        return merged_luma ? widen_with_luminance(planes, x, y, c, name(stage)) : planes;
    };
    // The gated light with its neutral: the lens's neutral through the gate, so that no sum
    // takes the gate's products, which a GPU compiler may fuse differently in each kernel.
    auto widened_light = [&](const char *stage, const char *gate_stage) {
        return merged_luma ? through_gate(widened(lens_light, stage), gate_stage, false, true) : light;
    };

    bool materialised = false;
    Func flare_mean;
    if (use_flare) {
        Func exposure_view = store_light(widened_light("exposure_luma", "exposure_gate"), Store::Light,
                                         light_channels);
        flare_mean = b.frame_mean(widened(lens_light, "lens_luma"), light_channels, p.width_,
                                  p.height_, name("flare"));
        // Gated through its fraction: the blur behind reads this per tap, and with the
        // fraction and mean both zero a tap is the exposure exactly.
        Func flared(name("flared"));
        flared(x, y, c) = veiling_glare(exposure_view(x, y, c),
                                        gate_pass * gated(on_flare, flare_mean(c), 0.0f),
                                        gated(on_flare, configuration(FOTUFILM_CONFIG_FLARE), 0.0f));
        light = flared;
        materialised = true;
    }

    if (in.texture && !materialised) {
        light = store_light(widened_light("texture_light_luma", "texture_light_gate"), Store::TextureLight,
                            light_channels);
        materialised = true;
    }
    Func flat_light = light;

    if (use_mtf) {
        Func pre_mtf = materialised
            ? light : store_light(widened_light("light_luma", "light_gate"), Store::Light, light_channels);
        materialised = true;
        Expr mtf_radius = Halide::max(
            p.mtf_radius_0_, Halide::max(p.mtf_radius_1_, p.mtf_radius_2_));
        Func per_layer = b.gaussian(
            pre_mtf, p.mtf_sigma_0_, p.mtf_sigma_1_, p.mtf_sigma_2_, mtf_radius,
            p.width_, p.height_, name("mtf"), light_channels,
            merged_luma ? Expr(p.mtf_luma_sigma_) : Expr(),
            merged_luma ? Expr(p.mtf_luma_radius_) : Expr());
        Func combined = per_layer;
        if (use_mtf_luma) {
            Func secondary = b.gaussian(
                pre_mtf,
                Halide::max(configuration(FOTUFILM_CONFIG_MTF_SECONDARY_SIGMA), 0.151f),
                Halide::max(configuration(FOTUFILM_CONFIG_MTF_SECONDARY_SIGMA + 1), 0.151f),
                Halide::max(configuration(FOTUFILM_CONFIG_MTF_SECONDARY_SIGMA + 2), 0.151f),
                p.mtf_luma_radius_, p.width_, p.height_, name("mtf_secondary"), 3);
            Func mixed(name("mtf_mixed"));
            Expr primary_share = configuration(FOTUFILM_CONFIG_MTF_PRIMARY_SHARE + c);
            mixed(x, y, c) = primary_share * per_layer(x, y, c)
                + (1.0f - primary_share) * secondary(x, y, c);
            Func mixed_view = b.store(mixed, Store::MtfMixed, 3);
            Expr luma_blurred;
            if (merged_luma) {
                luma_blurred = per_layer(x, y, 3);
            } else {
                Func luma_direct(name("mtf_luma_direct"));
                luma_direct(x, y, c) = record_neutral(pre_mtf, x, y);
                Func luma_view = b.store(luma_direct, Store::MtfLumaDirect, 1);
                Func blurred = b.gaussian(
                    luma_view, p.mtf_luma_sigma_, p.mtf_luma_sigma_, p.mtf_luma_sigma_,
                    p.mtf_luma_radius_, p.width_, p.height_, name("mtf_luma"), 1);
                luma_blurred = blurred(x, y, 0);
            }
            Func separated(name("mtf_separated"));
            separated(x, y, c) = mtf_luma_mix(
                configuration, mixed_view(x, y, c), record_neutral(mixed_view, x, y),
                luma_blurred);
            Func separated_view = b.store(separated, Store::MtfSeparated, 3);
            combined = select_stage(on_mtf_luma, separated_view, per_layer, x, y, c,
                                    name("mtf_luma_selected"));
        }
        Func selected = select_stage(on_mtf, combined, pre_mtf, x, y, c,
                                     name("mtf_selected"));
        light = b.store(selected, Store::MtfSelected, 3);
    }

    if (in.light_out) {
        return {light, Func(), Func()};
    }

    if (use_halation) {
        Func light_view = materialised ? light : store_light(light, Store::Light, 3);
        materialised = true;
        std::array<Expr, 3> scattered = b.scattered(
            light_view,
            {p.halation_stride_0_, p.halation_stride_1_, p.halation_stride_2_},
            {p.halation_strided_radius_0_, p.halation_strided_radius_1_,
             p.halation_strided_radius_2_},
            p.width_, p.height_, p.origin_x_, p.origin_y_, x, y, c,
            FOTUFILM_CONFIG_HALATION_RING_RADIUS, use_annular, name("halation_"), 3);
        Func returned(name("halation_returned"));
        returned(x, y, c) = halation_returned(
            configuration, c, light_view(x, y, c), scattered[0], scattered[1], scattered[2]);
        Func returned_view = b.store(returned, Store::HalationReturned, 3);
        Func halated(name("halated"));
        halated(x, y, c) = halation_mix(
            configuration, c, light_view(x, y, c),
            [&](int source) { return returned_view(x, y, source); });
        light = select_stage(on_halation, halated, light_view, x, y, c,
                             name("halation_selected"));
    }

    Func log_exposure(name("log_exposure"));
    log_exposure(x, y, c) = fs_log10(Halide::max(light(x, y, c), 1.0e-6f), approximate);

    Func effective_log = log_exposure;
    Expr adjacency_residual = 0.0f;
    Func donor_released(name("donor_released"));
    Func donor_pointwise = donor_released;
    if (use_couplers || use_adjacency || use_donor) {
        Func log_view = store_light(log_exposure, Store::LogExposure, 3);
        Func activation(name("activation"));
        activation(x, y, c) = film_activation(
            configuration, c, film_curve(c, log_view(x, y, c)));
        Func activation_view = b.store(activation, Store::Activation, 3);
        Func released(name("released"));
        released(x, y, c) = coupler_release(configuration, c, activation_view(x, y, c));
        Func released_view = use_couplers ? b.store(released, Store::Released, 3) : released;
        Func coupler_diffused = released_view;
        Func fringe_diffused = released_view;
        Func adjacency_diffused = activation_view;
        const bool fringe_compiled = use_coupler_diffusion && use_couplers && !b.windowed();
        if (use_coupler_diffusion && use_couplers) {
            Func diffused = b.gaussian_decimated(
                released_view, p.coupler_sigma_, p.coupler_radius_, p.origin_x_, p.origin_y_,
                p.width_, p.height_, name("coupler_diffused"), 3);
            Func selected(name("coupler_diffused_selected"));
            selected(x, y, c) = gated(on_coupler_diffusion, diffused(x, y, c),
                                      gated(on_couplers, released_view(x, y, c), 0.0f));
            coupler_diffused = selected;
            if (fringe_compiled) {
                Func fringe = b.gaussian_decimated(
                    released_view, p.fringe_sigma_, p.fringe_radius_, p.origin_x_,
                    p.origin_y_, p.width_, p.height_, name("fringe_diffused"), 3);
                Func fringe_selected(name("fringe_diffused_selected"));
                fringe_selected(x, y, c) = gated(on_coupler_diffusion, fringe(x, y, c),
                                                 gated(on_couplers, released_view(x, y, c), 0.0f));
                fringe_diffused = fringe_selected;
            }
        }
        if (use_adjacency) {
            adjacency_diffused = b.gaussian_decimated(
                activation_view, p.adjacency_sigma_, p.adjacency_radius_, p.origin_x_,
                p.origin_y_, p.width_, p.height_, name("adjacency_diffused"), 3);
            Func secondary = adjacency_diffused;
            if (!b.windowed()) {
                secondary = b.gaussian_decimated(
                    activation_view, p.adjacency_secondary_sigma_,
                    p.adjacency_secondary_radius_, p.origin_x_, p.origin_y_, p.width_,
                    p.height_, name("adjacency_secondary"), 3);
            }
            adjacency_residual = gated(
                on_adjacency,
                activation_view(x, y, c) - adjacency_transport(
                    configuration, gated(on_adjacency, adjacency_diffused(x, y, c), 0.0f),
                    gated(on_adjacency, secondary(x, y, c), 0.0f)),
                0.0f);
        }
        Func donor_diffused;
        if (use_donor) {
            Func donor_curve;
            if (b.tabulated_curves()) {
                donor_curve = curve_table(configuration, FOTUFILM_CONFIG_DONOR_CURVE, 6, 1,
                                          name("donor_curve"), b.device(), approximate);
            }
            Func donor_log(name("donor_log"));
            donor_log(x, y, c) = fs_log10(
                Halide::max(donor_exposure(x, y, c), 1.0e-6f), approximate);
            Func donor_log_view = b.store(donor_log, Store::DonorLog, 1);
            Func donor_activation(name("donor_activation"));
            donor_activation(x, y, c) =
                (b.donor_curve(configuration, donor_curve, donor_log_view(x, y, c))
                 - configuration(FOTUFILM_CONFIG_DONOR_CURVE))
                / Halide::max(curve_range(configuration, FOTUFILM_CONFIG_DONOR_CURVE),
                              1.0e-6f);
            Func donor_view = b.store(donor_activation, Store::DonorActivation, 1);
            donor_released(x, y, c) = donor_release(configuration, donor_view(x, y, c));
            Func donor_released_view = b.store(donor_released, Store::DonorReleased, 1);
            donor_pointwise = donor_released_view;
            donor_diffused = donor_released_view;
            if (use_coupler_diffusion) {
                Func diffused = b.gaussian_decimated(
                    donor_released_view, p.coupler_sigma_, p.coupler_radius_, p.origin_x_,
                    p.origin_y_, p.width_, p.height_, name("donor_diffused"), 1);
                Func selected(name("donor_diffused_selected"));
                selected(x, y, c) = gated(on_donor && on_coupler_diffusion, diffused(x, y, c),
                                          gated(on_donor, donor_released_view(x, y, c), 0.0f));
                donor_diffused = selected;
            }
        }
        Func shifted(name("inhibited"));
        Expr inhibited = log_view(x, y, c);
        if (use_couplers || use_donor) {
            Expr inhibition = 0.0f;
            if (use_couplers) {
                Expr coupler_term = coupler_inhibition(
                    configuration, c, coupler_diffused(x, y, 0), coupler_diffused(x, y, 1),
                    coupler_diffused(x, y, 2));
                if (fringe_compiled) {
                    Expr fringed = fringe_inhibition(
                        configuration, c, coupler_term, p.fringe_radius_,
                        fringe_diffused(x, y, 0) - coupler_diffused(x, y, 0),
                        fringe_diffused(x, y, 1) - coupler_diffused(x, y, 1),
                        fringe_diffused(x, y, 2) - coupler_diffused(x, y, 2));
                    coupler_term = gated(on_coupler_diffusion, fringed, coupler_term);
                }
                inhibition = gated(on_couplers, coupler_term, 0.0f);
            }
            if (use_donor) {
                inhibition = inhibition + gated(
                    on_donor, donor_inhibition(configuration, c, donor_diffused(x, y, 0)),
                    0.0f);
            }
            Func inhibition_field(name("inhibition"));
            inhibition_field(x, y, c) = inhibition;
            Func inhibition_view = b.store(inhibition_field, Store::Inhibition, 3);
            inhibited = inhibited_log_exposure(configuration, c, log_view(x, y, c),
                                               inhibition_view(x, y, c));
        }
        Expr shift = 0.0f;
        if (use_adjacency) shift = adjacency_shift(configuration, adjacency_residual);
        shifted(x, y, c) = inhibited - shift;
        effective_log = select_stage(on_couplers || on_adjacency || on_donor, shifted,
                                     log_view, x, y, c, name("inhibited_selected"));
    }

    Func density(name("density"));
    auto developed_from = [&](Func log_source, bool spatial) {
        Expr formed = film_curve(c, log_source(x, y, c));
        if (use_adjacency && spatial) {
            formed = gated(
                on_adjacency, adjacency_density(configuration, c, formed, adjacency_residual),
                formed);
        }
        return developed_density(configuration, c, formed);
    };
    if (density_in) {
        density(x, y, c) = in.density_source(c);
    } else {
        density(x, y, c) = developed_from(effective_log, true);
    }

    Func flat_density(name("flat_density"));
    if (in.texture) {
        Func flat_log(name("flat_log_exposure"));
        flat_log(x, y, c) = fs_log10(Halide::max(flat_light(x, y, c), 1.0e-6f), approximate);
        Func flat_effective = flat_log;
        if (use_couplers || use_donor) {
            Func flat_log_view = b.store(flat_log, Store::FlatLog, 3);
            Func flat_shifted(name("flat_inhibited"));
            Expr inhibition = 0.0f;
            if (use_couplers) {
                Func flat_activation(name("flat_activation"));
                flat_activation(x, y, c) = film_activation(
                    configuration, c, film_curve(c, flat_log_view(x, y, c)));
                Func flat_activation_view = b.store(flat_activation, Store::FlatActivation, 3);
                Func flat_released(name("flat_released"));
                flat_released(x, y, c) = coupler_release(
                    configuration, c, flat_activation_view(x, y, c));
                Func flat_released_view = b.store(flat_released, Store::FlatReleased, 3);
                inhibition = gated(
                    on_couplers,
                    coupler_inhibition(configuration, c, flat_released_view(x, y, 0),
                                       flat_released_view(x, y, 1),
                                       flat_released_view(x, y, 2)),
                    0.0f);
            }
            if (use_donor) {
                inhibition = inhibition + gated(
                    on_donor,
                    donor_inhibition(configuration, c,
                                     gated(on_donor, donor_pointwise(x, y, 0), 0.0f)),
                    0.0f);
            }
            flat_shifted(x, y, c) = inhibited_log_exposure(
                configuration, c, flat_log_view(x, y, c), inhibition);
            flat_effective = select_stage(on_couplers || on_donor, flat_shifted, flat_log_view,
                                          x, y, c, name("flat_inhibited_selected"));
        }
        flat_density(x, y, c) = developed_from(flat_effective, false);
        flat_density = b.store(flat_density, Store::FlatDensity, 3);
    }

    Func developed = density;
    if (use_grain) {
        // The film grain model reads the developed density at every pixel of a whole frame, so
        // where it is compiled in the density is kept whole for every grain model alike.
        Func density_view = b.store(density, in.film_tiles ? Store::FilmDensity : Store::Density, 3);
        GrainFields fields = b.grain_fields(configuration, p, in.monochrome, use_mottle,
                                            x, y, c, p.width_, p.height_, in.prefix, suffix);
        Expr layer = Halide::min(c, fields.channels - 1);
        DensityPosition position = density_position(configuration, c, density_view(x, y, c));
        Expr modulation = grain_density_modulation(configuration, c, position.net);
        Expr mottle;
        if (use_mottle) {
            mottle = gated(on_mottle, fields.mottle(x, y, layer), 0.0f);
        }
        Expr clump = clump_grain(configuration, c, modulation, fields.grain(x, y, layer), mottle);
        Func grained(name("grained"));
        grained(x, y, c) = selected_developed_density(density_view(x, y, c), clump);
        developed = grained;
        if (in.film_tiles) {
            // Film grain (mode 1) replaces the other models' grain outright: it is the film's
            // own fluctuation about the curve, sampled from the host-rendered tiles.
            Expr film_on = on_grain && in.film_on;
            Expr record = Halide::select(in.monochrome != 0, 1, c);
            Func film(name("film_grain"));
            film(x, y, c) = film_tile_grain(configuration, *in.film_tiles, density_view(x, y, c),
                                            x + p.origin_x_, y + p.origin_y_, record,
                                            Expr(p.seed_), film_on);
            Func film_view = b.store(film, Store::FilmGrain, 3, film_on);
            // Colour grain: each record's grain mixed toward the three records' mean, keeping
            // their total variance — independent records at 1, one shared grain at 0.
            Expr colour = Halide::clamp(
                configuration(FOTUFILM_CONFIG_FILM_TILE + kFilmTileColour), 0.0f, 1.0f);
            Expr shared = Halide::sqrt(3.0f - 2.0f * colour * colour) - colour;
            Expr mean = (film_view(x, y, 0) + film_view(x, y, 1) + film_view(x, y, 2)) / 3.0f;
            Expr mixed = Halide::select(in.monochrome != 0, film_view(x, y, c),
                                        colour * film_view(x, y, c) + shared * mean);
            Func filmed(name("filmed"));
            filmed(x, y, c) = gated(film_on, density_view(x, y, c) + mixed, developed(x, y, c));
            developed = filmed;
        }
        developed = select_stage(on_grain, developed, density_view, x, y, c,
                                 name("grain_selected"));
    }

    // The developed film is printed through the enlarger's carrier, which holds the light off
    // the paper beyond its opening; its edge then spreads with the enlarger's own.
    {
        Expr carried = carrier_density(configuration, x + p.origin_x_, y + p.origin_y_);
        Func in_carrier(name("in_carrier"));
        in_carrier(x, y, c) = developed(x, y, c) + carried;
        developed = in_carrier;
        if (in.texture) {
            Func flat_in_carrier(name("flat_in_carrier"));
            flat_in_carrier(x, y, c) = flat_density(x, y, c) + carried;
            flat_density = flat_in_carrier;
        }
    }

    if (use_print_mtf) {
        Func source = b.store(developed, Store::PrintMtfInput, 3);
        Func transmittance(name("print_mtf_transmittance"));
        transmittance(x, y, c) = transmittance_of(source(x, y, c), approximate);
        Func transmittance_view = b.store(transmittance, Store::Transmittance, 3);
        Expr sigma = configuration(FOTUFILM_CONFIG_PRINT_MTF_SIGMA);
        Func spread = b.gaussian(transmittance_view, sigma, sigma, sigma, p.print_mtf_radius_,
                                 p.width_, p.height_, name("print_mtf"), 3);
        Func printed(name("printed"));
        printed(x, y, c) = density_of(
            print_mtf_read(configuration, transmittance_view(x, y, c), spread(x, y, c)),
            approximate);
        Func printed_view = b.store(printed, Store::Printed, 3);
        developed = select_stage(on_print_mtf, printed_view, source, x, y, c,
                                 name("print_mtf_selected"));
        if (in.texture) {
            Func flat_transmittance(name("flat_print_mtf_transmittance"));
            flat_transmittance(x, y, c) = transmittance_of(flat_density(x, y, c), approximate);
            Func flat_stored = b.store(flat_transmittance, Store::FlatTransmittance, 3);
            Func flat_printed(name("flat_printed"));
            flat_printed(x, y, c) = density_of(flat_stored(x, y, c), approximate);
            Func flat_printed_view = b.store(flat_printed, Store::FlatPrinted, 3);
            flat_density = select_stage(on_print_mtf, flat_printed_view, flat_density,
                                        x, y, c, name("flat_print_mtf_selected"));
        }
    }

    return {light, developed, flat_density};
}

struct PrintInputs {
    Halide::ImageParam &configuration;
    Expr reversal;
    bool monochrome;
    /// Whether the print takes FOTUFILM_CONFIG_PRINT_FINISH before the grade: a scanned
    /// negative's light controls. The fused pipeline compiles it into its density-in variants
    /// alone; the standalone CPU print always does, and neutral slots leave its print untouched.
    bool finish;
    std::string prefix;
    std::string suffix;
};

inline Func build_print(Backend &b, const PrintInputs &in, Func developed, Var x, Var y, Var c) {
    Halide::ImageParam &configuration = in.configuration;
    auto name = [&](const char *stage) { return in.prefix + stage + in.suffix; };
    const bool approximate = b.approximate();

    Func activation(name("print_activation"));
    activation(x, y, c) = film_activation(configuration, c, developed(x, y, c));
    Func activation_view = b.store(activation, Store::PrintActivation, 3);
    auto relative = [&](Expr channel) {
        return b.film_lut_sample(activation_view(x, y, 0), activation_view(x, y, 1),
                                 activation_view(x, y, 2), channel);
    };

    Func paper_curve = paper_curve_table(configuration, name("print_paper_curve"), b.device(),
                                         approximate);
    Func activated(name("print_paper_activation"));
    activated(x, y, c) = paper_activation(configuration, paper_curve, c, relative(c), approximate);
    Func activated_view = b.store(activated, Store::PaperActivation, 3);

    Func display(name("print_display"));
    Expr through_paper = b.paper_lut_sample(activated_view(x, y, 0), activated_view(x, y, 1),
                                            activated_view(x, y, 2), c);
    display(x, y, c) = Halide::select(in.reversal != 0, relative(c), through_paper);
    // Both read every channel of the display, so it is held once rather than recomputed per
    // channel through the paper's cube.
    Func display_view = in.monochrome || in.finish ? b.store(display, Store::Display, 3) : display;
    if (in.finish) {
        Func finished(name("print_finished"));
        finished(x, y, c) = print_finish(configuration, c, display_view(x, y, 0),
                                         display_view(x, y, 1), display_view(x, y, 2),
                                         approximate);
        display_view = finished;
    }

    Func printed(name("print_graded"));
    Expr composed = in.monochrome
        ? (display_view(x, y, 0) + display_view(x, y, 1) + display_view(x, y, 2)) / 3.0f
        : display_view(x, y, c);
    printed(x, y, c) = color_grade(configuration, c, composed);
    return printed;
}

}
}

#endif
