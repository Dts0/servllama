#!/usr/bin/env bash
set -euo pipefail

plugin_root="${1:?Plugin root is required}"
test_dir="${plugin_root}/.native/tests"
mkdir -p "${test_dir}"
"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror \
    -I"${plugin_root}/android/src/main/cpp" \
    -isystem "${plugin_root}/MNN/schema/current" \
    -isystem "${plugin_root}/MNN/3rd_party/flatbuffers/include" \
    -isystem "${plugin_root}/MNN/apps/frameworks/3rd_party/include" \
    "${plugin_root}/android/src/main/cpp/mnn_hexagon_model_check.cpp" \
    "${plugin_root}/test/native/mnn_hexagon_model_check_test.cpp" \
    -o "${test_dir}/mnn_hexagon_model_check_test"
"${test_dir}/mnn_hexagon_model_check_test"

"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -pthread \
    -I"${plugin_root}/test/native/stubs" \
    -I"${plugin_root}/android/src/main/cpp" \
    -I"${plugin_root}/MNN/apps/frameworks/3rd_party/include" \
    "${plugin_root}/android/src/main/cpp/mnn_llm_session_adapter.cpp" \
    "${plugin_root}/android/src/main/cpp/mnn_prefill_control.cpp" \
    "${plugin_root}/android/src/main/cpp/mnn_native_diagnostics.cpp" \
    "${plugin_root}/test/native/mnn_llm_session_adapter_test.cpp" \
    -o "${test_dir}/mnn_llm_session_adapter_test"
"${test_dir}/mnn_llm_session_adapter_test"

"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -pthread \
    -I"${plugin_root}/android/src/main/cpp" \
    "${plugin_root}/android/src/main/cpp/mnn_native_diagnostics.cpp" \
    "${plugin_root}/test/native/mnn_native_diagnostics_test.cpp" \
    -o "${test_dir}/mnn_native_diagnostics_test"
"${test_dir}/mnn_native_diagnostics_test"

"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror -pthread \
    -DMNN_ENGINE_LOG_BRIDGE_TEST -DMNN_USE_LOGCAT \
    -I"${plugin_root}/test/native/stubs" \
    -I"${plugin_root}/android/src/main/cpp" -I"${plugin_root}/MNN/include" \
    "${plugin_root}/android/src/main/cpp/mnn_log_bridge.cpp" \
    "${plugin_root}/android/src/main/cpp/mnn_native_diagnostics.cpp" \
    "${plugin_root}/test/native/mnn_log_bridge_test.cpp" \
    -Wl,--wrap=__android_log_print -ldl -o "${test_dir}/mnn_log_bridge_test"
"${test_dir}/mnn_log_bridge_test"
