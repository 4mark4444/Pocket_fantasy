cmake_minimum_required(VERSION 3.14)
project("llama-android" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# ── llama.cpp build options ────────────────────────────────────────────────────
# Static link everything into a single .so — no extra .so files to deploy.
set(BUILD_SHARED_LIBS       OFF CACHE BOOL "" FORCE)

# Skip everything we don't need.
set(LLAMA_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER      OFF CACHE BOOL "" FORCE)

# Build common helpers (chat templates, samplers, tokenization utilities).
set(LLAMA_BUILD_COMMON      ON  CACHE BOOL "" FORCE)

# Cross-compiling: do NOT auto-detect host CPU features.
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)

# No dynamic backend loading (keeps the build simple; static CPU backend only).
set(GGML_BACKEND_DL         OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS   OFF CACHE BOOL "" FORCE)

# Disable optional heavy deps that complicate cross-compilation.
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL           OFF CACHE BOOL "" FORCE)

# ── Add llama.cpp ──────────────────────────────────────────────────────────────
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

# ── Our JNI shared library ─────────────────────────────────────────────────────
add_library(llama-android SHARED llama_jni.cpp)

target_include_directories(llama-android PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/ggml/src
    ${LLAMA_SRC}/common
)

target_link_libraries(llama-android
    llama
    llama-common
    android
    log
)
