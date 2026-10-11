#pragma once
#include <cstddef>
#include "film_seed.hpp"

namespace lfm {
// A byte-copy boundary, not an image downsample or a change to float precision.
inline constexpr std::size_t film_transfer_chunk_bytes = 4u * 1024u * 1024u;
static_assert(film_transfer_chunk_bytes % sizeof(float) == 0);
}
