#pragma once
#include <span>
#include <string_view>
namespace lfm {
struct FilmShader { std::string_view name; std::span<const unsigned char> bytes; };
std::span<const FilmShader> film_shaders();
}
