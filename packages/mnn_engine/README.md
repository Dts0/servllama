> ServLlama vendored integration: wrapper API version **0.2.0**, native MNN **3.6.2**. This is not a new published wrapper release. See [the upgrade notes](doc/SERVLLAMA_MNN_362.md) for source pins, build steps and untested hardware/model behavior.

# mnn_engine

[English](README.md) | [简体中文](README_ZH.md)

[![pub package](https://img.shields.io/pub/v/mnn_engine.svg)](https://pub.dev/packages/mnn_engine)
[![Build Android native libraries](https://github.com/ArkaneFans/mnn_engine/actions/workflows/build-native-android.yml/badge.svg)](https://github.com/ArkaneFans/mnn_engine/actions/workflows/build-native-android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Run [Alibaba MNN](https://github.com/alibaba/MNN) large language models on
Android from Flutter, and expose the active model as an on-device
OpenAI-compatible HTTP API.

The pub.dev package already includes verified `arm64-v8a` native libraries.
Host apps do not need MNN source, CMake, the Android NDK, Linux, or WSL.

> [!IMPORTANT]
> This is an independent community plugin, not an official Alibaba MNN
> Flutter package. The public API is still evolving in the `0.x` series.

## Features

- Import, list, load, unload, rename, and delete complete MNN model
  directories. The directory name is the runtime and API model ID.
- Validate `config.json` and referenced model, weight, embedding, and
  tokenizer files before activation.
- Keep model and server state in an Android foreground service, with
  snapshots, state events, and log streams.
- Serve OpenAI-compatible `/v1/models` and `/v1/chat/completions` on
  loopback or all IPv4 interfaces, with optional Bearer authentication,
  SSE streaming, vision input, function tools, reasoning content, and
  cancelling the current generation.
- Configure backend, mmap, precision, and generation threads when loading
  a model, and inspect or clear mmap/GPU caches.

## Platform support

| Requirement | Supported value |
| --- | --- |
| Flutter platform | Android only |
| Android ABI | `arm64-v8a` only |
| Minimum Android version | API 28 |
| Compile SDK used by the plugin | 35 |
| Flutter | 3.35.0 or newer |
| MNN | 3.6.2 at commit `7e3c6fb5225d3ccf22807ee641332669df34650d` |
| Inference backend | CPU (default), OpenCL, Vulkan buffer; optional Hexagon |
| Active models | One at a time |
| Concurrent generations | One at a time |

iOS, desktop platforms, `armeabi-v7a`, `x86`, and `x86_64` are not currently
supported.

The 0.2.0 development branch is unpublished. Its HTTP cancellation and native
drain contract is documented in [HTTP cancellation](doc/HTTP_CANCELLATION_ZH.md).

## Installation

Add the package to your Flutter application:

```yaml
dependencies:
  mnn_engine: ^0.1.1
```

Then run:

```shell
flutter pub get
```

Configure the host Android application for API 28 and ARM64. For Kotlin DSL
(`android/app/build.gradle.kts`):

```kotlin
android {
    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}
```

For Groovy (`android/app/build.gradle`):

```groovy
android {
    defaultConfig {
        minSdk 28

        ndk {
            abiFilters "arm64-v8a"
        }
    }
}
```

The development plugin manifest merges Internet, foreground service, special-use
foreground service, and notification permissions. On Android 13 and newer,
the host application should request notification permission in its own UI.

## Quick start

```dart
import 'package:mnn_engine/mnn_engine.dart';

final engine = MnnEngine.instance;

final eventSubscription = engine.events.listen((event) {
  final snapshot = event.snapshot;
  print(
    'model=${snapshot.modelState}, '
    'server=${snapshot.serverState}, '
    'generation=${snapshot.generationState}',
  );
});

final logSubscription = engine.logs.listen((entry) {
  print('[${entry.level}] ${entry.tag}: ${entry.message}');
});

final engineInfo = await engine.initialize();
if (!engineInfo.nativeLibraryLoaded) {
  throw StateError('MNN native runtime is unavailable.');
}

// Opens the Android directory picker and imports the selected model directory
// into the application's private storage.
final importedModel = await engine.importModelDirectory();
await engine.loadModel(importedModel.modelId);

late final MnnServerInfo server;
try {
  server = await engine.startServer(
    port: 8081,
    apiKey: 'replace-with-a-runtime-secret',
  );
} on MnnEngineException catch (error) {
  if (error.code == 'port_in_use') {
    throw StateError('Port 8081 is currently unavailable.');
  }
  rethrow;
}
print('OpenAI-compatible base URL: ${server.baseUrl}/v1');

// Later, when the host decides to stop the runtime:
await engine.stopServer();
await engine.unloadModel();
await eventSubscription.cancel();
await logSubscription.cancel();
```

`importModelDirectory()` launches an Android Activity and must be called while
the plugin is attached to a Flutter Activity. If the host application already
downloaded a model into private storage, use:

```dart
final imported = await engine.importModelFromPath(modelDirectory.path);
```

## Load options

`loadModel()` accepts `MnnLoadOptions`. Omitted fields use the defaults below.

| Field | Default | Values |
| --- | --- | --- |
| `backend` | `cpu` | `cpu`, `opencl`, `vulkan`; `hexagon` is experimental |
| `useMmap` | `false` | `true`, `false` |
| `precision` | `low` | `low`, `high` |
| `threadNum` | `4` | `1` to `8` |

Call `getBackendCapabilities()` before offering a GPU backend. `compiled`
means the backend is included in this build; `available` means it initialized
on this device. Stop the API server before loading or switching a model.
Unavailable backends return `backend_unavailable`; the plugin does not fall
back to CPU. The loaded model's `backend`, `useMmap`, `precision`, and
`threadNum` fields record the selection.

```dart
final capabilities = await engine.getBackendCapabilities();
final opencl = capabilities.firstWhere(
  (item) => item.backend == MnnBackend.opencl,
);
if (!opencl.available) {
  throw StateError('OpenCL is not available on this device.');
}

await engine.loadModel(
  importedModel.modelId,
  options: const MnnLoadOptions(
    backend: MnnBackend.opencl,
    useMmap: true,
    precision: MnnPrecision.high,
    threadNum: 6,
  ),
);
```

`getMmapCache()` and `clearMmapCache()` report or delete generated mmap and
GPU runtime caches. Unload the model before clearing. Pass `modelId` for one
imported model, or omit it for all models. The next load rebuilds the cache.

```dart
final cache = await engine.getMmapCache();
if (cache.sizeBytes > 0) {
  await engine.clearMmapCache();
}
```

`hexagon` is experimental and requires a matching native build. See
[native build instructions](doc/BUILDING_NATIVE.md).

## Model directory

The plugin imports a complete MNN model directory rather than a single `.mnn`
file. A typical text model looks like this:

```text
Qwen3-0.6B-MNN/
├── config.json
├── llm.mnn
├── llm.mnn.weight
├── tokenizer.txt
├── llm_config.json
└── market_config.json          # Optional display metadata
```

The root `config.json` must contain a non-empty `llm_model`. Every referenced
model, weight, embedding, and tokenizer path must be relative to the model
directory and must exist. Imported models are copied to:

```text
<application filesDir>/mnn/models/<model-key>/
```

Model files and model licenses are not distributed with this plugin.

## OpenAI-compatible API

The default server address is `http://127.0.0.1:8081`.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/` | Built-in API test page |
| `GET` | `/health` | Engine, model, and server state |
| `GET` | `/v1/models` | The active model in OpenAI-compatible format |
| `POST` | `/v1/chat/completions` | Streaming or non-streaming chat completions |

Example request:

```shell
curl http://127.0.0.1:8081/v1/chat/completions \
  -H "Authorization: Bearer replace-with-a-runtime-secret" \
  -H "Content-Type: application/json" \
  --data-binary '{
    "model": "qwen3-0-6b-mnn",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true,
    "max_tokens": 128
  }'
```

`max_tokens` is optional. When omitted or set to `-1`, generation continues
until the model emits an end token or the runtime reaches its context limit.
`max_completion_tokens` and `n_predict` are accepted as aliases.

Use `MnnServerBindMode.allInterfaces` to listen on `0.0.0.0`. This makes the
server reachable through Wi-Fi, hotspots, VPNs, and other IPv4 interfaces.
Always configure an API key unless the device is on a trusted network.

## Runtime rules

- A model must be loaded before the server starts.
- Stop the server before unloading, deleting, or replacing the active model.
- A second concurrent generation receives HTTP 429; requests are not queued.
- `stopServer()` rejects new requests with HTTP 503 (`server_stopping`) and
  cancels the active generation.
- `cancelGeneration()` cancels only the active generation; it does not stop
  the server.
- A generation failure unloads the model and clears `activeModel`. Recover
  with `stopServer()`, `loadModel()`, then `startServer()`. Normal completion,
  length limits, and cancellation keep the model loaded.
- Complete shutdown order is `stopServer()` followed by `unloadModel()`.

## Native binaries

The pub package contains:

```text
android/src/main/jniLibs/arm64-v8a/
├── libMNN.so
└── libmnn_engine_jni.so
```

Source revision, toolchain, build flags, and SHA-256 hashes are recorded in
[`native/android-arm64-v8a.json`](native/android-arm64-v8a.json). Consumer
builds never compile native code. Maintainers can rebuild locally or with
GitHub Actions; see [native build instructions](doc/BUILDING_NATIVE.md).

## Additional resources

- [Build the Android native libraries](doc/BUILDING_NATIVE.md)
- [中文 Native 编译指南](doc/BUILDING_NATIVE_ZH.md)
- [Example application](example/)

## License

`mnn_engine` is released under the [Apache License 2.0](LICENSE). Bundled
native code and third-party attribution are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Model files are licensed
separately by their respective publishers.
