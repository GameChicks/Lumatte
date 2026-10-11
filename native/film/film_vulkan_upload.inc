// Included inside the pinned VulkanGpu class by adapt_vulkan.cmake.
    BufferRef upload_data(const void* raw, const double* narrow, size_t bytes,
                          bool persistent, std::string& error) {
        if (bytes % 4) { error = "Vulkan upload size must be 4-byte aligned"; return {}; }
        const auto start = diagnostic_start();
        BufferRef out = allocate(bytes, persistent, error);
        if (!out) return {};
        std::lock_guard<std::mutex> lock(dispatch_mutex_);
        if (!ensure_staging(upload_staging_, std::min(bytes, lfm::film_transfer_chunk_bytes), false, error)) return {};
        for (size_t offset = 0; offset < bytes;) {
            if (lfm::film_cancelled()) { error = "Film upload cancelled"; return {}; }
            const size_t count = std::min(bytes - offset, lfm::film_transfer_chunk_bytes);
            if (narrow) {
                float* dst = static_cast<float*>(upload_staging_.mapped);
                for (size_t i = 0; i < count / sizeof(float); ++i)
                    dst[i] = float(narrow[offset / sizeof(float) + i]);
            } else {
                std::memcpy(upload_staging_.mapped, static_cast<const unsigned char*>(raw) + offset, count);
            }
            if (!mapped_range(upload_staging_, count, true, error)) return {};
            if (!submit_sync([&](VkCommandBuffer command) {
                    device_barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                    VkBufferMemoryBarrier host{};
                    host.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                    host.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
                    host.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                    host.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                    host.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                    host.buffer = upload_staging_.handle;
                    host.offset = 0;
                    host.size = VkDeviceSize(count);
                    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_HOST_BIT,
                                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 1, &host, 0, nullptr);
                    VkBufferCopy range{0, VkDeviceSize(offset), VkDeviceSize(count)};
                    vkCmdCopyBuffer(command, upload_staging_.handle, out.get()->handle, 1, &range);
                    transfer_written(command);
                }, "Vulkan upload", error)) return {};
            offset += count;
        }
        transfer_timing("upload", bytes, start);
        return out;
    }
