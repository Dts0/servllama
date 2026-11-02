# Native regression tests

Run in Linux/WSL with the pinned MNN submodule initialized:

```bash
bash scripts/test_mnn_llm_adapter.sh "$PWD"
bash scripts/test_mnn_tokenizer.sh "$PWD"
```

The first command checks the model-format guard, incremental generation,
prefill cancellation and reuse, model chunk defaults, failure handling, bounded
native log storage, and the real MNN log macros with Android logging suppressed.
LLM execution is stubbed; this does not measure
device inference or GPU/NPU numerical accuracy.

The tokenizer test builds the upstream tokenizer with the same compatibility
patch used in `libMNN.so`, then checks single-token decoding against a generated
MTOK fixture, including thinking/tool markers and Unicode added tokens. It does
not download a model. Both commands write only under `.native/tests/`.

For a real-model CPU regression on Linux/WSL, provide an existing model:

```bash
bash scripts/test_mnn_prefill.sh "$PWD" /path/to/Qwen3-0.6B-MNN/config.json
```

This builds MNN with the same tokenizer and prefill integrations in the local
build cache. It compares short/long greedy outputs with and without chunking,
cancels before prefill, after 128 tokens, and at the last chunk, then verifies
that the next request matches a clean generation. It downloads nothing and
does not replace the packaged Android libraries. This is a CPU correctness
check; Android GPU and image-input acceptance require device testing.

The same C++ fixture accepts an optional `cpu`, `opencl`, or `vulkan` argument
when cross-compiled with the Android NDK and linked to the packaged `libMNN.so`.
Run it from a writable device test directory. GPU runs disable tuning so this
checks correctness, not throughput, and keep OpenCL/Vulkan caches in separate
`.mnn-prefill-test-<backend>` directories. The Android app's normal tuning
settings and HTTP/MethodChannel cancellation should be checked separately.

For multimodal models, pass an image after the backend:

```bash
./mnn_prefill_integration_test /path/to/model/config.json cpu /path/to/apple.jpg
./mnn_prefill_integration_test /path/to/model/config.json vulkan /path/to/apple.jpg --chunked-only
```

Image cases exercise mRoPE/deep-stack inputs and generate up to 32 tokens.
The default compares `chunk=0` against `chunk=128`. `--chunked-only` instead
uses `chunk=128` for both the reference and subsequent requests: it still
requires identical repeated output and identical output after each cancellation.
This separates cancellation/reuse checks from numerical changes caused by
different prefill shapes. Use a model copy with high precision for a stricter
CPU comparison across chunk sizes; low precision or GPU execution need not
produce the same greedy tokens across those sizes.

See [the SM8475 device report](../../doc/PREFILL_CANCELLATION_DEVICE_ACCEPTANCE_ZH.md)
for results, reproduction commands, and the unresolved OpenCL low-precision
image repeatability failure. `--chunked-only` does not bypass that failure.

One-off Hexagon device investigation tools are outside the maintained test
suite. The findings and reproduction conditions remain in
`doc/HEXAGON_8_ELITE_INVESTIGATION_ZH.md`.
