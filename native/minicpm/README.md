# MiniCPM CPU HTTP runtime

A separate executable, **not** a replacement for the official llama.cpp runtime.
Pinned upstream: `tc-mb/llama.cpp-omni` commit
`e0eca9d8f57ac8f5a5ad6191a8007b93436c1e71`.
Local changes are in `../../patches/minicpm/servllama-http.patch`; the executable
entry point is `main.cpp`. The upstream fork has no built `llama-server` target;
this entry point uses its existing `server-context`, HTTP transport and handlers.

## Build

Install CMake >=3.14, a C++17 compiler, and Android NDK `27.0.12077973`.

```sh
native/minicpm/build.sh host
ANDROID_NDK_HOME=/path/to/android-ndk-r27 native/minicpm/build.sh android
```

`MINICPM_BUILD_ROOT` changes the build/cache directory (default `.minicpm-build`).
Android output: `.minicpm-build/build-android/bin/libminicpm-server.so`.
The file is a PIE **executable named .so**, launched by the app's native process
runner, not loaded via JNI. It targets Android28 ARM64, ARMv8-A, CPU only,
16KiB ELF segment alignment. Internal llama/ggml/mtmd/omni and libc++ libraries
are static. Only Android system libc, libm and libdl are dynamically needed.
No official runtime library files are changed. The Android build automatically invokes `package_android.py` to verify
system-only dependencies and 16KiB alignment, strip/copy the delivery binary,
and regenerate the bundled license and manifest SHA256/size. The unstripped
build output is retained for debugging. To package an existing build:

```sh
python3 native/minicpm/package_android.py .minicpm-build --ndk /path/to/android-ndk-r27
```

## Runtime contract

Existing llama.cpp common CLI parsing is retained, including context, batch,
ubatch, threads, parallel slots, reasoning, Jinja, flash-attention, mmap,
API key and image token limits. CPU mode requires `--gpu-layers 0`; auto maps
to CPU, while explicit unsupported GPU values fail instead of being ignored.
The app passes `--device none`. There are no new context/batch/parallel hard caps.
Model memory and upstream architecture constraints still apply.

LES model arguments:

```text
--model /bundle/MiniCPM-V-4_7-35B-A3B-mix_q3gud_im-les.gguf
--moe-experts /bundle/experts
--moe-slot-bank 256
--moe-cache-window 0
--mmproj /bundle/mmproj-model-f16.gguf  # optional image input
```

`--moe-cache-window N` sets upstream `LLAMA_MOE_DECODE_WINDOW` before model
initialization. Larger values consume additional RAM; zero disables this cache.
It is **not** the KV prompt-cache hit count. The known CPU alloc-only buffer
adapter and portable `F_NOCACHE` guards enable LES SSD routed experts on CPU.
Upstream's duplicate omni/mtmd global logger names were disambiguated for static
Android linking; no duplicate-symbol linker override is used.

Exposed routes: health, props, models, slots, metrics; OpenAI chat/completions
and completions (streaming/non-streaming); tokenize, detokenize, apply-template,
embeddings. `/metrics` follows upstream enable flag. `/health` and `/models`
(and `/v1` aliases) are public upstream routes even when an API key is set;
other registered routes require the key. Default host remains upstream loopback.
The app may expose a listener deliberately; this build has no TLS and should
not be placed on an untrusted network without a trusted TLS proxy.

Request body limit is 32MiB (including base64 image text). No web UI is bundled.
Request/response, generated-token, schema, parsed-message and API-key logging
was removed from the linked HTTP path. Route/status and numeric timings remain.
Signals stop accepting requests, mark readers cancelled, wait for HTTP handlers,
then stop inference. Model loading cannot be interrupted immediately: the app
must retain its bounded timeout / force-kill fallback while loading.

## Interface compatibility

The fork's existing handlers implement OpenAI `messages`, `stream`,
`stream_options.include_usage`, normal usage and llama.cpp `timings`.
`prompt_tokens_details.cached_tokens` is actual KV prompt reuse.
`/props.default_generation_settings.n_ctx` is the actual **per-slot** context,
not total across slots or model training maximum. Unknown values must remain
unknown in the UI; do not estimate context occupancy or cache hits.

Vision uses the same OpenAI `image_url` content part (data URI) and `--mmproj`;
it was exercised successfully on this exact model. The unrelated omni WebSocket,
video and streaming speech/TTS protocols are not exposed or claimed supported.
The linked fork reports this model as vision=true, audio=false.

## Verification performed (Linux x86-64 CPU, not an Android device)

Official pinned LES43 asset bundle, revision
`7a7ab2ac8f045d53574ead3324b97e709d0e318b`, all43 sizes and SHA256 verified,
16,623,585,508 bytes. Quantization is **mix_q3gud_im**, full model top-k8;
no reduced-top-k shortcut was used.

- Text: context4096, batch32, ubatch32, slots1, threads4, cache-window0.
  SSE terminates with `[DONE]`; usage18 prompt/10 completion tokens.
  First cached0; repeat cached14. Native timings first prefill13.03tok/s,
  decode7.48tok/s; repeat17.32/9.42tok/s. These are short synthetic prompt
  measurements on the cloud CPU, **not phone or sustained-prefill promises**.
- Disconnect during streaming, then subsequent request: succeeds.
- Slots2, total context8192, batch64: props reports4096 per slot.
- Image:64x64 red PNG via OpenAI image_url returns `Red`; usage90in/2out.
- API key: protected route rejects missing key with401.
- SIGTERM with two active streams and a third queued request: all three tasks
  cancelled and process exits0, without HTTP/inference join deadlock.
- Android NDK27 cross-build succeeds; stripped ARM64 executable9,059,528bytes,
  all LOAD segments0x4000 aligned, no third-party shared dependencies.

`smoke_extended.py` exercises the synthetic image, authenticated slots and
SIGTERM during concurrent/queued streams. It intentionally terminates the owned
test server; set `SERVER_PID` and use only a disposable local test process.

`smoke_http.py` reproduces text/SSE/cache/cancellation tests against an already
running local server. `download_test_model.py` downloads and verifies the pinned
catalog for testing; weights are not part of APK/source artifacts.
Android emulator/device inference and phone memory/speed remain untested.
