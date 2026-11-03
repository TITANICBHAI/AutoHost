cmake_minimum_required(VERSION 3.22.1)

project(aria-llama-jni VERSION 1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── llama.cpp submodule path ────────────────────────────────────────────────
# To add llama.cpp: from artifacts/mobile/android/app/src/main/cpp/
#   git submodule add https://github.com/ggerganov/llama.cpp llama.cpp
#   git submodule update --init --recursive
set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama.cpp)

# ─── Hardware flags for Exynos 9611 (Samsung Galaxy M31) ─────────────────────
# ARM Cortex-A73 × 4 (big) + Cortex-A53 × 4 (LITTLE) → arm64-v8a, NEON always on
# Mali-G72 MP3 → Vulkan 1.1 + OpenCL 2.0
# No dedicated NPU
#
# ── GPU backends: Vulkan + OpenCL both compiled in ───────────────────────────
#
# Both backends are linked into the same .so. The user selects which one to use
# at runtime via the in-app Settings → GPU Backend chip selector (Vulkan / OpenCL / CPU).
# GGML's backend registry handles the routing; no rebuild needed to switch.
#
# Vulkan (~15–30 tok/s on Mali-G72):
#   Requires glslc (GLSL→SPIR-V compiler) on the BUILD machine.
#   • Firebase Studio: pkgs.shaderc is in .idx/dev.nix — glslc auto-installed.
#   • Android Studio local: sudo apt install glslc
#                       or  https://vulkan.lunarg.com/sdk/home
#   • GitHub Actions CI:  NDK's built-in glslc is added to PATH via $GITHUB_PATH
#                         (see .github/workflows/build-android.yml — Install step).
#   Runtime: libvulkan.so — standard on Android 7+ (M31 ships Android 10).
#
# OpenCL (~8–15 tok/s on Mali-G72 MP3, OpenCL 2.0):
#   No glslc needed — kernels compile on-device at first run.
#   Requires: opencl-headers/ vendored in this directory (already present).
#             Python3 on the BUILD machine (for GGML_OPENCL_EMBED_KERNELS —
#             embeds .cl sources as C strings so no runtime file I/O is needed).
#   Runtime: libOpenCL.so — present on M31 via Mali vendor driver.
#   Custom FindOpenCL.cmake (same directory) satisfies find_package(OpenCL REQUIRED)
#   without requiring libOpenCL.so in the NDK sysroot.

set(GGML_VULKAN OFF CACHE BOOL "Vulkan backend — Mali-G72 MP3 Vulkan 1.1 (requires glslc at build time)" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "OpenCL backend — Mali-G72 MP3 OpenCL 2.0 (runtime-switchable with Vulkan)" FORCE)
set(GGML_METAL  OFF CACHE BOOL "Metal is iOS only" FORCE)
set(GGML_CUDA   OFF CACHE BOOL "No CUDA on Android" FORCE)
set(GGML_BLAS   OFF CACHE BOOL "No external BLAS needed for Q4_K_M" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Cross-compiling for arm64-v8a" FORCE)

# ─── OpenCL build options ─────────────────────────────────────────────────────
# FindOpenCL.cmake in this directory resolves the NDK+Android path.
list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}")

set(GGML_OPENCL_EMBED_KERNELS    ON  CACHE BOOL   "Embed .cl kernels as C strings (no runtime file I/O on Android)" FORCE)
set(GGML_OPENCL_TARGET_VERSION   "300" CACHE STRING "OpenCL host-API target version (300 = OpenCL 3.0 headers; Mali-G72 is 2.0 at runtime)" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS OFF CACHE BOOL "Adreno-specific matmul kernels — disable for Mali" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# LLAMA_CURL gates HTTP-based model fetching in llama.cpp/common (libcurl).
# Android does not need this — models are sideloaded from app storage — and
# pulling libcurl into the NDK build would force us to vendor or cross-compile
# it. Disable explicitly so common/CMakeLists.txt:85 does not abort with
# "Could NOT find CURL".
set(LLAMA_CURL OFF CACHE BOOL "No HTTP model download on Android" FORCE)
# LLAMA_BUILD_COMMON defaults to LLAMA_STANDALONE, which is OFF when llama.cpp
# is consumed via add_subdirectory (not a top-level build). Force it ON so the
# 'common' CMake target is always produced and can be linked by llama-jni.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build llama.cpp common utilities" FORCE)

# ─── Vulkan HPP headers for Android NDK cross-build ──────────────────────────
# ggml-vulkan.cpp includes <vulkan/vulkan.hpp> (Vulkan C++ API wrapper from
# KhronosGroup/Vulkan-Hpp).  This file is NOT shipped with the Android NDK —
# only the C API headers (vulkan/vulkan.h, vulkan/vulkan_core.h) are.
#
# Strategy: the CI runner (ubuntu-24.04) provides vulkan.hpp via libvulkan-dev
# at /usr/include/vulkan/vulkan.hpp.  On Windows, it is provided by the
# Vulkan SDK (e.g. C:/VulkanSDK/1.4.341.1/Include/vulkan/vulkan.hpp).
# Because vulkan.hpp is a header-only template library (zero architecture-specific code),
# it is safe to use the host copy when cross-compiling for arm64-v8a.
#
# We copy only the Vulkan .hpp files into a build-local directory and add it
# as a plain user include (-I).  User -I paths are searched before the NDK
# sysroot, so the cross-compiler reliably finds <vulkan/vulkan.hpp> without
# polluting the search path with the entire host /usr/include.
# If vulkan.hpp is absent (local build without libvulkan-dev), we fall back by
# disabling the Vulkan backend so the rest of the build (OpenCL + CPU) succeeds.
if(ANDROID)
    set(VULKAN_HPP_SEARCH_PATHS
        "/usr/include/vulkan/vulkan.hpp"
        "$ENV{VULKAN_SDK}/Include/vulkan/vulkan.hpp"
        "C:/VulkanSDK/1.4.341.1/Include/vulkan/vulkan.hpp"
        "C:/VulkanSDK/1.3.290.0/Include/vulkan/vulkan.hpp"
    )

    set(FOUND_VULKAN_HPP_PATH "")
    foreach(PATH IN LISTS VULKAN_HPP_SEARCH_PATHS)
        if(EXISTS "${PATH}")
            set(FOUND_VULKAN_HPP_PATH "${PATH}")
            break()
        endif()
    endforeach()

    if(NOT "${FOUND_VULKAN_HPP_PATH}" STREQUAL "")
        message(STATUS "[ARIA] vulkan.hpp found at ${FOUND_VULKAN_HPP_PATH} — staging Vulkan C++ headers into build-local directory")
        get_filename_component(VULKAN_HPP_DIR "${FOUND_VULKAN_HPP_PATH}" DIRECTORY)
        # IMPORTANT: include_directories(BEFORE SYSTEM ...) generates -isystem which
        # the NDK cross-compiler's --sysroot overrides (NDK sysroot /usr/include is
        # searched first and it only has vulkan.h, not vulkan.hpp).
        # Fix: copy ONLY the Vulkan .hpp files to a clean build-local dir and add
        # it as a plain user include (-I, no SYSTEM keyword).  User -I paths are
        # always searched before the sysroot, so the cross-compiler finds vulkan.hpp.
        file(COPY "${VULKAN_HPP_DIR}/"
             DESTINATION "${CMAKE_BINARY_DIR}/vulkan_hpp_include/vulkan"
             FILES_MATCHING PATTERN "*.hpp")
        include_directories(BEFORE "${CMAKE_BINARY_DIR}/vulkan_hpp_include")

        # Stage SPIRV-Headers (the unified1 C++ enum wrappers) the same way.
        # ggml-vulkan.cpp:28 includes "spirv/unified1/spirv.hpp", which lives in
        # the apt 'spirv-headers' package at /usr/include/spirv/ or in Vulkan SDK.
        set(SPIRV_HPP_SEARCH_PATHS
            "/usr/include/spirv/unified1/spirv.hpp"
            "$ENV{VULKAN_SDK}/Include/spirv/unified1/spirv.hpp"
            "C:/VulkanSDK/1.4.341.1/Include/spirv/unified1/spirv.hpp"
            "C:/VulkanSDK/1.3.290.0/Include/spirv/unified1/spirv.hpp"
        )
        set(FOUND_SPIRV_HPP_PATH "")
        foreach(PATH IN LISTS SPIRV_HPP_SEARCH_PATHS)
            if(EXISTS "${PATH}")
                set(FOUND_SPIRV_HPP_PATH "${PATH}")
                break()
            endif()
        endforeach()

        if(NOT "${FOUND_SPIRV_HPP_PATH}" STREQUAL "")
            message(STATUS "[ARIA] spirv-headers found at ${FOUND_SPIRV_HPP_PATH} — staging into build-local directory")
            get_filename_component(SPIRV_HPP_BASE_DIR "${FOUND_SPIRV_HPP_PATH}" DIRECTORY)
            get_filename_component(SPIRV_HPP_BASE_DIR "${SPIRV_HPP_BASE_DIR}" DIRECTORY) # up one to 'spirv'
            file(COPY "${SPIRV_HPP_BASE_DIR}/"
                 DESTINATION "${CMAKE_BINARY_DIR}/spirv_include/spirv")
            include_directories(BEFORE "${CMAKE_BINARY_DIR}/spirv_include")
        else()
            message(WARNING "[ARIA] spirv/unified1/spirv.hpp not found — Vulkan backend will fail to compile")
        endif()
    else()
        message(WARNING "[ARIA] vulkan/vulkan.hpp not found — disabling Vulkan backend")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

# ─── Add llama.cpp as a static library ───────────────────────────────────────
add_subdirectory(${LLAMA_DIR} llama_build)

# ─── mtmd: multimodal (vision) library ───────────────────────────────────────
# mtmd is under tools/mtmd/ and is NOT built by add_subdirectory(llama_dir)
# unless LLAMA_BUILD_TOOLS=ON (which requires LLAMA_STANDALONE). We add it
# directly so the 'mtmd' CMake target is available for linking.
# mtmd exposes its own PUBLIC include path (tools/mtmd/) so we do NOT need to
# manually add it to target_include_directories below.
#
# LLAMA_INSTALL_VERSION is set inside llama.cpp's own root CMakeLists.txt scope
# and does NOT propagate back to this parent scope (CMake subdirectory scoping).
# tools/mtmd/CMakeLists.txt:40 calls set_target_properties(... VERSION
# ${LLAMA_INSTALL_VERSION} ...) which silently expands to an empty string here,
# causing CMake to see malformed arguments and abort with "incorrect number of
# arguments". Define a fallback so the property call is always well-formed.
if(NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()
add_subdirectory(${LLAMA_DIR}/tools/mtmd mtmd_build)

# ─── Android log library ─────────────────────────────────────────────────────
find_library(LOG_LIB log)

# ─── Our JNI bridge shared library ───────────────────────────────────────────
# Two source files:
#   llama_jni.cpp  — wraps llama.cpp for LLM inference
#   aria_math.cpp  — NEON SIMD math for EmbeddingEngine + PolicyNetwork
add_library(
    llama-jni
    SHARED
    llama_jni.cpp
    aria_math.cpp
    aria_crash_handler.cpp
)

target_include_directories(llama-jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/ggml/src
)

# NOTE: 'common' target is produced by llama.cpp's own CMake when
# LLAMA_BUILD_COMMON=ON (set above). The include path for common/ headers is
# already listed in target_include_directories above.
target_link_libraries(llama-jni
    llama           # llama.cpp core
    llama-common    # llama.cpp common utilities (renamed from 'common' in b6800+)
    mtmd            # multimodal: CLIP image encoder + projection layer
    ${LOG_LIB}      # __android_log_print
)

# ─── Compiler flags ──────────────────────────────────────────────────────────
target_compile_options(llama-jni PRIVATE
    -O3                  # Maximum optimization
    -march=armv8-a       # Exynos 9611 is ARMv8-A
    # NOTE: -mfpu=neon is intentionally omitted. It is a 32-bit ARM flag only.
    # On arm64-v8a (AArch64), NEON is always available and requires no flag.
    # NDK r23+ rejects -mfpu=neon on AArch64 with a compiler error.
    -ffast-math          # Aggressive float optimization for inference
    -fno-finite-math-only # ggml/vec.h rejects __FINITE_MATH_ONLY__ (set by -ffast-math); override it
    -funroll-loops
)

# ─── On-device LoRA training via llama_opt_* API ─────────────────────────────
# ENABLED: LLAMA_HAS_TRAINING is defined. The JNI function nativeTrainLora()
# in llama_jni.cpp is compiled with the real llama_opt_* / ggml_opt_* code path.
#
# The build uses the vendored llama.cpp submodule at android/app/src/main/cpp/llama.cpp/.
# The llama_jni.cpp training block is guarded by #if defined(LLAMA_HAS_TRAINING)
# so removing this line is safe and causes a graceful fallback:
#   → nativeTrainLora() returns JNI_FALSE
#   → LoraTrainer.kt calls stubTrainLora() — writes metadata-only .bin
#   → No inference regression
#
# If the vendored llama.cpp version does not export these symbols, the linker
# will fail at build time (not at runtime). Remove the definition below to
# disable training and get a guaranteed clean build, then re-enable once the
# API surface is verified.
add_compile_definitions(LLAMA_HAS_TRAINING)
