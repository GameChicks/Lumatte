#pragma once
#include <cstdint>
#include <functional>
#include <vector>
namespace lfm {
// Product effect levels, not calibrated claims about a physical filter brand.
void black_diffusion(std::vector<float>& linear_rgb, uint32_t width, uint32_t height,
                     float strength, const std::function<void()>& check_cancelled);
}
