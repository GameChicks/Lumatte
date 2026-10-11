// Included inside the pinned VulkanGpu class by adapt_vulkan.cmake.
    bool read(Buffer* src, size_t src_offset, void* dst, size_t bytes,
              std::string& error) override {
        if (!validate_read(src, src_offset, dst, bytes, error)) return false;
        if (!bytes) return true;
        std::lock_guard<std::mutex> lock(dispatch_mutex_);
        const auto start = diagnostic_start();
        if (!ensure_staging(read_staging_, std::min(bytes, lfm::film_transfer_chunk_bytes), true, error)) return false;
        for (size_t offset = 0; offset < bytes;) {
            if (lfm::film_cancelled()) { error = "Film readback cancelled"; return false; }
            const size_t count = std::min(bytes - offset, lfm::film_transfer_chunk_bytes);
            if (!submit_sync([&](VkCommandBuffer command) {
                    device_barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                   VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
                    VkBufferCopy range{VkDeviceSize(src_offset + offset), 0, VkDeviceSize(count)};
                    vkCmdCopyBuffer(command, src->handle, read_staging_.handle, 1, &range);
                    VkBufferMemoryBarrier barrier{};
                    barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                    barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
                    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                    barrier.buffer = read_staging_.handle;
                    barrier.offset = 0;
                    barrier.size = VkDeviceSize(count);
                    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                         VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &barrier, 0, nullptr);
                }, "Vulkan host readback", error) ||
                !mapped_range(read_staging_, count, false, error)) return false;
            std::memcpy(static_cast<unsigned char*>(dst) + offset, read_staging_.mapped, count);
            // submit_sync has drained the queue before the same staging bytes are reused.
            offset += count;
        }
        transfer_timing("read", bytes, start);
        return true;
    }
