# On-device LLM (Qwen3.5-0.8B GGUF) through llama.cpp, used only to read SMS text into slots.
cmake_minimum_required(VERSION 3.22.1)
project(pandastic_llm C CXX)

# Always optimise: ggml at -O0 (the default for debug APKs) is many times too slow to answer an SMS.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

include(FetchContent)
# Only the core library: no tools, servers, tests or network code.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Cortex-A55 and newer (most 4 GB phones since 2019) have dot-product and fp16 instructions.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
if(ANDROID_ABI STREQUAL "x86_64")
    # Emulator only. The default Android emulator CPU exposes SSE4.2, AVX and F16C but not AVX2/FMA.
    set(GGML_SSE42 ON CACHE BOOL "" FORCE)
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
    set(GGML_FMA OFF CACHE BOOL "" FORCE)
endif()

if(NOT PANDASTIC_NO_LLM)
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG v0.5.0
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(pandastic_llm SHARED llm_jni.cpp)
target_compile_features(pandastic_llm PRIVATE cxx_std_17)
target_link_libraries(pandastic_llm PRIVATE llama ggml android log)
endif()

# Whisper has its own ggml version. Build its static libraries in a separate CMake
# project and hide their symbols to avoid collisions with the existing llama runtime.
include(ExternalProject)
FetchContent_Declare(whisper_source
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG 927cfce34f31707e17f2bff35c349632fb9e2c3a)
FetchContent_GetProperties(whisper_source)
if(NOT whisper_source_POPULATED)
    FetchContent_Populate(whisper_source)
endif()
set(speech_build "${CMAKE_CURRENT_BINARY_DIR}/whisper-build")
set(speech_arch_args)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND speech_arch_args -DGGML_CPU_ARM_ARCH=armv8-a)
elseif(ANDROID_ABI STREQUAL "x86_64")
    list(APPEND speech_arch_args -DGGML_SSE42=ON -DGGML_AVX=ON -DGGML_F16C=ON -DGGML_AVX2=OFF -DGGML_FMA=OFF)
endif()
set(speech_libraries
    "${speech_build}/src/libwhisper.a"
    "${speech_build}/ggml/src/libggml.a"
    "${speech_build}/ggml/src/libggml-cpu.a"
    "${speech_build}/ggml/src/libggml-base.a")
ExternalProject_Add(whisper_static
    SOURCE_DIR "${whisper_source_SOURCE_DIR}"
    BINARY_DIR "${speech_build}"
    CMAKE_ARGS
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_BACKEND_DL=OFF
        -DGGML_CPU_KLEIDIAI=OFF -DGGML_CPU_ALL_VARIANTS=OFF
        -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_CURL=OFF
        ${speech_arch_args}
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target whisper --parallel 4
    BUILD_BYPRODUCTS ${speech_libraries}
    INSTALL_COMMAND "")
add_library(pandastic_speech SHARED speech_jni.cpp)
add_dependencies(pandastic_speech whisper_static)
target_compile_features(pandastic_speech PRIVATE cxx_std_17)
target_include_directories(pandastic_speech PRIVATE "${whisper_source_SOURCE_DIR}/include" "${whisper_source_SOURCE_DIR}/ggml/include")
target_link_libraries(pandastic_speech PRIVATE ${speech_libraries} android log)
target_link_options(pandastic_speech PRIVATE -Wl,--exclude-libs,ALL -Wl,-Bsymbolic)
set_target_properties(pandastic_speech PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
