#ifndef FOTUFILM_HALIDE_H
#define FOTUFILM_HALIDE_H

#include <math.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Per-instance execution contexts for the AOT plugin runtime.
void *fotufilm_halide_metal_context_create(void);
void fotufilm_halide_metal_context_destroy(void *context);
void *fotufilm_halide_metal_context_bind(void *context);
void fotufilm_halide_metal_context_restore(void *context);

#include "FotufilmConfigLayout.h"

/// Decode-kernel parameters: row-major scene-space matrix, transfer, and premultiplication flag.
/// Decode transfers are defined independently by `fotufilm::inputTransformFor` and are not the
/// algebraic inverses of output coefficients.
///
///   0  identity — the host's space is already linear.
///   1  power law, sign-preserving: |v| <= c4 ? |v| * c0 : pow(c1 * |v| + c3, c2),
///      carried back across zero by the sign. Rec.709 gamma 2.4 and sRGB.
///   2  exponential, signed: v <= c4 ? (v - c3) * c0 : exp(v * c1 - c2) - c5.
///      DaVinci Intermediate and ACEScct.
enum {
    FOTUFILM_DECODE_MATRIX = 0,
    FOTUFILM_DECODE_TRANSFER = FOTUFILM_DECODE_MATRIX + 9,
    FOTUFILM_DECODE_COEFFICIENTS,
    FOTUFILM_DECODE_PREMULTIPLIED = FOTUFILM_DECODE_COEFFICIENTS + 6,
    FOTUFILM_DECODE_PARAMETER_COUNT,
};

/// Marks the "engine unavailable" fallbacks that FotufilmHalide.cpp and
/// FotufilmHalideMetal.cpp define when they are compiled without Halide.
#define FOTUFILM_FALLBACK __attribute__((weak))

/// Feature mask selecting which physical stages a pipeline variant includes.
enum {
    FOTUFILM_FRAME_FLARE = 1 << 0,
    FOTUFILM_FRAME_MTF = 1 << 1,
    FOTUFILM_FRAME_HALATION = 1 << 2,
    FOTUFILM_FRAME_COUPLERS = 1 << 3,
    FOTUFILM_FRAME_ADJACENCY = 1 << 4,
    FOTUFILM_FRAME_GRAIN = 1 << 5,
    FOTUFILM_FRAME_REVERSAL = 1 << 6,
    FOTUFILM_FRAME_MONOCHROME = 1 << 7,
    /// Selects the scene-referred schedule: linear float in and out, with no sRGB transfer, no
    /// clamp to display white, and no 8-bit quantization.
    FOTUFILM_FRAME_FLOAT_IO = 1 << 8,
    /// Compiles the extended emulsion MTF: its optional second positive scale and the luminance
    /// arm feeding the zero-DC correction at FOTUFILM_CONFIG_MTF_LUMA_SHARE. The historical name
    /// is retained because it is part of the generated AOT variant ABI.
    FOTUFILM_FRAME_MTF_LUMA = 1 << 9,
    /// Compiles the DIR couplers' spatial diffusion.
    FOTUFILM_FRAME_COUPLER_DIFFUSION = 1 << 10,
    /// Selects the realtime schedule rather than the reference one: byte-input variants may use
    /// half-float intermediates; float-input variants retain full storage precision while using
    /// tabulated H&D curves, decimated spatial pyramids, and the table-driven grain draw.
    FOTUFILM_FRAME_REALTIME = 1 << 11,
    /// Exact transcendentals instead of the GPU schedules' fast_* polynomials.
    FOTUFILM_FRAME_EXACT_MATH = 1 << 12,
    /// The hybrid fast develop's two halves.
    FOTUFILM_FRAME_DENSITY_OUT = 1 << 13,
    FOTUFILM_FRAME_DENSITY_IN = 1 << 14,
    /// Compiles the grain-size mixture's second clump field: a coarse crystal
    /// population blurred at its own correlation length and laid under the
    /// sharp one. The Android CPU generator includes it; other AOT backends
    /// must include it in their own compiled variants before accepting the bit.
    FOTUFILM_FRAME_GRAIN_MOTTLE = 1 << 16,
    /// Compiles the enlarger and paper MTF: one blur in transmittance at the end of develop,
    /// which is where the negative stops being the image and starts being the thing an enlarger
    /// projects. Off when the negative itself is what is being viewed, since then nothing
    /// images it. The Android CPU generator includes it for combined rendering;
    /// standalone negative development clears the bit before invoking the kernel.
    FOTUFILM_FRAME_PRINT_MTF = 1 << 17,
    /// Measures global veiling glare on-device: the frame averages its own first stage rather than
    /// reading FOTUFILM_CONFIG_FLARE_MEAN, so only a whole-frame caller may set it. Chosen at run
    /// time within a class; a row-window graph cannot see the frame and never serves it.
    /// The float32 GPU reduction is not bit-identical to the host's ordered double reduction.
    FOTUFILM_FRAME_FLARE_MEASURE = 1 << 18,
    /// Applies the configured output matrix, transfer, and premultiplication in-kernel.
    /// GPU transcendentals may differ from host libm, so callers must use one encode path per
    /// delivery. Hosts that resample must do so in linear light and leave this disabled.
    FOTUFILM_FRAME_ENCODE_OUT = 1 << 19,
    /// Stops the scene-referred pipeline after veiling glare and the emulsion MTF and hands that
    /// light back as interleaved float RGBA — the exact numbers the halation pyramid would read,
    /// in the same full-precision storage the schedule keeps them in. The first half of the
    /// two-pass striped render: a strip of light needs only the MTF's own apron, where a strip
    /// of finished frame needs halation's whole reach.
    FOTUFILM_FRAME_LIGHT_OUT = 1 << 20,
    /// Takes the halation pyramid's three blurred grids as an input — packed behind the
    /// configuration, whole-frame, built once by `fotufilm_halide_metal_halation_fields` from the
    /// light a LIGHT_OUT pass wrote — instead of building them from the strip. The strip then
    /// needs no halation apron at all, and the sampled values are the very numbers the staged
    /// path's own pyramid store holds, so the delivered pixels do not move.
    FOTUFILM_FRAME_FIELDS_IN = 1 << 21,
    /// Realtime output kernels compile only the transfer arm the host needs. Reference kernels
    /// omit these bits and retain the coefficient-driven runtime selection used for parity work.
    FOTUFILM_FRAME_OUTPUT_LINEAR = 1 << 22,
    FOTUFILM_FRAME_OUTPUT_POWER = 1 << 23,
    FOTUFILM_FRAME_OUTPUT_LOG = 1 << 24,
    /// Returns source light multiplied by the transmittance difference between developments with
    /// and without selected spatial stages. Pointwise operations cancel; uniform fields and empty
    /// selections are exact no-ops.
    FOTUFILM_FRAME_TEXTURE = 1 << 25,
    /// Enables a three-scale lens-diffusion halo in scene light before emulsion exposure.
    /// Direct share 1 with zero kernel weights is identity.
    FOTUFILM_FRAME_DIFFUSION = 1 << 26,
    /// Enables a fourth capture layer that releases inhibitor but forms no transported dye.
    /// The host warp already includes its neutral-axis counterweight, so donor stocks require a
    /// matching compiled variant; dropping this bit would retain compensation without release.
    FOTUFILM_FRAME_DONOR_LAYER = 1 << 27,
    /// Legacy annular-basis variant. Current physical profiles use centered continuous fields.
    FOTUFILM_FRAME_HALATION_ANNULAR = 1 << 28,
    /// JIT-only continuation from nonnegative photographic record exposure. Never RGB or density.
    /// AOT callers must not mask this bit and then run an RGB-input variant.
    FOTUFILM_FRAME_RECORD_EXPOSURE_IN = 1 << 30,
    /// Develops with no film in the gate: the creative controls — white balance, the
    /// exposure-keyed tone masks, saturation and vibrance — then straight into the print's
    /// delivery basis and the grade. No spectral recovery, no characteristic curve, no couplers,
    /// no grain, no paper. Everything the emulsion would have done falls out of the pipeline,
    /// so a variant carrying this bit should carry no stage bits either.
    ///
    /// It is a variant rather than a configuration because the middle of the pipeline is
    /// replaced, not switched off: there is no setting of the film and paper cubes that turns
    /// the spectral path into a matrix.
    FOTUFILM_FRAME_NO_FILM = 1 << 29,
};

#include "FotufilmAotVariants.h"

/// Sigma of the Gaussian that three iterated box blurs of `radius` approximate.
static inline float fotufilm_halation_box_sigma(int32_t radius) {
    const float width = (float)(2 * radius + 1);
    return sqrtf((width * width - 1.0f) * 0.25f);
}

/// Decimation stride for one halation scale: the largest power of two that keeps the decimated
/// sigma comfortably above the grid (>= 2.5 samples), so the down/up resampling adds spread that
/// stays negligible against the blur itself.
static inline int32_t fotufilm_halation_stride(int32_t radius) {
    const float sigma = fotufilm_halation_box_sigma(radius);
    int32_t stride = 1;
    while (stride < 8 && (float)(2 * stride) * 2.5f <= sigma) stride *= 2;
    return stride;
}

/// How far a decimated Gaussian of `sigma` (`radius` taps at full resolution) really reads: the
/// GPU schedules blur the coupler, fringe and adjacency fields on a grid decimated by a power of
/// two chosen from the sigma, and the blur's grid radius, the box cell it lands in and the
/// bilinear neighbour it reads back through all stand outside the nominal radius. A strip that
/// prices its apron by the nominal radius alone is a few rows short at every seam. Mirrors
/// gaussian_grid_reach in FotufilmHalideGeometry.h.
static inline int32_t fotufilm_gaussian_grid_reach(float sigma, int32_t radius) {
    if (radius <= 0) return 0;
    const int32_t stride = sigma >= 8.0f ? 8 : sigma >= 4.0f ? 4 : sigma >= 2.0f ? 2 : 1;
    int32_t grid_radius = (radius + stride - 1) / stride;
    if (grid_radius < 1) grid_radius = 1;
    return stride == 1 ? radius + 1 : stride * (grid_radius + 2);
}

/// Diffusion decimation with a stride ceiling of 64. Keeping decimated sigma near 2.5 samples
/// prevents large mist radii from creating oversized aprons; halation retains its ceiling of 8.
static inline int32_t fotufilm_diffusion_stride(int32_t radius) {
    const float sigma = fotufilm_halation_box_sigma(radius);
    int32_t stride = 1;
    while (stride < 64 && (float)(2 * stride) * 2.5f <= sigma) stride *= 2;
    return stride;
}

/// Box radius on the decimated grid whose three-pass chain, together with the spread the box
/// downsample and bilinear upsample add, matches the full-resolution chain's Gaussian sigma.
static inline int32_t fotufilm_halation_strided_radius(int32_t radius,
                                                      int32_t stride) {
    if (stride <= 1) return radius;
    const float sigma = fotufilm_halation_box_sigma(radius);
    float variance = sigma * sigma / (float)(stride * stride) - 0.25f;
    if (variance < 0.25f) variance = 0.25f;
    const float width = sqrtf(4.0f * variance + 1.0f);
    const int32_t scaled = (int32_t)((width - 1.0f) * 0.5f + 0.5f);
    return scaled < 1 ? 1 : scaled;
}

/// The byte frames' primaries, packed for the kernels' scalar: FOTUFILM_CONFIG_BYTE_BASIS names
/// the input's in its first slot and the delivery's in its second, 1 for sRGB; bit 0 and bit 1
/// here. Every road derives the scalar from the configuration this way.
static inline int32_t fotufilm_byte_basis(const float *configuration) {
    return (configuration[FOTUFILM_CONFIG_BYTE_BASIS] != 0.0f ? 1 : 0)
        | (configuration[FOTUFILM_CONFIG_BYTE_BASIS + 1] != 0.0f ? 2 : 0);
}

/// IEEE half from a float, round-to-nearest-even.
static inline uint16_t fotufilm_float_to_half(float value) {
    union { float f; uint32_t u; } bits;
    bits.f = value;
    const uint32_t sign = (bits.u >> 16) & 0x8000u;
    const uint32_t magnitude = bits.u & 0x7fffffffu;
    if (magnitude >= 0x7f800000u) {
        return (uint16_t)(sign | 0x7c00u | (magnitude > 0x7f800000u ? 0x200u : 0u));
    }
    if (magnitude >= 0x477ff000u) return (uint16_t)(sign | 0x7c00u);
    if (magnitude < 0x38800000u) {
        if (magnitude < 0x33000000u) return (uint16_t)sign;
        const uint32_t shifted = (magnitude & 0x7fffffu) | 0x800000u;
        const int shift = 126 - (int)(magnitude >> 23);
        const uint32_t base = shifted >> shift;
        const uint32_t remainder = shifted & ((1u << shift) - 1u);
        const uint32_t halfway = 1u << (shift - 1);
        uint32_t rounded = base;
        if (remainder > halfway || (remainder == halfway && (base & 1))) ++rounded;
        return (uint16_t)(sign | rounded);
    }
    uint32_t half = ((magnitude >> 13) & 0x3ffu)
        | ((uint32_t)((int)(magnitude >> 23) - 112) << 10);
    const uint32_t remainder = magnitude & 0x1fffu;
    if (remainder > 0x1000u || (remainder == 0x1000u && (half & 1))) ++half;
    return (uint16_t)(sign | half);
}

/// Returns 1 when this target was compiled against Halide, otherwise 0.
int32_t fotufilm_halide_available(void);

/// Stages 1-7 of the film model: spectral exposure through the stock's LUT (HDR radiance above 1
/// preserved), veiling glare with the exact frame mean, per-layer emulsion diffusion,
/// base-reflection halation, DIR coupler inhibition + adjacency, H&D development (reversal stocks
/// complemented to their measured direct-positive densities), and calibrated clump grain.
int32_t fotufilm_halide_develop(
    const float *input_r, const float *input_g, const float *input_b,
    float *output_r, float *output_g, float *output_b,
    int32_t width, int32_t height,
    const float *configuration,
    const float *exposure_lut, int32_t lut_dimension,
    int32_t feature_mask, uint32_t seed);

/// Registers the film grain model's tiles under `id`: `count` floats, the running-sum tables of
/// FOTUFILM_FILM_TILE_LEVELS levels for each of three records, ((side + 1)², levels, 3) in that
/// order. A frame in grain mode 1 samples the tiles its FILM_TILE block names; a null or empty
/// `tiles` forgets them. Returns 0, or -1 when the count is not the tiles' size.
int32_t fotufilm_halide_set_film_tiles(int32_t id, const float *tiles, int64_t count);

/// Stage 8: developed densities to display-linear RGB.
int32_t fotufilm_halide_print(
    const float *input_r, const float *input_g, const float *input_b,
    float *output_r, float *output_g, float *output_b,
    int32_t width, int32_t height,
    const float *configuration,
    const float *film_output_lut, const float *paper_output_lut,
    int32_t lut_dimension, int32_t feature_mask);

/// Both halves at once: scene-linear RGB in, display-linear RGB out.
int32_t fotufilm_halide_process(
    const float *input_r, const float *input_g, const float *input_b,
    float *output_r, float *output_g, float *output_b,
    int32_t width, int32_t height,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    int32_t feature_mask, uint32_t seed);

/// One horizontal strip of a frame, developed and printed.
int32_t fotufilm_halide_process_strip(
    const float *input_r, const float *input_g, const float *input_b,
    float *output_r, float *output_g, float *output_b,
    int32_t width, int32_t height,
    int32_t output_width, int32_t output_height,
    int32_t origin_x, int32_t origin_y,
    int32_t interior_top, int32_t interior_height,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    int32_t feature_mask, uint32_t seed);

/// A contiguous tile including its spatial apron. Only the named interior rectangle is
/// copied to the frame-sized output planes, at origin + interior coordinates.
int32_t fotufilm_halide_process_tile(
    const float *input_r, const float *input_g, const float *input_b,
    float *output_r, float *output_g, float *output_b,
    int32_t width, int32_t height, int32_t output_width, int32_t output_height,
    int32_t origin_x, int32_t origin_y, int32_t interior_left, int32_t interior_top,
    int32_t interior_width, int32_t interior_height,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    int32_t feature_mask, uint32_t seed);

/// As process_tile, with optional contiguous interleaved R/G/B/donor photographic record exposure
/// (width * height * 4 floats, finite and nonnegative). Added after diffusion and the camera gate,
/// before film optics; excluded from lens-glare measurement. Null is the ordinary scene-only path.
/// The field includes the tile's apron. Unsupported backends return an error rather than ignore it.
int32_t fotufilm_halide_process_tile_with_exposure(
    const float *input_r, const float *input_g, const float *input_b,
    float *output_r, float *output_g, float *output_b,
    int32_t width, int32_t height, int32_t output_width, int32_t output_height,
    int32_t origin_x, int32_t origin_y, int32_t interior_left, int32_t interior_top,
    int32_t interior_width, int32_t interior_height,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    int32_t feature_mask, uint32_t seed, const float *additional_record_exposure);

/// Develops the same apron-bearing tile as process_tile_with_exposure, but writes only
/// interior_width * interior_height samples per output plane, tightly packed from (0, 0).
/// frame_width/frame_height describe the virtual photograph, not destination allocation.
/// Absolute origin, spatial support, configuration, seed and global measurements retain
/// their full-frame meaning. The caller supplies sufficient apron and whole-frame metering.
/// Combined film development only, with scene-linear input and display-linear output.
/// Separate stages, no-film and encoded output are rejected. No output is written until
/// rendering succeeds. Unsupported backends return an error.
int32_t fotufilm_halide_process_region(
    const float *input_r, const float *input_g, const float *input_b,
    float *output_r, float *output_g, float *output_b,
    int32_t width, int32_t height, int32_t frame_width, int32_t frame_height,
    int32_t origin_x, int32_t origin_y, int32_t interior_left, int32_t interior_top,
    int32_t interior_width, int32_t interior_height,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    int32_t feature_mask, uint32_t seed, const float *additional_record_exposure);

/// Single-plane blur kernels used by the public Blur API.
int32_t fotufilm_halide_gaussian(
    const float *input, float *output, int32_t width, int32_t height,
    float sigma, int32_t radius);
int32_t fotufilm_halide_approximate_gaussian(
    const float *input, float *output, int32_t width, int32_t height,
    int32_t radius);

/// Returns 1 when a usable Halide CUDA target is present on this host — a Linux box with a
/// driver the pipeline can reach. The CUDA entry points run the same schedules as the Metal ones,
/// and come in two pairs: host buffers, and the device pointers below.
int32_t fotufilm_halide_cuda_available(void);

/// The device the CUDA entry points develop on: 1 for CUDA, 2 for Vulkan (FOTUFILM_GPU_DEVICE, or
/// on Linux's ahead-of-time build whichever GPU answers), 0 for none.
int32_t fotufilm_halide_gpu_device(void);

/// The CUDA counterpart of `fotufilm_halide_metal_prepare`.
int32_t fotufilm_halide_cuda_prepare(
    int32_t feature_mask,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id);

/// The CUDA counterpart of `fotufilm_halide_metal_process_srgb8`.
int32_t fotufilm_halide_cuda_process_srgb8(
    const uint8_t *input, uint8_t *output, int32_t width, int32_t height,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// The CUDA counterpart of `fotufilm_halide_metal_process_linear_float`.
int32_t fotufilm_halide_cuda_process_linear_float(
    const float *input, float *output, int32_t width, int32_t height,
    int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// Develops tightly packed RGBA from caller-owned CUdeviceptr buffers without device-host copies.
/// Both buffers must belong to the CUDA context current on the engine's first driver call; the
/// caller retains allocation ownership.
int32_t fotufilm_halide_cuda_process_device_srgb8(
    uint64_t input_device_pointer, uint64_t output_device_pointer,
    int32_t width, int32_t height,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// The float counterpart of `fotufilm_halide_cuda_process_device_srgb8`.
int32_t fotufilm_halide_cuda_process_device_linear_float(
    uint64_t input_device_pointer, uint64_t output_device_pointer,
    int32_t width, int32_t height, int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// Returns 1 when a usable Halide Metal target is present on this host.
int32_t fotufilm_halide_metal_available(void);

/// Whether a variant was generated that can develop `feature_mask`. Asked before setting a bit
/// the build may not carry — FOTUFILM_FRAME_FLARE_MEASURE is generated for the float variants
/// only, and a caller that cannot have it has to measure the glare itself.
int32_t fotufilm_halide_metal_variant_exists(int32_t feature_mask);

/// Prints Halide's per-stage profile of every frame run so far, on a build
/// whose kernels were generated with FOTUFILM_HALIDE_PROFILE set.
void fotufilm_halide_metal_report_profile(void);

/// Compiles the requested feature variant and uploads the stock's spectral
/// tables without processing a frame.
int32_t fotufilm_halide_metal_prepare(
    int32_t feature_mask,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id);

/// Fused RGBA8 frame processing: the transfer decode in the basis FOTUFILM_CONFIG_BYTE_BASIS names,
/// the spectral film model, and the encode back into the basis it names for the output.
int32_t fotufilm_halide_metal_process_srgb8(
    const uint8_t *input, uint8_t *output, int32_t width, int32_t height,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// Scene-referred processing: interleaved linear float RGBA in and out, with
/// values above 1.0 carried through the whole model.
int32_t fotufilm_halide_metal_process_linear_float(
    const float *input, float *output, int32_t width, int32_t height,
    int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// `process_linear_float` delivering only rows [out_y, out_y + out_rows) of the strip: `output`
/// holds `out_rows` tightly packed rows, and the apron rows above and below are computed only
/// through the stages a delivered pixel reads them from. Delivered pixels match the uncropped
/// call's exactly.
int32_t fotufilm_halide_metal_process_linear_float_rows(
    const float *input, float *output, int32_t width, int32_t height,
    int32_t out_y, int32_t out_rows,
    int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// `process_linear_float_rows` in both axes: `input` is a `width` x `height` tile of the frame
/// whose top-left pixel is (`origin_x`, `origin_y`), and `output` holds only the tile's window
/// [out_x, out_x + out_columns) x [out_y, out_y + out_rows), tightly packed; the apron around
/// the window is computed only through the stages a delivered pixel reads it from. Delivered
/// pixels match a whole-frame develop's exactly.
///
/// With `fields`, the halation samples those whole-frame grids (built by
/// `fotufilm_halide_metal_halation_fields`) instead of a pyramid over the tile, so the tile's
/// apron need not reach as far as halation does. `fields_id` names the blob so the tiles of
/// one frame upload it once; it must change when the blob does. A caller that lays the blob
/// out directly behind its configuration — `fields == configuration + COUNT` — has it read in
/// place rather than copied, and must then keep it where it is until
/// `fotufilm_halide_metal_release_fields`. Null fields develop with the pyramid over the
/// tile.
int32_t fotufilm_halide_metal_process_linear_float_tile(
    const float *input, float *output, int32_t width, int32_t height,
    int32_t out_x, int32_t out_columns, int32_t out_y, int32_t out_rows,
    int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *fields, int32_t fields_floats, uint64_t fields_id,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// The first pass of the two-pass still road: develops the strip only as far as veiling glare
/// and the emulsion MTF — a strip of light needs only that reach — and delivers the finest
/// halation grid of that light: the light box-averaged over the first scale's stride on the
/// frame's own cell lattice (the lattice a whole-frame develop decimates on), three floats a
/// cell, interleaved, `ceil(width / stride)` cells wide. `out_y` and `out_rows` are in cells of
/// the strip's own grid, whose first cell holds the strip rows from `origin_y - origin_y %
/// stride`; a caller that keeps its strips on cell boundaries delivers the frame's grid rows
/// whole. The caller's mask should carry the stages up to the light (flare, diffusion, MTF)
/// and the frame's identity bits; FLOAT_IO and LIGHT_OUT are implied.
int32_t fotufilm_halide_metal_process_light_grid(
    const float *input, float *grid_out, int32_t width, int32_t height,
    int32_t out_y, int32_t out_rows,
    int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// Floats a whole-frame halation fields blob occupies for this frame size and these halation
/// radii (FOTUFILM_CONFIG_HALATION_RADIUS values, 3 of them), header included. Negative on a
/// build that cannot serve the fields path.
int32_t fotufilm_halide_metal_halation_fields_floats(
    int32_t width, int32_t height, const int32_t *halation_radii);

/// Builds the halation pyramid's three blurred grids from the frame's finest grid — the cells
/// the light-grid passes delivered, `ceil(width / stride0)` x `ceil(height / stride0)` x 3,
/// interleaved — into `fields` (sized by the floats call above), with the same arithmetic a
/// staged develop runs internally.
int32_t fotufilm_halide_metal_halation_fields(
    const float *grid, int32_t width, int32_t height,
    const int32_t *halation_radii, float *fields, int32_t fields_floats);

/// Drops the halation grids the pipelines keep uploaded between the tiles of a frame.
void fotufilm_halide_metal_release_fields(void);

/// The kStillFast* bits this build's approximate-math float still schedule was generated with
/// (0 where the still path is the untouched reference), for hosts whose memory model follows
/// the schedule.
int32_t fotufilm_halide_metal_still_fast_bits(void);

/// Zero-copy variant for caller-owned MTLBuffers.
int32_t fotufilm_halide_metal_process_buffers(
    uint64_t input_mtl_buffer, uint64_t output_mtl_buffer,
    int32_t width, int32_t height, int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// The zero-copy form of `process_linear_float`, for a stream of high-bit- depth frames:
/// caller-owned MTLBuffers of interleaved linear float RGBA, width * height * 16 bytes each.
int32_t fotufilm_halide_metal_process_buffers_float(
    uint64_t input_mtl_buffer, uint64_t output_mtl_buffer,
    int32_t width, int32_t height, int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// The zero-copy form of `process_linear_float_tile`: the tile in a caller-owned MTLBuffer of
/// width * height * 16 bytes, the window delivered into one of out_columns * out_rows * 16.
int32_t fotufilm_halide_metal_process_buffers_float_tile(
    uint64_t input_mtl_buffer, uint64_t output_mtl_buffer,
    int32_t width, int32_t height,
    int32_t out_x, int32_t out_columns, int32_t out_y, int32_t out_rows,
    int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *fields, int32_t fields_floats, uint64_t fields_id,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// `process_light_grid` reading the strip from a caller-owned MTLBuffer; the grid cells still
/// land in host memory, there being few of them.
int32_t fotufilm_halide_metal_process_buffers_light_grid(
    uint64_t input_mtl_buffer, float *grid_out, int32_t width, int32_t height,
    int32_t out_y, int32_t out_rows,
    int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// The hybrid fast develop's two halves, both zero-copy over caller-owned MTLBuffers.
int32_t fotufilm_halide_metal_process_buffers_head(
    uint64_t input_mtl_buffer, uint64_t density_mtl_buffer,
    int32_t width, int32_t height, int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

int32_t fotufilm_halide_metal_process_buffers_tail(
    uint64_t density_mtl_buffer, uint64_t output_mtl_buffer,
    int32_t width, int32_t height,
    int32_t density_width, int32_t density_height,
    int32_t origin_x, int32_t origin_y,
    const float *configuration,
    const float *exposure_lut, const float *film_output_lut,
    const float *paper_output_lut, int32_t lut_dimension,
    uint64_t spectral_cache_id, int32_t feature_mask, uint32_t seed);

/// Per-row tone and glare measurements for linear-float RGBA bands. Tone writes `grid_width`
/// log2-luminance sums per row. Glare writes three layer-exposure sums per row after the tone grid
/// is solved. Input is either an MTLBuffer or host rows. Returns 0 or -1 on invalid input/device failure.
int32_t fotufilm_halide_metal_measure_tone_rows(
    uint64_t input_mtl_buffer, const float *input_rows, float *rows_out,
    int32_t grid_width, int32_t width, int32_t rows,
    const float *configuration);

int32_t fotufilm_halide_metal_measure_flare_rows(
    uint64_t input_mtl_buffer, const float *input_rows, float *rows_out,
    int32_t width, int32_t rows, int32_t origin_y,
    const float *configuration, const float *exposure_lut,
    const float *film_output_lut, const float *paper_output_lut,
    int32_t lut_dimension, uint64_t spectral_cache_id, int32_t feature_mask);

/// Decodes RGBA rows by repairing non-finite values, optionally un-premultiplying, applying the
/// transfer, and converting to scene space. Inputs and outputs may independently use MTLBuffers or
/// host rows. `report_out` stores the pre-repair RGB peak and repair flag per row. Returns 0 or -1.
int32_t fotufilm_halide_metal_decode_rows(
    uint64_t input_mtl_buffer, const float *input_rows,
    uint64_t output_mtl_buffer, float *output_rows, float *report_out,
    int32_t width, int32_t rows, const float *parameters);

/// Realtime spelling of `fotufilm_halide_metal_decode_rows`: identical coefficients, repair, and
/// reporting, with the video schedule's bounded transfer approximations.
int32_t fotufilm_halide_metal_decode_rows_realtime(
    uint64_t input_mtl_buffer, const float *input_rows,
    uint64_t output_mtl_buffer, float *output_rows, float *report_out,
    int32_t width, int32_t rows, const float *parameters);

#ifdef __cplusplus
}
#endif

#endif
