cmake_minimum_required(VERSION 3.22.1)
project(lfm_lut LANGUAGES CXX)

add_library(lfm_lut SHARED lut_jni.cpp)
target_compile_features(lfm_lut PRIVATE cxx_std_20)
# Preserve float32 operation boundaries of the existing Kotlin colour path.
target_compile_options(lfm_lut PRIVATE -ffp-contract=off)
target_link_libraries(lfm_lut PRIVATE jnigraphics m)
target_link_options(lfm_lut PRIVATE "-Wl,--no-undefined"
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
