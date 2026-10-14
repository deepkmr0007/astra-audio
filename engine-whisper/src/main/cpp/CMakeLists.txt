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
# Speech recognition is pure number crunching: always compile it optimized, even inside a debug APK
# (an -O0 whisper.cpp is 5–10× slower and would make debug builds useless for real recordings).
add_compile_options(-O3)
add_compile_definitions(NDEBUG)

if(ANDROID)
    # 16 KB page alignment for EVERY native library (Android 15+ devices with 16 KB pages, Play requirement).
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
    set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
endif()

# --- CPU kernels: pick the fastest variant the phone supports, at run time -------------------------
# arm64 phones differ a lot: ARMv8.2 dot-product, FP16 arithmetic and ARMv8.6 int8 matrix-multiply
# make whisper several times faster on newer chips, but crash older Cortex-A53/A73 phones (SIGILL) if
# compiled in unconditionally. ggml's upstream answer is to build one small CPU-backend library per
# feature set (android_armv8.0_1 … android_armv9.2_2) and score them against the CPU's HWCAP bits
# when the app starts, loading only the best one. These libraries are part of the APK (our own code,
# installed by the system) — nothing is downloaded or loaded from anywhere else.
option(ASTRA_CPU_VARIANTS "Build all ggml CPU variants and choose at run time" OFF)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(ASTRA_CPU_VARIANTS ON)
endif()

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)        # ggml's own thread pool; avoids shipping libomp
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)        # never tune for the build machine
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)      # faster tiled matmul kernels (sgemm)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)

if(ASTRA_CPU_VARIANTS)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    if(ANDROID)
        set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    endif()
else()
    # Other ABIs (32-bit ARM, x86_64 emulator): one static baseline build linked into the JNI library.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${WHISPER_DIR} whisper-build)

add_library(astra_whisper SHARED astra_whisper_jni.cpp)
target_include_directories(astra_whisper PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
if(ASTRA_CPU_VARIANTS)
    target_compile_definitions(astra_whisper PRIVATE ASTRA_CPU_VARIANTS=1)
    target_link_libraries(astra_whisper PRIVATE ggml-base ggml)
endif()

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
