cmake_minimum_required(VERSION 3.22.1)
project(lfm_raw_preview LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(LIBRAW_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../third_party/libraw/upstream")
include("${CMAKE_CURRENT_LIST_DIR}/../../third_party/libraw/sources.cmake")
add_library(lfm_libraw STATIC ${LIBRAW_SOURCES})
target_include_directories(lfm_libraw PUBLIC "${LIBRAW_ROOT}")
# JPEG preview bytes are read without a JPEG decoder. No optional SDK/demosaic packs or OpenMP.
target_compile_definitions(lfm_libraw PUBLIC
    NODEPS LIBRAW_MAX_THUMBNAIL_MB=64 LIBRAW_MAX_PROFILE_SIZE_MB=16
    LIBRAW_CALLOC_RAWSTORE)

add_library(lfm_raw_preview SHARED raw_preview_jni.cpp)
target_link_libraries(lfm_raw_preview PRIVATE lfm_libraw m)
target_link_options(lfm_raw_preview PRIVATE
    "-Wl,--exclude-libs,ALL" "-Wl,--no-undefined"
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../lut" "${CMAKE_CURRENT_BINARY_DIR}/lut")
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../mobile-film" "${CMAKE_CURRENT_BINARY_DIR}/mobile-film")
