# MiniCPM / LES experimental backend

This is a third, separately selected engine (`mini_cpm`), not a replacement for
the official llama.cpp runtime or MNN. It launches `libminicpm-server.so` with its
own bundled version manifest. The native build is CPU-only; official OpenCL /
Hexagon settings remain intact and are not applied to this engine.

The pinned LES bundle is represented as one `model_assets` record with a stable
UUID runtime identity. Its GGUF, projector, expert manifest and all expert files
must be present and verified before it is published as ready. Downloads use the
existing scheduler and resumable range/hash transfer. A partial bundle must never
appear as a runnable model, and deletion must remove only app-owned content.

Common llama.cpp launch settings are forwarded, including context, threads,
logical batch, parallel slots, flash attention and image token budget. Additional
LES settings expose physical microbatch, slot bank, expert-cache window and
reasoning. No 4K context or single-slot restriction is imposed. Invalid or
unsupported native combinations should report an error rather than silently
substitute smaller settings. Increasing any memory-related parameter may fail on
an actual phone; the 256K UI ceiling is **not** a claim that 256K fits in RAM.

The chat transport uses the existing OpenAI-compatible streaming API. Images use
its existing `image_url` request path and bundled mmproj; custom omni audio/video
interfaces are outside this integration. Device-level image operation still
requires native/device validation. Generation statistics keep the existing
backend-reported / estimated distinction. LES expert-cache statistics must not be
reported as prompt/KV cache hits. Context usage remains based on actual usage and
server capacity, showing `-` if either is unavailable rather than estimating.

Cancellation invalidates the operation generation, stops verification on chunk
boundaries, waits for an in-flight process launch to settle, and retains the
runtime lease until termination is confirmed. Models cannot be changed while the
published service is active.

## Android packaging and installation

The canonical source retains application ID `com.arkanefans.servllama`. For a
parallel experimental installation use the Gradle property
`miniCpmExperimental=true` (for example set
`ORG_GRADLE_PROJECT_miniCpmExperimental=true` before `flutter build apk --release
--target-platform android-arm64`). This produces
`com.arkanefans.servllama.minicpmexperimental`, labelled
`ServLlama MiniCPM（实验）`. It does not migrate the original application's private
data or require uninstalling that application. The CPU and Snapdragon packages
are alternatives with the same experimental application ID; install only the
variant supported by the device. The MiniCPM runtime is CPU-only in both.

The old APK signing private key was not available in this recovered environment.
Experimental deliverables therefore use a new local test certificate and cannot
upgrade the original signed app. Release builds use `android/key.properties`
when configured; otherwise the existing Gradle configuration falls back to debug
signing. Do not publish a test-signed APK as a production release. Never include
keystores, key properties, SSH keys or local toolchain/cache directories in source
or patch deliveries.

Native build instructions are in `native/minicpm/README.md`. Official llama.cpp
and MNN native libraries are preserved byte-for-byte and audited independently;
MiniCPM is a separately named executable with static inference dependencies.
Run `flutter analyze`, `flutter test` and `tool/verify_android_bundle.py` before
shipping an APK. Host tests do not establish Android SAF/provider behavior,
phone thermal stability, usable RAM or sustained model performance.

Android LES import uses the system directory picker (SAF), copies only the pinned
relative files into an app-owned staging directory, validates lengths during copy
and hashes before adoption, then renames rather than making a second full copy.
Allow at least one complete bundle's free space plus working reserve. Cancellation
is checked between chunks; an unresponsive document-provider read cannot be
interrupted instantly. Interrupted imports never become runnable models. Known
staging directories abandoned for over a day are cleaned on a subsequent import.

## Verification (2026-10-30, cloud Linux host)

- Flutter 3.35.2 / Dart 3.9.0, restored pinned project dependencies.
- `flutter analyze --no-pub`: no issues.
- Full Dart/widget suite: 988 passed, 1 failed. The only failure is the existing
  `test/features/agent/mcp_client_test.dart` SSE test, with Dio request cancellation
  (`Error: closed`) during teardown. It also fails identically in a disposable
  extraction of the unchanged original Git HEAD with the same dependency graph;
  the unrelated MCP transport was not changed.
- Focused integration suite: 60 passed, 0 failed. Coverage includes CPU-only launch arguments without context/parallel
  caps, 256K settings persistence, model readiness and cancellation/retry,
  source pinning, bundle integrity and symlinks, import rollback and stable UUIDs,
  native-log privacy across split chunks, and actual/unknown context usage.
- Host tests need the system SQLite runtime exposed as `libsqlite3.so`; the
  workspace test-only symlink and `LD_LIBRARY_PATH` are not APK contents.
- These are host unit/widget checks, not a claim of Android device execution.
  Native HTTP/Android build and device verification are separate gates.

Before invoking Flutter in this environment, source the workspace `env.sh`:
`BOT=true` and `CI=true` prevent Flutter's automatic Azure metadata probe. The
original unsafe startup attempt was stopped; no metadata access is required for
building or testing this app.
