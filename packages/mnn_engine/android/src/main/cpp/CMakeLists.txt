cmake_minimum_required(VERSION 3.22.1)
project(mnn_engine_jni LANGUAGES CXX)

if(NOT DEFINED MNN_ROOT OR NOT EXISTS "${MNN_ROOT}/include/MNN/Interpreter.hpp")
    message(FATAL_ERROR "MNN_ROOT is invalid: ${MNN_ROOT}")
endif()
if(NOT DEFINED MNN_LIBRARY OR NOT EXISTS "${MNN_LIBRARY}")
    message(FATAL_ERROR "MNN_LIBRARY is invalid: ${MNN_LIBRARY}")
endif()

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${MNN_LIBRARY}")

add_library(mnn_engine_jni SHARED
    mnn_engine_jni.cpp
    mnn_backend_support.cpp
    mnn_llm_session_adapter.cpp
    mnn_hexagon_model_check.cpp
    mnn_native_diagnostics.cpp
)
target_compile_features(mnn_engine_jni PRIVATE cxx_std_17)
target_compile_definitions(mnn_engine_jni PRIVATE
    MNN_COMMIT="${MNN_COMMIT}"
    MNN_ENGINE_OPENCL=$<BOOL:${MNN_OPENCL}>
    MNN_ENGINE_VULKAN=$<BOOL:${MNN_VULKAN}>
    MNN_ENGINE_HEXAGON=$<BOOL:${MNN_HEXAGON}>
)
target_include_directories(mnn_engine_jni PRIVATE
    "${MNN_ROOT}/include"
    "${MNN_ROOT}/transformers/llm/engine/include"
    "${MNN_ROOT}/apps/frameworks/3rd_party/include"
    "${MNN_ROOT}/schema/current"
    "${MNN_ROOT}/3rd_party/flatbuffers/include"
)
target_link_libraries(mnn_engine_jni PRIVATE MNN log android dl)
target_link_options(mnn_engine_jni PRIVATE "-Wl,-z,max-page-size=16384")
