cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp is fetched at configure time (pinned tag) and built as static libs
# linked into a single JNI shared library: libwhisper_jni.so
include(FetchContent)

set(WHISPER_CPP_TAG "v1.7.4" CACHE STRING "whisper.cpp git tag")

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

add_library(whisper_jni SHARED whisper_jni.cpp)

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
