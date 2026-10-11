#include "film_diffusion.hpp"
#include <algorithm>
#include <cmath>

namespace lfm {
namespace {
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
        for (uint32_t x = 0; x < width; ++x) for (int c = 0; c < 3; ++c) {
            double value = 0;
            for (int i = -radius; i <= radius; ++i) {
                const uint32_t sx = uint32_t(std::clamp(int(x) + i, 0, int(width) - 1));
                value += source[(size_t(y) * width + sx) * 3 + c] * weights[size_t(i + radius)];
            }
            horizontal[(size_t(y) * width + x) * 3 + c] = float(value);
        }
    }
    for (uint32_t y = 0; y < height; ++y) {
        check();
        for (uint32_t x = 0; x < width; ++x) for (int c = 0; c < 3; ++c) {
            double value = 0;
            for (int i = -radius; i <= radius; ++i) {
                const uint32_t sy = uint32_t(std::clamp(int(y) + i, 0, int(height) - 1));
                value += horizontal[(size_t(sy) * width + x) * 3 + c] * weights[size_t(i + radius)];
            }
            output[(size_t(y) * width + x) * 3 + c] = float(value);
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
            for (uint32_t sy = y0; sy < y1; ++sy) for (uint32_t sx = x0; sx < x1; ++sx)
                for (int c = 0; c < 3; ++c) sum[c] += rgb[(size_t(sy) * width + sx) * 3 + c];
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
    auto sample = [&](const std::vector<float>& plane, uint32_t x, uint32_t y, int c) {
        const float gx = std::clamp((float(x) + .5f) * gw / width - .5f, 0.0f, float(gw - 1));
        const float gy = std::clamp((float(y) + .5f) * gh / height - .5f, 0.0f, float(gh - 1));
        const uint32_t x0 = uint32_t(gx), y0 = uint32_t(gy), x1 = std::min(x0 + 1, gw - 1), y1 = std::min(y0 + 1, gh - 1);
        const float fx = gx - x0, fy = gy - y0;
        auto at = [&](uint32_t sx, uint32_t sy) { return plane[(size_t(sy) * gw + sx) * 3 + c]; };
        return (at(x0,y0) * (1-fx) + at(x1,y0) * fx) * (1-fy) + (at(x0,y1) * (1-fx) + at(x1,y1) * fx) * fy;
    };
    for (uint32_t y = 0; y < height; ++y) {
        check();
        for (uint32_t x = 0; x < width; ++x) for (int c = 0; c < 3; ++c) {
            const size_t i = (size_t(y) * width + x) * 3 + c;
            const float softness = strength * .15f;
            rgb[i] = std::max(0.0f, rgb[i] * (1-softness) + sample(core,x,y,c) * softness +
                strength * (.22f * sample(halo,x,y,c) + .10f * sample(bloom,x,y,c)));
        }
    }
}
}
