cmake_minimum_required(VERSION 3.22.1)
project(mangaflow_vulkan_enhancement LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(ncnn_android
    URL https://github.com/Tencent/ncnn/releases/download/20261020/ncnn-20261020-android-vulkan.zip
    URL_HASH SHA256=6e0c5f9b397faf32b923de2ef6210af9bf375851869dceff06a8005331981086
    TLS_VERIFY TRUE)
FetchContent_GetProperties(ncnn_android)
if(NOT ncnn_android_POPULATED)
    FetchContent_Populate(ncnn_android)
endif()

set(ncnn_component "${ncnn_android_SOURCE_DIR}/${ANDROID_ABI}")
if(NOT EXISTS "${ncnn_component}/lib/cmake/ncnn/ncnnConfig.cmake")
    set(ncnn_component "${ncnn_android_SOURCE_DIR}/ncnn-20261020-android-vulkan/${ANDROID_ABI}")
endif()
find_package(ncnn CONFIG REQUIRED
    PATHS "${ncnn_component}/lib/cmake/ncnn" NO_DEFAULT_PATH)
if(NOT NCNN_VERSION STREQUAL "20261020" OR NOT NCNN_VULKAN)
    message(FATAL_ERROR "Expected the pinned ncnn 20261020 Vulkan Android runtime")
endif()

file(READ "${CMAKE_CURRENT_SOURCE_DIR}/rife/warp.comp" rife_shader)
string(FIND "${rife_shader}" "#version" rife_shader_start)
string(SUBSTRING "${rife_shader}" ${rife_shader_start} -1 rife_shader)
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rife_warp_shader.h"
    "static constexpr char kRifeWarpShader[] = R\"MF_SHADER(${rife_shader})MF_SHADER\";\n")

add_library(mangaflow_cugan SHARED cugan_jni.cpp rife_jni.cpp)
target_include_directories(mangaflow_cugan PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(mangaflow_cugan PRIVATE ncnn)
target_link_options(mangaflow_cugan PRIVATE "-Wl,-z,max-page-size=16384")
