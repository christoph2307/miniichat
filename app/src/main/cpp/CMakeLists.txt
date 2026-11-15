cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp is fetched at configure time (pinned tag) and built as static libs
# linked into a single JNI shared library: libwhisper_jni.so
include(FetchContent)

set(WHISPER_CPP_TAG "v1.9.5" CACHE STRING "whisper.cpp git tag")

# Optional GPU backend (OpenCL, with Qualcomm's Adreno-optimised kernels). Built only in the
# :whisper-gpu module. libOpenCL.so is the phone's vendor driver at runtime; the Khronos ICD
# loader built here is only a link stub and is excluded from the APK (see app/build.gradle.kts).
option(WHISPER_JNI_OPENCL "build the OpenCL GPU backend" OFF)
set(OPENCL_SDK_TAG "v2026.05.29" CACHE STRING "Khronos OpenCL headers / ICD loader tag")

set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL             OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2             OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS         OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES      OFF CACHE BOOL "" FORCE)

if (ANDROID AND ANDROID_ABI STREQUAL "armeabi-v7a")
    # same flags as upstream whisper.android example for 32-bit ARM
    add_compile_options(-mfpu=neon-vfpv4 -mfloat-abi=softfp)
endif()

if (WHISPER_JNI_OPENCL)
    FetchContent_Declare(opencl_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        GIT_TAG        ${OPENCL_SDK_TAG}
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(opencl_headers)

    set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(BUILD_TESTING                OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERINFO      OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERS         OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS ON)  # the loader must be a shared lib → NEEDED libOpenCL.so
    FetchContent_Declare(opencl_icd_loader
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
        GIT_TAG        ${OPENCL_SDK_TAG}
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(opencl_icd_loader)
    set(BUILD_SHARED_LIBS OFF)
    # Android drivers ship as plain "libOpenCL.so": drop the versioned SONAME (libOpenCL.so.1)
    # so our NEEDED entry resolves to the vendor library at runtime.
    set_property(TARGET OpenCL PROPERTY VERSION)
    set_property(TARGET OpenCL PROPERTY SOVERSION)

    # satisfy ggml's find_package(OpenCL) with the fetched headers + loader target
    set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY     OpenCL CACHE STRING "" FORCE)
    set(GGML_OPENCL                    ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS      ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON  CACHE BOOL "" FORCE)
    add_compile_definitions(WHISPER_JNI_HAS_GPU=1)
endif()

if (DEFINED WHISPER_CPP_SOURCE_DIR AND EXISTS "${WHISPER_CPP_SOURCE_DIR}/CMakeLists.txt")
    # Offline builds: -DWHISPER_CPP_SOURCE_DIR=/path/to/whisper.cpp
    add_subdirectory(${WHISPER_CPP_SOURCE_DIR} whisper_build)
else()
    FetchContent_Declare(
        whisper
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG        ${WHISPER_CPP_TAG}
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(whisper)
endif()

# The same sources are built twice by Gradle: the baseline lib (app module) and an
# ARMv8.2 lib with dot-product + FP16 instructions (:whisper-opt module), selected at runtime.
set(WHISPER_JNI_LIB_NAME "whisper_jni" CACHE STRING "name of the JNI library")
add_library(whisper_jni SHARED whisper_jni.cpp)
set_target_properties(whisper_jni PROPERTIES OUTPUT_NAME ${WHISPER_JNI_LIB_NAME})

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(whisper_jni PRIVATE whisper ${LOG_LIB} android)
else()
    # Host build (for compile checks only)
    find_package(JNI REQUIRED)
    target_include_directories(whisper_jni PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(whisper_jni PRIVATE whisper)
endif()

target_compile_options(whisper_jni PRIVATE -O3)
