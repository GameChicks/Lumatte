cmake_minimum_required(VERSION 3.22.1)
project(lfm_film LANGUAGES C CXX)

set(SPK_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../third_party/spektralab/upstream/engine")
set(SPK_CORE "${SPK_ROOT}/src/core")
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The NDK supplies Vulkan headers and a host glslc. No dependency downloads,
# reference build scripts, examples, probes or test targets are invoked here.
find_program(LFM_GLSLC NAMES glslc glslc.exe HINTS
    "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64"
    "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64"
    "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64" REQUIRED)

set(SPK_CORE_NAMES blob cam16 colour curves digital_intermediate hanatos json
    latitude_fit numeric params print_lut printing profile setup_cache spectral)
set(SPK_CORE_SOURCES)
foreach(name IN LISTS SPK_CORE_NAMES)
    list(APPEND SPK_CORE_SOURCES "${SPK_CORE}/${name}.cpp")
endforeach()
add_library(lfm_spectral_core STATIC ${SPK_CORE_SOURCES})
target_include_directories(lfm_spectral_core PUBLIC "${SPK_CORE}")

add_library(lfm_volk STATIC "${SPK_ROOT}/third_party/volk/volk.c")
target_include_directories(lfm_volk PUBLIC "${SPK_ROOT}/third_party/volk")
target_link_libraries(lfm_volk PUBLIC dl)

# Derive per-photo RNG and task cancellation in generated copies. The upstream
# progress flag is a plain bool and resets when rendering starts; the app uses
# its own atomic task flag at node boundaries instead of writing that bool.
set(SPK_ADAPTED "${CMAKE_CURRENT_BINARY_DIR}/adapted")
file(MAKE_DIRECTORY "${SPK_ADAPTED}")
# Vulkan 1.0 only guarantees 128 invocations in a workgroup. One value drives
# the host registry, the reduction dispatch and all generated shader layouts.
set(LFM_FILM_WORKGROUP 128)
file(WRITE "${SPK_ADAPTED}/film_gpu_layout.hpp"
    "#pragma once\n#include <cstdint>\nnamespace lfm { inline constexpr uint32_t film_workgroup_size = ${LFM_FILM_WORKGROUP}u; }\n")
file(GLOB pipeline_headers "${SPK_ROOT}/src/pipeline/*.hpp")
foreach(header IN LISTS pipeline_headers)
    get_filename_component(name "${header}" NAME)
    configure_file("${header}" "${SPK_ADAPTED}/${name}" COPYONLY)
endforeach()
file(READ "${SPK_ROOT}/src/pipeline/pipeline.hpp" pipeline_header)
set(seed_anchor "uint64_t rng_state_ = 0x243F6A8885A308D3ull;")
string(FIND "${pipeline_header}" "${seed_anchor}" seed_position)
if(seed_position EQUAL -1)
    message(FATAL_ERROR "Pinned film seed adaptation anchor is missing")
endif()
string(REPLACE "${seed_anchor}" "uint64_t rng_state_ = lfm::initial_film_seed();" pipeline_header "${pipeline_header}")
file(WRITE "${SPK_ADAPTED}/pipeline.hpp" "#include \"film_seed.hpp\"\n${pipeline_header}")
set(SPK_PIPELINE_SOURCES)
foreach(name blur engine pipeline scene_latitude windows_unported)
    configure_file("${SPK_ROOT}/src/pipeline/${name}.cpp" "${SPK_ADAPTED}/${name}.cpp" COPYONLY)
    list(APPEND SPK_PIPELINE_SOURCES "${SPK_ADAPTED}/${name}.cpp")
endforeach()
file(READ "${SPK_ROOT}/src/pipeline/pipeline.cpp" pipeline_source)
set(cancel_anchor "if (progress_ && progress_->cancelled)")
string(FIND "${pipeline_source}" "${cancel_anchor}" cancel_position)
if(cancel_position EQUAL -1)
    message(FATAL_ERROR "Pinned film cancellation adaptation anchor is missing")
endif()
string(REPLACE "${cancel_anchor}"
    "if (lfm::film_cancelled() || (progress_ && progress_->cancelled))"
    pipeline_source "${pipeline_source}")
set(reduction_anchor "groups * 256, error)) return false;")
string(FIND "${pipeline_source}" "${reduction_anchor}" reduction_position)
if(reduction_position EQUAL -1)
    message(FATAL_ERROR "Pinned film maximum reduction dispatch anchor is missing")
endif()
string(REPLACE "${reduction_anchor}" "groups * lfm::film_workgroup_size, error)) return false;"
    pipeline_source "${pipeline_source}")
file(WRITE "${SPK_ADAPTED}/pipeline.cpp" "#include \"film_gpu_layout.hpp\"\n${pipeline_source}")
include("${CMAKE_CURRENT_LIST_DIR}/adapt_vulkan.cmake")

set(SPK_SHADER_NAMES spk_math_probe spk_take_rgb spk_matmul3 spk_stride_sample
    spk_affine3 spk_tc_b spk_lut2d_cubic spk_sep_fir_acc spk_iir_df_acc
    spk_lincomb3 spk_log10_guarded spk_curves spk_couplers_correction
    spk_spectral_epilogue spk_print_exposure spk_cam16ucs_compress
    spk_cctf_encode_matrix spk_to_rgba16 spk_grain_layers spk_grain_layer_one
    spk_grain_simple spk_lognormal_field spk_rng_probe spk_mul spk_glare_add
    spk_reduce_max spk_boost spk_bw_correct spk_edr spk_cctf_decode
    spk_zoom_bilinear_mirror spk_geometry_resample_df spk_lut3d_trilinear spk_di_normalise)
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/shaders")
set(shader_headers)
set(shader_registry "#include \"film_shader_resources.hpp\"\n")
set(shader_entries "")
foreach(name IN LISTS SPK_SHADER_NAMES)
    set(shader_original "${SPK_ROOT}/src/shaders/vulkan/${name}.comp")
    set(shader "${CMAKE_CURRENT_BINARY_DIR}/shaders/${name}.comp")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${shader_original}")
    file(READ "${shader_original}" shader_source)
    if(NOT name STREQUAL "spk_math_probe")
        set(layout_anchor "layout(local_size_x = 256) in;")
        string(FIND "${shader_source}" "${layout_anchor}" layout_position)
        if(layout_position EQUAL -1)
            message(FATAL_ERROR "Pinned shader workgroup layout is missing: ${name}")
        endif()
        string(REPLACE "${layout_anchor}" "layout(local_size_x = ${LFM_FILM_WORKGROUP}) in;"
            shader_source "${shader_source}")
    endif()
    if(name STREQUAL "spk_reduce_max")
        set(scratch_anchor "shared float scratch[256];")
        string(FIND "${shader_source}" "${scratch_anchor}" scratch_position)
        if(scratch_position EQUAL -1)
            message(FATAL_ERROR "Pinned maximum reduction scratch layout is missing")
        endif()
        string(REPLACE "${scratch_anchor}" "shared float scratch[${LFM_FILM_WORKGROUP}];"
            shader_source "${shader_source}")
    endif()
    file(WRITE "${shader}" "${shader_source}")
    set(spirv "${CMAKE_CURRENT_BINARY_DIR}/shaders/${name}.spv")
    set(header "${spirv}.hpp")
    add_custom_command(OUTPUT "${spirv}" "${header}"
        COMMAND "${LFM_GLSLC}" --target-env=vulkan1.0 -fshader-stage=compute
            "-I${SPK_ROOT}/src/shaders/vulkan" "${shader}" -o "${spirv}"
        COMMAND "${CMAKE_COMMAND}" "-DINPUT=${spirv}" "-DOUTPUT=${header}"
            "-DSYMBOL=${name}" -P "${CMAKE_CURRENT_LIST_DIR}/embed_shader.cmake"
        DEPENDS "${shader}" "${shader_original}" "${SPK_ROOT}/src/shaders/vulkan/spk_rng.glsl"
            "${CMAKE_CURRENT_LIST_DIR}/embed_shader.cmake" VERBATIM)
    list(APPEND shader_headers "${header}")
    string(APPEND shader_registry "#include \"${name}.spv.hpp\"\n")
    string(APPEND shader_entries "{\"${name}.spv\", std::span(shader_${name})},\n")
endforeach()
string(APPEND shader_registry "namespace lfm {\nstd::span<const FilmShader> film_shaders() {\nstatic const FilmShader shaders[] = {${shader_entries}};\nreturn shaders;\n}\n}\n")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/film_shader_resources.cpp" "${shader_registry}")
set_source_files_properties("${CMAKE_CURRENT_BINARY_DIR}/film_shader_resources.cpp"
    PROPERTIES OBJECT_DEPENDS "${shader_headers}")

add_library(lfm_film SHARED film_jni.cpp film_diffusion.cpp film_seed.cpp
    "${CMAKE_CURRENT_BINARY_DIR}/film_shader_resources.cpp" ${shader_headers}
    ${SPK_PIPELINE_SOURCES} "${SPK_VULKAN_SOURCE}")
target_include_directories(lfm_film PRIVATE "${CMAKE_CURRENT_LIST_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/shaders" "${SPK_ADAPTED}" "${SPK_ROOT}/include"
    "${SPK_ROOT}/src" "${SPK_ROOT}/src/gpu" "${SPK_CORE}")
target_compile_definitions(lfm_film PRIVATE SPK_VERSION="lfm-prototype"
    SPK_BUILD_STAMP="spektralab-6a347d5756f8+lfm")
target_link_libraries(lfm_film PRIVATE lfm_spectral_core lfm_volk jnigraphics dl m)
target_link_options(lfm_film PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--no-undefined"
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
