cmake_minimum_required(VERSION 3.22)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# --------------------------------------------------------------------------
# llama.cpp integration
#
# ggml derives GGML_SYSTEM_ARCH from the Android toolchain itself, so this file
# must not override it. KleidiAI and OpenMP are left off: KleidiAI is fetched
# from GitHub at configure time, which fails behind TLS-intercepting proxies,
# and the plain CPU backend is sufficient for the bundled models.
# --------------------------------------------------------------------------

# app/src/main/cpp -> app/src/main -> app/src -> app -> chatAI
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        jni_bridge.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
