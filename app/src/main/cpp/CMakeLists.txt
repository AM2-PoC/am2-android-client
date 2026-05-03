cmake_minimum_required(VERSION 3.22.1)

project(opus_jni)

# ===============================
# OPUS DIR
# ===============================
set(OPUS_DIR ${CMAKE_SOURCE_DIR}/opus-1.5.2)

# ===============================
# OPUS SOURCE
# ===============================
file(GLOB OPUS_SRC
        ${OPUS_DIR}/src/*.c
        ${OPUS_DIR}/celt/*.c
        ${OPUS_DIR}/silk/*.c
        ${OPUS_DIR}/silk/fixed/*.c
)

# Filter out demo/test files that contain 'main' functions
list(FILTER OPUS_SRC EXCLUDE REGEX ".*_demo\\.c$")
list(FILTER OPUS_SRC EXCLUDE REGEX ".*/opus_demo\\.c$")
list(FILTER OPUS_SRC EXCLUDE REGEX ".*/opus_compare\\.c$")
list(FILTER OPUS_SRC EXCLUDE REGEX ".*/repacketizer_demo\\.c$")

# ===============================
# JNI LIB
# ===============================
add_library(
        opus_jni
        SHARED
        opus_jni.cpp
        ${OPUS_SRC}
)

# Include directories
target_include_directories(opus_jni PRIVATE
        ${OPUS_DIR}/include
        ${OPUS_DIR}/celt
        ${OPUS_DIR}/silk
        ${OPUS_DIR}/silk/fixed
)

# Compile definitions
target_compile_definitions(opus_jni PRIVATE
        OPUS_BUILD
        FIXED_POINT
        DISABLE_FLOAT_API
        VAR_ARRAYS
)

# Optimization
target_compile_options(opus_jni PRIVATE -O3)

find_library(
        log-lib
        log
)

target_link_libraries(
        opus_jni
        ${log-lib}
)
