## 0.2.0

- Keep HTTP request ownership inside the server. Clients cancel their own transport; server-generated request IDs remain internal.
- Signal native cancellation when an HTTP coroutine is cancelled or an SSE write fails. Emit SSE comment heartbeats during prefill and buffered tool output, and keep token buffering bounded.
- Join synchronous JNI work before releasing request admission or staged media. Keep one active generation and HTTP 429 for contention; there is no generation queue.
- Make `stopServer` wait for admitted native work to return. A drain timeout retains ownership and prevents restart/unload until stopping is retried successfully. Service teardown also observes the native-drain guard.
- Keep `cancelGeneration` as a signal for the current native generation, and retain the Android special-use foreground service type. Native ABI and the pinned MNN revision are unchanged.
- TCP half-close alone is not proof of cancellation. HTTP disconnect handling is best effort; only successful stop and unload establish that local resources are released. See `doc/HTTP_CANCELLATION_ZH.md` for the contract and validation boundary.

## 0.1.1

- Add mmap, precision, and generation thread options to `MnnLoadOptions`, and APIs to inspect or clear mmap/GPU runtime caches.

## 0.1.0

- Upgrade the pinned MNN source and bundled Android libraries to 3.6.1.
- Add CPU/OpenCL/Vulkan backend selection, capability checks and resident backend reporting.
- Build Vulkan buffer by default; retain opt-in Hexagon host/DSP builds and deployment.
- Report unavailable backends without silently retrying on CPU; separate model caches by backend and version.
- Share native event streams between multiple Dart listeners.
- Extend GitHub Actions with optional Hexagon SDK builds, complete native bundles, failure logs and independent example APK verification.
- Add a pinned Snapdragon Docker build using Hexagon SDK 6.6, a Community SDK domain adapter, toolchain provenance and DSP dependency/symbol validation.
- Bundle v73/v75/v79/v81 DSP runtimes behind one ARM64 stub, query the device ISA with FastRPC, and extract only the matching runtime. Report the detected architecture to consumers.
- Default GitHub Actions and local builds to CPU/OpenCL/Vulkan; Hexagon requires explicit opt-in, including for its host backend. Tag builds use the default CPU/GPU bundle.
- Upgrade JNI adapter ABI to 8, keep bounded native error context through a direct MNN log bridge, and remove temporary logger hooks, Logcat fallback and verbose diagnostic dumps.
- Preserve MNN 3.6.1 MTOK added-token decoding, generation error checks and the experimental Hexagon model-format guard; maintain native/tokenizer regression tests independently of device investigation tools.
- Release failed generation sessions and clear resident-model state so loading the same model rebuilds the runtime; preserve reuse after normal completion, length limits and cancellation.
- Reject new or pending generation requests when server shutdown begins, without queueing them or reopening admission when an old request finishes.
- Check cancellation between prefill chunks (128 tokens by default for standard causal models), preserve explicit model chunk settings and stops received before native entry, and align multimodal positions and deep-stack inputs with chunked embeddings.

- Align multi-turn tool-call messages and completion responses with `llama-server`.
- Support both JSON and tagged-parameter MNN tool-call output formats.

## 0.0.2

- Use the imported model directory name as the MNN runtime and API model ID.
- Add model-directory renaming with case-insensitive duplicate-name checks.
- Add import-result APIs with optional automatic conflict-free model naming.

## 0.0.1

- Bundle verified Android arm64 native libraries for pub.dev consumers.
- Stop compiling MNN from consumer Gradle builds.
- Use the Ktor CIO server engine to avoid Netty packaging conflicts in host applications.
- Add reproducible GitHub Actions native builds and public artifact metadata.
- Add bilingual package documentation and pub.dev publishing guidance.
- Make server binding authoritative, reuse safe local ports, and report real
  bind conflicts without a race-prone preflight in the main startup path.
- Add Android arm64 MNN 3.6.0 native build and JNI runtime.
- Add complete model directory import and validation.
- Add foreground Ktor OpenAI-compatible server and SSE chat completions.
- Add runtime state and independent log event streams.
