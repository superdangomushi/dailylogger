cmake_minimum_required(VERSION 3.10)
project(whisper-jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# Path to the vendored whisper.cpp git submodule.
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# whisper.cpp の新しい ggml CMake を使わず、必要なソースを直接コンパイルして
# 単一の libwhisper-jni.so にまとめる（Android example と同じ構成）。
add_library(
    whisper-jni
    SHARED
    ${CMAKE_SOURCE_DIR}/jni.c
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp
)

target_include_directories(
    whisper-jni
    PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

target_compile_definitions(whisper-jni PRIVATE GGML_USE_CPU)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper-jni PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_link_options(whisper-jni PRIVATE -Wl,--gc-sections)
endif ()

find_library(LOG_LIB log)
target_link_libraries(whisper-jni ${LOG_LIB} android)
