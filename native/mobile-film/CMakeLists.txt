cmake_minimum_required(VERSION 3.22.1)
project(lfm_mobile_film LANGUAGES CXX)
file(READ "${CMAKE_CURRENT_LIST_DIR}/shaders/film.vert" FILM_VERTEX)
file(READ "${CMAKE_CURRENT_LIST_DIR}/shaders/film.frag" FILM_FRAGMENT)
configure_file(mobile_shaders.hpp.in "${CMAKE_CURRENT_BINARY_DIR}/mobile_shaders.hpp" @ONLY)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_LIST_DIR}/shaders/film.vert" "${CMAKE_CURRENT_LIST_DIR}/shaders/film.frag")
add_library(lfm_mobile_film SHARED mobile_film_jni.cpp mobile_film_gles.cpp)
target_include_directories(lfm_mobile_film PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_features(lfm_mobile_film PRIVATE cxx_std_20)
# Prototypes use Debug, but full-resolution image processing still needs optimization.
# Preserve IEEE checks and cancellation; do not enable fast-math.
target_compile_options(lfm_mobile_film PRIVATE -O3 -ffp-contract=off)
target_link_libraries(lfm_mobile_film PRIVATE jnigraphics EGL GLESv3 m)
target_link_options(lfm_mobile_film PRIVATE "-Wl,--no-undefined"
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
