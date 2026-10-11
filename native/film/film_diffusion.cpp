#include "film_diffusion.hpp"
#include <algorithm>
#include <cmath>

namespace lfm {
namespace {
struct AxisSample { uint32_t low, high; float mix; };

std::vector<AxisSample> sample_axis(uint32_t pixels, uint32_t grid,
                                  const std::function<void()>& check) {
    std::vector<AxisSample> samples(pixels);
    for (uint32_t i = 0; i < pixels; ++i) {
        if ((i & 1023u) == 0) check();
        const float position = std::clamp((float(i) + .5f) * grid / pixels - .5f,
                                          0.0f, float(grid - 1));
        const uint32_t low = uint32_t(position);
        samples[i] = {low, std::min(low + 1, grid - 1), position - low};
    }
    return samples;
}

struct GridSample {
    size_t top_left, top_right, bottom_left, bottom_right;
    float fx, fy;
    float read(const std::vector<float>& plane, int channel) const {
        return (plane[top_left + channel] * (1-fx) + plane[top_right + channel] * fx) * (1-fy) +
               (plane[bottom_left + channel] * (1-fx) + plane[bottom_right + channel] * fx) * fy;
    }
};

std::vector<float> gaussian(const std::vector<float>& source, uint32_t width, uint32_t height,
                            float sigma, const std::function<void()>& check) {
    const int radius = int(std::ceil(3.0f * sigma));
    std::vector<float> weights(size_t(radius * 2 + 1));
    double sum = 0;
    for (int i = -radius; i <= radius; ++i) { weights[size_t(i + radius)] = std::exp(-.5f * i * i / (sigma * sigma)); sum += weights[size_t(i + radius)]; }
    for (float& w : weights) w = float(w / sum);
    std::vector<float> horizontal(source.size()), output(source.size());
    for (uint32_t y = 0; y < height; ++y) {
        check();
        for (uint32_t x = 0; x < width; ++x) {
            double value[3] = {};
            for (int i = -radius; i <= radius; ++i) {
                const uint32_t sx = uint32_t(std::clamp(int(x) + i, 0, int(width) - 1));
                const size_t index = (size_t(y) * width + sx) * 3;
                const float weight = weights[size_t(i + radius)];
                for (int c = 0; c < 3; ++c) value[c] += source[index + c] * weight;
            }
            for (int c = 0; c < 3; ++c) horizontal[(size_t(y) * width + x) * 3 + c] = float(value[c]);
        }
    }
    for (uint32_t y = 0; y < height; ++y) {
        check();
        for (uint32_t x = 0; x < width; ++x) {
            double value[3] = {};
            for (int i = -radius; i <= radius; ++i) {
                const uint32_t sy = uint32_t(std::clamp(int(y) + i, 0, int(height) - 1));
                const size_t index = (size_t(sy) * width + x) * 3;
                const float weight = weights[size_t(i + radius)];
                for (int c = 0; c < 3; ++c) value[c] += horizontal[index + c] * weight;
            }
            for (int c = 0; c < 3; ++c) output[(size_t(y) * width + x) * 3 + c] = float(value[c]);
        }
    }
    return output;
}
}

void black_diffusion(std::vector<float>& rgb, uint32_t width, uint32_t height,
                     float strength, const std::function<void()>& check) {
    if (strength <= 0) return;
    const double scale = std::min(1.0, 512.0 / std::max(width, height));
    const uint32_t gw = std::max(1u, uint32_t(std::ceil(width * scale)));
    const uint32_t gh = std::max(1u, uint32_t(std::ceil(height * scale)));
    std::vector<float> light(size_t(gw) * gh * 3), highlights(light.size());
    // Area-average the linear image so small lamps contribute at every output size.
    for (uint32_t y = 0; y < gh; ++y) {
        check();
        const uint32_t y0 = uint32_t(uint64_t(y) * height / gh), y1 = uint32_t(uint64_t(y + 1) * height / gh);
        for (uint32_t x = 0; x < gw; ++x) {
            const uint32_t x0 = uint32_t(uint64_t(x) * width / gw), x1 = uint32_t(uint64_t(x + 1) * width / gw);
            double sum[3] = {};
            for (uint32_t sy = y0; sy < y1; ++sy) {
                if ((sy & 63u) == 0) check();
                for (uint32_t sx = x0; sx < x1; ++sx)
                    for (int c = 0; c < 3; ++c) sum[c] += rgb[(size_t(sy) * width + sx) * 3 + c];
            }
            const double count = double(x1 - x0) * (y1 - y0);
            for (int c = 0; c < 3; ++c) {
                const size_t i = (size_t(y) * gw + x) * 3 + c;
                light[i] = float(sum[c] / count);
                highlights[i] = std::max(light[i] - .18f, 0.0f);
            }
        }
    }
    auto core = gaussian(light, gw, gh, .75f, check);
    auto halo = gaussian(highlights, gw, gh, 3.5f, check);
    auto bloom = gaussian(highlights, gw, gh, 10.0f, check);
    // Reuse coordinates across the three channels and the three effect planes.
    // This is O(width + height) auxiliary storage, never another whole-frame plane.
    const auto x_samples = sample_axis(width, gw, check);
    const auto y_samples = sample_axis(height, gh, check);
    const float softness = strength * .15f;
    for (uint32_t y = 0; y < height; ++y) {
        check();
        const auto sy = y_samples[y];
        const size_t top = size_t(sy.low) * gw, bottom = size_t(sy.high) * gw;
        for (uint32_t x = 0; x < width; ++x) {
            const auto sx = x_samples[x];
            const GridSample sample{(top + sx.low) * 3, (top + sx.high) * 3,
                (bottom + sx.low) * 3, (bottom + sx.high) * 3, sx.mix, sy.mix};
            for (int c = 0; c < 3; ++c) {
                const size_t i = (size_t(y) * width + x) * 3 + c;
                rgb[i] = std::max(0.0f, rgb[i] * (1-softness) + sample.read(core,c) * softness +
                    strength * (.22f * sample.read(halo,c) + .10f * sample.read(bloom,c)));
            }
        }
    }
}
}
