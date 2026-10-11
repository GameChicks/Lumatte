#pragma once
#include <cstdint>
#include <atomic>
namespace lfm {
void set_film_seed(uint64_t seed);
uint64_t initial_film_seed();
void set_film_cancellation(const std::atomic_bool* flag);
bool film_cancelled();
}
