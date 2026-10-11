# Configure-time source adaptation only, part of an explicitly requested build.
# The pinned original is never rewritten and no probes/tests are executed here.
function(lfm_replace_vulkan_method source begin end include_file)
    string(FIND "${${source}}" "${begin}" begin_position)
    string(FIND "${${source}}" "${end}" end_position)
    if(begin_position EQUAL -1 OR end_position EQUAL -1 OR NOT end_position GREATER begin_position)
        message(FATAL_ERROR "Pinned Vulkan transfer adaptation boundary is missing: ${include_file}")
    endif()
    string(LENGTH "${begin}" begin_length)
    math(EXPR after_begin "${begin_position} + ${begin_length}")
    string(SUBSTRING "${${source}}" ${after_begin} -1 rest)
    string(FIND "${rest}" "${begin}" duplicate)
    if(NOT duplicate EQUAL -1)
        message(FATAL_ERROR "Pinned Vulkan transfer adaptation boundary is ambiguous: ${include_file}")
    endif()
    string(SUBSTRING "${${source}}" 0 ${begin_position} prefix)
    string(SUBSTRING "${${source}}" ${end_position} -1 suffix)
    set(${source} "${prefix}#include \"${include_file}\"\n\n${suffix}" PARENT_SCOPE)
endfunction()

file(READ "${SPK_ROOT}/src/gpu/vulkan_gpu.cpp" vulkan_source)
set(workgroup_anchor "constexpr uint32_t kWorkgroup = 256;")
string(FIND "${vulkan_source}" "${workgroup_anchor}" workgroup_position)
if(workgroup_position EQUAL -1)
    message(FATAL_ERROR "Pinned Vulkan workgroup registry anchor is missing")
endif()
string(REPLACE "${workgroup_anchor}" "constexpr uint32_t kWorkgroup = lfm::film_workgroup_size;"
    vulkan_source "${vulkan_source}")
# Never allocate a giant startup probe or override a driver's storage range
# on Android, including a software Vulkan device used by an emulator.
set(storage_probe_anchor "probe_cpu_storage_range(detail);")
string(FIND "${vulkan_source}" "${storage_probe_anchor}" storage_probe_position)
if(storage_probe_position EQUAL -1)
    message(FATAL_ERROR "Pinned Vulkan storage probe call anchor is missing")
endif()
string(REPLACE "${storage_probe_anchor}" "// Android keeps the advertised maxStorageBufferRange."
    vulkan_source "${vulkan_source}")
lfm_replace_vulkan_method(vulkan_source
    "    bool read(Buffer* src, size_t src_offset, void* dst, size_t bytes,"
    "    bool dispatch(const char* kernel, const std::vector<Arg>& args,"
    "film_vulkan_read.inc")
lfm_replace_vulkan_method(vulkan_source
    "    BufferRef upload_data(const void* raw, const double* narrow, size_t bytes,"
    "    BufferRef allocate(size_t bytes, bool persistent, std::string& error)"
    "film_vulkan_upload.inc")
set(SPK_VULKAN_SOURCE "${SPK_ADAPTED}/vulkan_gpu.cpp")
file(WRITE "${SPK_VULKAN_SOURCE}"
    "#include \"film_transfer.hpp\"\n#include \"film_gpu_layout.hpp\"\n${vulkan_source}")
