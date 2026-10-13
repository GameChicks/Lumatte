#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include <vector>
#include <atomic>
#include <exception>
#include <mutex>
#include <thread>

namespace lfm::mobile {
using Colour = std::array<float, 3>;
using Check = std::function<void()>;
// Workers only touch disjoint rows and shared immutable data. Check must be native/thread-safe.
// Join all workers before propagating failure, so no bitmap is unlocked/recycled prematurely.
template<class Row> inline void parallel_rows(uint32_t rows, uint64_t work_per_row,
                                             const Check& check, const Row& process) {
    const uint32_t workers = std::min({4u, rows, std::max(1u, std::thread::hardware_concurrency())});
    if (workers < 2 || uint64_t(rows) * work_per_row < 262144) {
        for (uint32_t y = 0; y < rows; ++y) { check(); process(y); }
        return;
    }
    std::atomic_uint32_t next{0};
    std::atomic_bool stopped{false};
    std::exception_ptr failure;
    std::mutex failure_lock;
    {
        struct JoinedThreads {
            std::vector<std::thread> values;
            ~JoinedThreads() { for (auto& thread : values) if (thread.joinable()) thread.join(); }
        } threads;
        threads.values.reserve(workers);
        try {
            for (uint32_t i = 0; i < workers; ++i) threads.values.emplace_back([&] {
                try {
                    while (!stopped.load(std::memory_order_relaxed)) {
                        const uint32_t first = next.fetch_add(4, std::memory_order_relaxed);
                        if (first >= rows) break;
                        for (uint32_t y = first; y < std::min(first + 4, rows); ++y) {
                            if (stopped.load(std::memory_order_relaxed)) break;
                            check(); process(y);
                        }
                    }
                } catch (...) {
                    { std::lock_guard lock(failure_lock); if (!failure) failure = std::current_exception(); }
                    stopped.store(true, std::memory_order_relaxed);
                }
            });
        } catch (...) {
            stopped.store(true, std::memory_order_relaxed);
            throw; // The guard joins the workers already created, including partial startup.
        }
    }
    if (failure) std::rethrow_exception(failure);
    check();
}
struct Image { uint8_t* pixels; uint32_t width, height, stride; };
struct Table {
    int size;
    Colour minimum, maximum;
    std::vector<float> values;
    Colour sample(Colour input) const {
        int lo[3], hi[3]; float mix[3];
        for (int c = 0; c < 3; ++c) {
            if (!std::isfinite(input[c])) throw std::runtime_error("Non-finite film stage input");
            const float p = std::clamp((input[c] - minimum[c]) / (maximum[c] - minimum[c]), 0.f, 1.f) * (size - 1);
            lo[c] = int(p); hi[c] = std::min(lo[c] + 1, size - 1); mix[c] = p - lo[c];
        }
        auto index = [&](int x, int y, int z) { return size_t(x + size * (y + size * z)) * 3; };
        const size_t a = index(lo[0], lo[1], lo[2]), b = index(hi[0], lo[1], lo[2]);
        const size_t c = index(lo[0], hi[1], lo[2]), d = index(hi[0], hi[1], lo[2]);
        const size_t e = index(lo[0], lo[1], hi[2]), f = index(hi[0], lo[1], hi[2]);
        const size_t g = index(lo[0], hi[1], hi[2]), h = index(hi[0], hi[1], hi[2]);
        Colour out{};
        for (int ch = 0; ch < 3; ++ch) {
            const float low = (values[a + ch] * (1 - mix[0]) + values[b + ch] * mix[0]) * (1 - mix[1]) +
                (values[c + ch] * (1 - mix[0]) + values[d + ch] * mix[0]) * mix[1];
            const float high = (values[e + ch] * (1 - mix[0]) + values[f + ch] * mix[0]) * (1 - mix[1]) +
                (values[g + ch] * (1 - mix[0]) + values[h + ch] * mix[0]) * mix[1];
            out[ch] = low * (1 - mix[2]) + high * mix[2];
        }
        return out;
    }
};
struct Model { Table response, development, scan; };
struct Effects {
    float diffusion, exposure_gain;
    bool halation, grain;
    float particle_area, format_mm;
    Colour particle_scale, floor, uniformity, density_max, halation_strength, halation_sigma_um;
};

inline float decode(float value) { return value <= .04045f ? value / 12.92f : std::pow((value + .055f) / 1.055f, 2.4f); }
inline float encode(float value) { return value <= .0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.f / 2.4f) - .055f; }
inline float luminance(const Colour& rgb) { return .2126f * rgb[0] + .7152f * rgb[1] + .0722f * rgb[2]; }
inline Colour read_linear(const uint8_t* pixel, const std::array<float, 256>& decode_lut) {
    return {decode_lut[pixel[0]], decode_lut[pixel[1]], decode_lut[pixel[2]]};
}

struct Field {
    uint32_t width = 0, height = 0;
    std::vector<float> values;
    Field() = default;
    Field(uint32_t w, uint32_t h) : width(w), height(h), values(size_t(w) * h * 3) {}
    Colour sample(uint32_t x, uint32_t y, const Image& image) const {
        if (values.empty()) return {};
        const float px = std::clamp((float(x) + .5f) * width / image.width - .5f, 0.f, float(width - 1));
        const float py = std::clamp((float(y) + .5f) * height / image.height - .5f, 0.f, float(height - 1));
        const uint32_t x0 = uint32_t(px), y0 = uint32_t(py), x1 = std::min(x0 + 1, width - 1), y1 = std::min(y0 + 1, height - 1);
        const float fx = px - x0, fy = py - y0;
        const size_t a = (size_t(y0) * width + x0) * 3, b = (size_t(y0) * width + x1) * 3;
        const size_t c = (size_t(y1) * width + x0) * 3, d = (size_t(y1) * width + x1) * 3;
        Colour output{};
        for (int ch = 0; ch < 3; ++ch)
            output[ch] = (values[a + ch] * (1 - fx) + values[b + ch] * fx) * (1 - fy) +
                (values[c + ch] * (1 - fx) + values[d + ch] * fx) * fy;
        return output;
    }
};

inline Field gaussian(const Field& source, Colour sigmas, const Check& check) {
    Field horizontal(source.width, source.height), output(source.width, source.height);
    std::array<std::vector<float>, 3> weights;
    int radius[3];
    for (int c = 0; c < 3; ++c) {
        const float sigma = std::clamp(sigmas[c], .1f, 24.f);
        radius[c] = int(std::ceil(sigma * 3));
        weights[c].resize(size_t(radius[c] * 2 + 1));
        double sum = 0;
        for (int i = -radius[c]; i <= radius[c]; ++i) {
            float& weight = weights[c][size_t(i + radius[c])];
            weight = std::exp(-.5f * i * i / (sigma * sigma)); sum += weight;
        }
        for (float& weight : weights[c]) weight = float(weight / sum);
    }
    parallel_rows(source.height, uint64_t(source.width) * 12, check, [&](uint32_t y) {
        for (uint32_t x = 0; x < source.width; ++x) for (int c = 0; c < 3; ++c) {
            double sum = 0;
            for (int offset = -radius[c]; offset <= radius[c]; ++offset) {
                const uint32_t sx = uint32_t(std::clamp(int(x) + offset, 0, int(source.width) - 1));
                sum += source.values[(size_t(y) * source.width + sx) * 3 + c] * weights[c][size_t(offset + radius[c])];
            }
            horizontal.values[(size_t(y) * source.width + x) * 3 + c] = float(sum);
        }
    });
    parallel_rows(source.height, uint64_t(source.width) * 12, check, [&](uint32_t y) {
        for (uint32_t x = 0; x < source.width; ++x) for (int c = 0; c < 3; ++c) {
            double sum = 0;
            for (int offset = -radius[c]; offset <= radius[c]; ++offset) {
                const uint32_t sy = uint32_t(std::clamp(int(y) + offset, 0, int(source.height) - 1));
                sum += horizontal.values[(size_t(sy) * source.width + x) * 3 + c] * weights[c][size_t(offset + radius[c])];
            }
            output.values[(size_t(y) * source.width + x) * 3 + c] = float(sum);
        }
    });
    return output;
}

/** Area reduction in linear/response light, never in encoded JPEG values. */
inline Field reduce_field(const Field& source, uint32_t w, uint32_t h, const Check& check) {
    Field output(w, h);
    parallel_rows(h, uint64_t(source.width) * source.height / h, check, [&](uint32_t y) {
        const uint32_t y0 = uint32_t(uint64_t(y) * source.height / h), y1 = uint32_t(uint64_t(y + 1) * source.height / h);
        for (uint32_t x = 0; x < w; ++x) {
            const uint32_t x0 = uint32_t(uint64_t(x) * source.width / w), x1 = uint32_t(uint64_t(x + 1) * source.width / w);
            double sum[3]{};
            for (uint32_t sy = y0; sy < y1; ++sy) for (uint32_t sx = x0; sx < x1; ++sx)
                for (int c = 0; c < 3; ++c) sum[c] += source.values[(size_t(sy) * source.width + sx) * 3 + c];
            const double count = double(x1 - x0) * (y1 - y0);
            for (int c = 0; c < 3; ++c) output.values[(size_t(y) * w + x) * 3 + c] = float(sum[c] / count);
        }
    });
    return output;
}

/** Wide scatter gets its own coarser grid; narrow components retain the analysis grid. */
inline Field scatter_gaussian(const Field& source, Colour sigmas, const Check& check) {
    for (float& sigma : sigmas) sigma = std::clamp(sigma, .1f, 24.f);
    uint32_t stride = 1;
    const float narrowest = *std::min_element(sigmas.begin(), sigmas.end());
    while (stride < 8 && narrowest / float(stride * 2) >= 1.f) stride *= 2;
    if (stride == 1 || std::min(source.width, source.height) < stride * 2) return gaussian(source, sigmas, check);
    const uint32_t w = std::max(1u, source.width / stride), h = std::max(1u, source.height / stride);
    const Field reduced = reduce_field(source, w, h, check);
    const float actual_stride = std::max(float(source.width) / w, float(source.height) / h);
    for (float& sigma : sigmas) {
        // Area averaging and linear reconstruction consume approximately 1/4 coarse-pixel variance.
        sigma = std::sqrt(std::max(.0625f, sigma * sigma / (actual_stride * actual_stride) - .25f));
    }
    const Field blurred = gaussian(reduced, sigmas, check);
    Field output(source.width, source.height);
    const Image dimensions{nullptr, source.width, source.height, 0};
    parallel_rows(source.height, uint64_t(source.width) * 4, check, [&](uint32_t y) {
        for (uint32_t x = 0; x < source.width; ++x) {
            const Colour rgb = blurred.sample(x, y, dimensions);
            for (int c = 0; c < 3; ++c) output.values[(size_t(y) * source.width + x) * 3 + c] = rgb[c];
        }
    });
    return output;
}

struct Spatial { Field lens, halation; float softness = 0; Colour halation_mix{}; };
inline Colour lens_colour(Colour linear, const Colour& glow, float softness) {
    for (int c = 0; c < 3; ++c) linear[c] = std::clamp(linear[c] * (1 - softness) + glow[c], 0.f, 1.f);
    return linear;
}
inline Colour film_response(const Model& model, Colour linear) {
    for (float& c : linear) c = std::clamp(encode(c), 0.f, 1.f);
    return model.response.sample(linear);
}

inline Spatial prepare_spatial(const Image& image, const Model& model, const Effects& effects,
                               const std::array<float, 256>& decode_lut, const Check& check) {
    Spatial spatial;
    const bool active_halation = effects.halation && std::any_of(effects.halation_strength.begin(),
        effects.halation_strength.end(), [](float value) { return value > 0; });
    if (effects.diffusion == 0 && !active_halation) return spatial;
    const double scale = std::min(1.0, 512.0 / std::max(image.width, image.height));
    const uint32_t w = std::min(512u, std::max(1u, uint32_t(std::ceil(image.width * scale))));
    const uint32_t h = std::min(512u, std::max(1u, uint32_t(std::ceil(image.height * scale))));
    // One full-image pass shared by lens diffusion and film return. No absolute highlight cutoff.
    // Linear area means preserve the energy of small lights before the compact response lookup.
    Field light(w, h);
    parallel_rows(h, uint64_t(image.width) * image.height / h, check, [&](uint32_t gy) {
        const uint32_t y0 = uint32_t(uint64_t(gy) * image.height / h), y1 = uint32_t(uint64_t(gy + 1) * image.height / h);
        for (uint32_t gx = 0; gx < w; ++gx) {
            const uint32_t x0 = uint32_t(uint64_t(gx) * image.width / w), x1 = uint32_t(uint64_t(gx + 1) * image.width / w);
            double sum[3]{};
            for (uint32_t y = y0; y < y1; ++y) {
                check();
                const uint8_t* row = image.pixels + size_t(y) * image.stride;
                for (uint32_t x = x0; x < x1; ++x) {
                    if ((x & 1023u) == 0) check();
                    const Colour rgb = read_linear(row + size_t(x) * 4, decode_lut);
                    for (int c = 0; c < 3; ++c) sum[c] += rgb[c];
                }
            }
            const double count = double(x1 - x0) * (y1 - y0);
            for (int c = 0; c < 3; ++c) light.values[(size_t(gy) * w + gx) * 3 + c] = float(sum[c] / count);
        }
    });
    if (effects.diffusion > 0) {
        // Illustrative black-mist response: sharp, scattered and absorbed shares are separate.
        // Grades change loading, not blur radius; these are not measurements of a branded filter.
        const float intercepted = effects.diffusion * .45f;
        const float scattered = intercepted * .55f;
        spatial.softness = intercepted;
        spatial.lens = Field(w, h);
        for (const auto [sigma, weight] : std::array<std::array<float, 2>, 3>{{{.75f, .20f}, {3.5f, .35f}, {10.f, .45f}}}) {
            const Field blurred = scatter_gaussian(light, {sigma, sigma, sigma}, check);
            for (size_t i = 0; i < blurred.values.size(); ++i) {
                if ((i & 16383u) == 0) check();
                spatial.lens.values[i] += scattered * weight * blurred.values[i];
            }
        }
    }
    if (!active_halation) return spatial;
    Field response(w, h);
    parallel_rows(h, uint64_t(w) * 12, check, [&](uint32_t y) {
        for (uint32_t x = 0; x < w; ++x) {
            const size_t offset = (size_t(y) * w + x) * 3;
            Colour rgb{light.values[offset], light.values[offset + 1], light.values[offset + 2]};
            if (effects.diffusion > 0) {
                const Colour glow{spatial.lens.values[offset], spatial.lens.values[offset + 1], spatial.lens.values[offset + 2]};
                rgb = lens_colour(rgb, glow, spatial.softness);
            }
            const Colour raw = film_response(model, rgb);
            for (int c = 0; c < 3; ++c) response.values[offset + c] = std::max(0.f, raw[c]);
        }
    });
    // Preserve the supplied stock's returned/direct ratios, including zero-return channels.
    // A single creative gain increases visibility without inventing blue/green return floors.
    Colour sigma{};
    for (int c = 0; c < 3; ++c) {
        const float returned = effects.halation_strength[c] * 2.f;
        spatial.halation_mix[c] = returned / (1.f + returned);
        sigma[c] = std::max(.35f, effects.halation_sigma_um[c] * std::max(w, h) / (effects.format_mm * 1000));
    }
    spatial.halation = Field(w, h);
    // Positive centred scales form a smooth return profile. No broad-minus-core shell or clipping.
    // This project-authored mixture is an approximation, not Fotufilm's fitted 800T profile.
    for (const auto [scale_factor, weight] : std::array<std::array<float, 2>, 3>{{{.65f, .25f}, {1.3f, .50f}, {2.6f, .25f}}}) {
        Colour scales{};
        for (int c = 0; c < 3; ++c) scales[c] = sigma[c] * scale_factor;
        const Field blurred = scatter_gaussian(response, scales, check);
        for (size_t i = 0; i < blurred.values.size(); ++i) {
            if ((i & 16383u) == 0) check();
            spatial.halation.values[i] += weight * spatial.halation_mix[i % 3] * blurred.values[i];
        }
    }
    return spatial;
}

inline uint32_t hash(uint32_t value) {
    value = (value ^ (value >> 16)) * 0x85ebca6bu;
    value = (value ^ (value >> 13)) * 0xc2b2ae35u;
    return value ^ (value >> 16);
}
inline float normal_at(int64_t x, int64_t y, uint32_t seed, uint32_t salt) {
    uint32_t base = uint32_t(x) * 73856093u ^ uint32_t(y) * 19349663u ^ seed ^ salt;
    float sum = 0;
    for (uint32_t i = 0; i < 3; ++i) sum += float(hash(base + i * 0x9e3779b9u) & 65535u) / 65535.f - .5f;
    return sum * 2.f; // Three uniforms, mean 0 and approximately unit variance; not exact Gaussian.
}
inline float grain_noise(double gx, double gy, uint32_t seed, uint32_t salt) {
    gx /= 1.4; gy /= 1.4;
    const auto x = int64_t(std::floor(gx)), y = int64_t(std::floor(gy));
    const float fx = float(gx - x), fy = float(gy - y);
    const float a = (1-fx)*(1-fy), b = fx*(1-fy), c = (1-fx)*fy, d = fx*fy;
    const float energy = std::sqrt(a*a + b*b + c*c + d*d);
    return (a*normal_at(x,y,seed,salt) + b*normal_at(x+1,y,seed,salt) +
            c*normal_at(x,y+1,seed,salt) + d*normal_at(x+1,y+1,seed,salt)) / energy;
}
inline void apply_grain(Colour& density, const Effects& effects, double gx, double gy,
                        float pixel_area, uint32_t seed) {
    if (!effects.grain) return;
    const float shared = grain_noise(gx, gy, seed, 0x7f4a7c15u);
    for (int c = 0; c < 3; ++c) {
        const float maximum = effects.density_max[c] + effects.floor[c];
        const float p = std::clamp((density[c] + effects.floor[c]) / maximum, 0.f, 1.f);
        const float n_inverse = effects.particle_area * effects.particle_scale[c] / pixel_area;
        const float deviation = maximum * std::sqrt(std::max(0.f, p * (1 - p * effects.uniformity[c]) * n_inverse));
        const float noise = .35f * shared + .9367497f * grain_noise(gx, gy, seed, 0x9e3779b9u * uint32_t(c + 1));
        density[c] += deviation * noise;
    }
}

inline void render_cpu(Image image, const Model& model, const Effects& effects,
                       uint32_t source_width, uint32_t source_height, uint64_t grain_seed, const Check& check) {
    std::array<float, 256> decode_lut{};
    for (int i = 0; i < 256; ++i) decode_lut[size_t(i)] = decode(float(i) / 255);
    const Spatial spatial = prepare_spatial(image, model, effects, decode_lut, check);
    const float pixel_um = effects.format_mm * 1000 / std::max(image.width, image.height);
    const uint32_t seed = uint32_t(grain_seed) ^ hash(uint32_t(grain_seed >> 32));
    // All spatial input fields are complete. In-place output never feeds a later pixel.
    parallel_rows(image.height, image.width, check, [&](uint32_t y) {
        uint8_t* row = image.pixels + size_t(y) * image.stride;
        const double gy = (double(y) + .5) * source_height / image.height - .5;
        for (uint32_t x = 0; x < image.width; ++x) {
            if ((x & 255u) == 0) check();
            uint8_t* pixel = row + size_t(x) * 4;
            Colour raw = effects.diffusion == 0 ? model.response.sample({pixel[0] / 255.f, pixel[1] / 255.f, pixel[2] / 255.f}) :
                film_response(model, lens_colour(read_linear(pixel, decode_lut), spatial.lens.sample(x, y, image), spatial.softness));
            const Colour halo = spatial.halation.sample(x, y, image);
            for (int c = 0; c < 3; ++c) raw[c] = std::log10(std::max(0.f,
                raw[c] * (1.f - spatial.halation_mix[c]) + halo[c]) * effects.exposure_gain + 1e-10f);
            Colour density = model.development.sample(raw);
            const double gx = (double(x) + .5) * source_width / image.width - .5;
            apply_grain(density, effects, gx, gy, pixel_um * pixel_um, seed);
            const Colour result = model.scan.sample(density);
            for (int c = 0; c < 3; ++c) {
                if (!std::isfinite(result[c])) throw std::runtime_error("Non-finite mobile film output");
                pixel[c] = uint8_t(std::lround(std::clamp(result[c], 0.f, 1.f) * 255));
            }
        }
    });
    check();
}
} // namespace lfm::mobile
