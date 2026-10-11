#include "film_seed.hpp"
namespace lfm {
static thread_local uint64_t frame_seed = 0x243F6A8885A308D3ull;
static thread_local const std::atomic_bool* cancellation = nullptr;
void set_film_seed(uint64_t seed) { frame_seed = seed ^ 0x243F6A8885A308D3ull; }
uint64_t initial_film_seed() { return frame_seed; }
void set_film_cancellation(const std::atomic_bool* flag) { cancellation = flag; }
bool film_cancelled() { return cancellation && cancellation->load(std::memory_order_relaxed); }
}
