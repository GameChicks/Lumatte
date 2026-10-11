cmake_minimum_required(VERSION 3.22.1)
project(lfm_mobile_film LANGUAGES CXX)
add_library(lfm_mobile_film SHARED mobile_film_jni.cpp)
target_compile_features(lfm_mobile_film PRIVATE cxx_std_20)
target_compile_options(lfm_mobile_film PRIVATE -ffp-contract=off)
target_link_libraries(lfm_mobile_film PRIVATE jnigraphics m)
target_link_options(lfm_mobile_film PRIVATE "-Wl,--no-undefined"
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
