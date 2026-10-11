#pragma once
#include "mobile_film_core.hpp"
#include <string>

namespace lfm::mobile {
struct GlesError : std::runtime_error { using std::runtime_error::runtime_error; };
void render_gles(Image image, const Model& model, const Effects& effects, const std::string& model_key,
    uint32_t source_width, uint32_t source_height, uint64_t seed, const Check& check);
// Only from the same native worker, after prior calls have drained. Never initializes EGL.
void release_idle_gles();
}
