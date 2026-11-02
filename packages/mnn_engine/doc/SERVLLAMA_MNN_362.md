# ServLlama MNN 3.6.2 integration

This vendored source preserves the `mnn_engine` **0.2.0** Flutter/Kotlin API and
upgrades its native runtime from MNN 3.6.1 to **3.6.2**. It is not a new upstream
wrapper release. `native/source-pin.json` identifies the pub.dev wrapper archive,
its SHA-256, and the official annotated MNN release tag and peeled commit.

- Official release: <https://github.com/alibaba/MNN/releases/tag/3.6.2>
- MNN commit: `7e3c6fb5225d3ccf22807ee641332669df34650d`
- Wrapper archive SHA-256: `aaeb9ae14c4d7b8669a6a017e25d85a0248ee1e73751a7378a28ae9f803ca977`
- Build: Android ARM64, API 28, NDK 27.0.12077973, CMake 3.22.1, static libc++.
- Enabled backends remain CPU, OpenCL, Vulkan buffer. MNN Hexagon and QNN remain
  disabled, as in the original common package. No Qualcomm SDK is downloaded.

## Why this is a source rebuild

The wrapper requires native adapter ABI 8 and application-owned native log and
prefill cancellation exports. A stock official MNN binary is not a drop-in
replacement for those custom exports. The official 3.6.2 Android ZIP was
checksum-verified, and none of its nine ARM64 libraries export the required
log/cancellation functions; see `native/official-binary-compatibility.json`.
The native build keeps the official MNN
checkout unchanged and applies its existing integration edits to build copies.
`native/android-arm64-v8a.json` is generated after successful build and packaging;
it records the actual version, toolchain, flags, Build IDs, sizes and SHA-256.

## Compatibility review

The tokenizer added-token and cancellation anchors still apply to 3.6.2.
Upstream Omni mRoPE now supports variable axis counts and relative spatial
positions. The compatibility patch was adapted to that implementation: each
chunk indexes the full multimodal position arrays, temporal/non-relative axes
add the prefill start position, and relative spatial axes keep zero offset.
Three- and four-axis behavior, subsequent chunks, existing history, fallback
positions and source-anchor failure are covered by model-free regression tests.
The log bridge, tokenizer literal-token preservation, chunk cancellation,
multimodal embedding splitting and deep-stack slicing are retained.

Historical `doc/*3.6.1*` investigations and acceptance notes are inherited from
the wrapper. They are **not evidence of device/model validation for 3.6.2**.

## Rebuild

Use an already-installed Android SDK and accept any needed SDK terms separately.
No model weights or new SDK components are downloaded by this build script.

```bash
export ANDROID_HOME=/path/to/android-sdk
# The source checkout is ignored and recreated from the exact verified tag.
bash packages/mnn_engine/scripts/prepare_mnn_source.sh
bash packages/mnn_engine/scripts/build_servllama_mnn.sh
```

The second script defaults to three build workers, verifies the exact clean MNN
commit, builds MNN and the JNI adapter, strips/package-copies the libraries,
checks exported JNI/custom integration symbols and 16 KiB ELF LOAD alignment,
and writes the manifest. A complete APK still needs the host Flutter build.
Downloaded `MNN/`, `.native/`, cache files and `*.so` outputs are not source files.

## Model-free tests

```bash
export MNN_CMAKE="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
plugin="$PWD/packages/mnn_engine"
bash "$plugin/scripts/test_mnn_llm_adapter.sh" "$plugin"
bash "$plugin/scripts/test_mnn_tokenizer.sh" "$plugin"
python3 "$plugin/scripts/test_mnn_362_compat.py"
```

These tests exercise the adapter with stubs, native log integration, tokenizer
fixtures and generated position code. They do **not** establish real MNN model
inference, numerical equivalence, multimodal correctness, cancellation latency,
or physical CPU/GPU throughput. Those require a stable ARM64 Android device and
appropriate models. No speed measurements are invented from static inspection.
