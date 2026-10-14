cmake_minimum_required(VERSION 3.22.1)
project(astra_whisper LANGUAGES C CXX)

# whisper.cpp v1.9.5 (commit d1be6fde11ac6e0407606b4e42fe72d34add8037), vendored and pruned to the
# CPU backend only. GPU backends and ggml-rpc (which contains socket code) are removed, so the native
# library has no networking capability at all. See docs/MODEL_INVENTORY.md and THIRD_PARTY_NOTICES.md.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/include/whisper.h)
    message(FATAL_ERROR "whisper.cpp sources missing at ${WHISPER_DIR}. Run scripts/fetch-whisper.sh")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)  # static libs are linked into a shared JNI library

# Static libraries linked into one shared JNI library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)        # ggml's own thread pool; avoids shipping libomp
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)        # never tune for the build machine
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)    # no dynamic backend loading (no dlopen of code)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)

# CPU baseline only (armv8-a + NEON on arm64). ARMv8.2 dot-product/fp16 kernels would be faster but
# crash with SIGILL on older Cortex-A53/A73 phones that are still common; a runtime-dispatched
# second library is listed in docs/UNFINISHED.md as a performance follow-up.

add_subdirectory(${WHISPER_DIR} whisper-build EXCLUDE_FROM_ALL)

add_library(astra_whisper SHARED astra_whisper_jni.cpp)
target_include_directories(astra_whisper PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)

if(ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(astra_whisper PRIVATE whisper ${LOG_LIB})
    if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        target_compile_options(astra_whisper PRIVATE -O3 -fvisibility=hidden)
        target_link_options(astra_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
    endif()
    # 16 KB page size support (required for new apps targeting Android 15+ on Google Play).
    target_link_options(astra_whisper PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Host build (used by scripts/verify-native.sh to compile-check the JNI bridge).
    find_package(JNI REQUIRED)
    target_include_directories(astra_whisper PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(astra_whisper PRIVATE whisper)
endif()
