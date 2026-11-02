#!/usr/bin/env bash
set -euo pipefail
plugin_root="$(realpath "${1:?Plugin root is required}")"
fixture="${plugin_root}/.native/tests/tokenizer_added_tokens.mtok"
mkdir -p "$(dirname "${fixture}")"
# Minimal public MTOK fixture: ordinary BPE vocabulary plus protocol/text
# tokens outside it. No downloaded model or proprietary tokenizer is needed.
python3 - "${fixture}" <<'PY'
import pathlib
import struct
import sys

def string(value):
    data = value.encode("utf-8")
    return struct.pack("<H", len(data)) + data

data = bytearray(b"430 4\n1 1 0\n15 15\n")
data += bytes([0, 0])  # no normalizer or pre-tokenizer
data += struct.pack("<BIBBI", 0, 2, 0, 0, 0)  # BPE, 2 tokens, no merges
for token_id, token in enumerate(["a", "b"]):
    data += string(token) + struct.pack("<I", token_id)
data += bytes([0])  # no decoder
added = ["<think>", "</think>", "<tool_call>", "</tool_call>", "<测试▁Ġ>", "<eos>"]
data += struct.pack("<I", len(added))
for token_id, token in enumerate(added, 10):
    data += struct.pack("<IBBB", token_id, int(token == "<eos>"), 0, 0) + string(token)
pathlib.Path(sys.argv[1]).write_bytes(data)
PY
source_dir="${plugin_root}/MNN/transformers/llm/engine/src/tokenizer"
source_file="${plugin_root}/.native/tests/tokenizer_compat/tokenizer.cpp"
cmake_bin="${MNN_CMAKE:-${HOME}/.local/share/mnn_engine/toolchains/cmake-3.22.1/bin/cmake}"
"${cmake_bin}" -DMNN_TOKENIZER_SOURCE="${source_dir}/tokenizer.cpp" \
    -DMNN_TOKENIZER_OUTPUT="${source_file}" \
    -P "${plugin_root}/scripts/cmake/mnn_tokenizer_compat.cmake"
"${CXX:-g++}" -std=c++17 -O1 \
    -I"${plugin_root}/MNN/include" -I"${plugin_root}/MNN/source" -I"${source_dir}" \
    "${plugin_root}/test/native/mnn_tokenizer_test.cpp" \
    "${source_file}" "${source_dir}/unicode.cpp" "${source_dir}/unicode_data.cpp" \
    "${plugin_root}/MNN/source/core/AutoTime.cpp" -o "${plugin_root}/.native/tests/mnn_tokenizer_test"
"${plugin_root}/.native/tests/mnn_tokenizer_test" \
    "${fixture}" a ab '<think>' '</think>' '<tool_call>' '</tool_call>' '<测试▁Ġ>' '<eos>'
