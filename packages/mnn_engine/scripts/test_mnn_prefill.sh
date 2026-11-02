#!/usr/bin/env bash
set -euo pipefail

plugin_root="${1:?Plugin root is required}"
model_config="${2:?An existing MNN model config.json is required (nothing is downloaded)}"
cmake_bin="${MNN_CMAKE:-${HOME}/.local/share/mnn_engine/toolchains/cmake-3.22.1/bin/cmake}"
workspace_id="$(printf '%s' "${plugin_root}" | sha256sum | cut -c1-12)"
build_dir="${HOME}/.cache/mnn_engine/${workspace_id}/prefill-host"
test_binary="${build_dir}/mnn_prefill_integration_test"

"${cmake_bin}" -S "${plugin_root}/MNN" -B "${build_dir}" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PROJECT_MNN_INCLUDE="${plugin_root}/scripts/cmake/mnn_log_bridge.cmake" \
    -DMNN_ENGINE_LOG_BRIDGE=OFF -DMNN_ENGINE_TOKENIZER_ADDED_TOKENS=ON \
    -DMNN_ENGINE_PREFILL_CANCELLATION=ON \
    -DMNN_BUILD_SHARED_LIBS=ON -DMNN_SEP_BUILD=OFF \
    -DMNN_BUILD_LLM=ON -DMNN_BUILD_LLM_OMNI=ON \
    -DMNN_LOW_MEMORY=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
    -DMNN_BUILD_OPENCV=ON -DMNN_IMGCODECS=ON -DMNN_BUILD_AUDIO=ON \
    -DMNN_KLEIDIAI=OFF -DMNN_OPENCL=OFF -DMNN_VULKAN=OFF -DMNN_HEXAGON=OFF \
    -DMNN_BUILD_TEST=OFF -DMNN_BUILD_BENCHMARK=OFF
"${cmake_bin}" --build "${build_dir}" --target MNN --parallel "${MNN_BUILD_JOBS:-4}"
"${CXX:-g++}" -std=c++17 -Wall -Wextra \
    -I"${plugin_root}/android/src/main/cpp" \
    -isystem "${plugin_root}/MNN/include" -isystem "${plugin_root}/MNN/transformers/llm/engine/include" \
    "${plugin_root}/test/native/mnn_prefill_integration_test.cpp" \
    -L"${build_dir}" -Wl,-rpath,"${build_dir}" -lMNN -o "${test_binary}"
"${test_binary}" "${model_config}"
