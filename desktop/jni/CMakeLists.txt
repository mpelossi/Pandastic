# llm_jni.cpp (the phone's llama.cpp bridge) built for desktop Linux, for the Docker brain.
cmake_minimum_required(VERSION 3.22)
project(pandastic_llm_desktop C CXX)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Portable binary: not tuned to the build machine's CPU. x86_64: AVX2/FMA (Intel 2013+, AMD 2015+).
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    foreach(feature SSE42 AVX AVX2 FMA F16C)
        set(GGML_${feature} ON CACHE BOOL "" FORCE)
    endforeach()
endif()
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG v0.5.0
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

find_package(JNI REQUIRED)
add_library(pandastic_llm SHARED ${PANDASTIC_CPP}/llm_jni.cpp)
target_compile_features(pandastic_llm PRIVATE cxx_std_17)
target_include_directories(pandastic_llm PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${JNI_INCLUDE_DIRS})
target_link_libraries(pandastic_llm PRIVATE llama ggml)
