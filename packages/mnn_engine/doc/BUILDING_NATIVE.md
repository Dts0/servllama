> **ServLlama native 3.6.2 override:** follow [SERVLLAMA_MNN_362.md](SERVLLAMA_MNN_362.md) and `scripts/build_servllama_mnn.sh`. The upstream 0.2.0 instructions below are retained for reference and describe the original 3.6.1/r27d setup.

# Build the Android native libraries

This guide is for contributors who need to rebuild the native libraries in
`mnn_engine`. Applications installed from pub.dev already receive verified
libraries and do not need this toolchain.

## Requirements

- Git with submodule support
- Flutter and the Android SDK
- Linux, or Windows with WSL2 (Ubuntu 22.04 is recommended)
- Android NDK `27.3.13750724` (r27d)
- CMake `3.22.1`, Ninja, Python 3, and a C/C++ build toolchain
- An ARM64 Android target (`arm64-v8a`)

The plugin is built against MNN 3.6.1 at commit
`d407447ed56c4121a11ccbd266dc184ca1ead0c2`.

The default build enables CPU, `MNN_OPENCL` and `MNN_VULKAN`, with
`MNN_VULKAN_IMAGE=OFF` for LLM buffer operators and `MNN_HEXAGON=OFF`.
Hexagon is retained for development but hidden in ServLlama. Building its host
backend requires explicit `MNN_HEXAGON=ON`; executing on the DSP also requires
the optional SDK-built resources below.

The current native adapter ABI is **8**. The common build also
requires `MNN_USE_LOGCAT=ON` and `MNN_ENGINE_LOG_BRIDGE=ON`. The plugin-owned
`scripts/cmake/mnn_log_bridge.cmake` attaches a direct log bridge to libMNN through
`CMAKE_PROJECT_MNN_INCLUDE` and `--wrap=__android_log_print`, without changing
the upstream submodule. Rebuild and package MNN and JNI together. The build
fingerprint includes bridge sources and CMake extensions, and verification
checks the log-sink export. A bounded buffer preserves native error context
when loading or generation fails. There is no process-wide Android logger hook,
runtime self-test, or Logcat subprocess fallback. Normal Android output is kept.
Application INFO logs cover model/server lifecycle and generation completion;
request timing and internal MNN INFO details use DEBUG, while native warning and
error priorities are preserved. Successful requests do not dump native context.

The common build also requires `MNN_ENGINE_TOKENIZER_ADDED_TOKENS=ON`.
`scripts/cmake/mnn_tokenizer_compat.cmake` fixes MNN 3.6.1's single-token MTOK
decode in a generated build copy, preserving added tokens such as `</think>`
and `<tool_call>`. The pinned submodule is unchanged, but the shipped libMNN
includes this compatibility patch. Its source is fingerprinted; unexpected
upstream source changes fail configuration for review. Rebuild and package MNN
and JNI together, then run `bash scripts/test_mnn_tokenizer.sh "$PWD"`.
`MNN_BUILD_JOBS=4` limits native build memory use in WSL.

`MNN_ENGINE_PREFILL_CANCELLATION=ON` is also required. The build-copy integration
in `scripts/cmake/mnn_prefill_compat.cmake` adds cancellation checks to MNN's
existing chunk loops, keeps full prompt counts, and slices multimodal position
IDs and deep-stack tensors consistently with their embeddings. Images/audio are
encoded before chunking; an encoder call itself cannot be interrupted. A
thread-local C callback reads the adapter's atomic cancellation flag, so only
the generation thread changes MNN state. The runtime prepares each request under
the same lock as cancellation; native entry never clears a newly received stop.
Normal cancellation keeps the loaded model, and the next request resets partial
KV state. Integration sources and the callback export are verified by the
build fingerprint and artifact checks. See [native tests](../test/native/README.md)
for regression commands, including an optional real-model CPU check.
The adapter chooses the default chunk size after reading MNN's merged model
metadata: floating causal masks use 128 tokens; legacy GLM/integer masks keep
the upstream full-prefill default. Explicit `chunk`/`chunk_limits` remain intact.

On the tested 8 Elite, W4/C4 Qwen3-0.6B short conversations now work in both
official CLI and the app; non-C4 Attention is rejected before model loading.
Long-input answer quality has not passed, and errors also reproduce with other
official backend/configuration choices. See the [device investigation](HEXAGON_8_ELITE_INVESTIGATION_ZH.md)
for historical evidence. Maintained regression commands are documented in
[test/native/README.md](../test/native/README.md).

## Recommended: GitHub Actions

Use [build-native-android.yml](../.github/workflows/build-native-android.yml).
It runs on GitHub-hosted Ubuntu 22.04, installs the pinned NDK/CMake versions,
builds MNN and JNI, optionally builds the Hexagon runtime, and verifies and
uploads the results. No local native compilation is required.

### Start a build

1. Commit and push the plugin changes, build scripts and MNN submodule pointer
   to your repository or fork.
2. Open **Actions → Build Android native libraries → Run workflow**.
3. Select the branch to build, such as `feat/mnn-android-backends`, and choose
   the options below.
4. When **Build and package ARM64 libraries** succeeds, download its artifact
   from the run's **Artifacts** section.

GitHub requires the workflow to exist on the default branch for manual dispatch.
Enable Actions first if your fork has not enabled workflows yet.

| Input | Default | Effect |
| --- | --- | --- |
| `include_hexagon` | `false` | Explicitly opt in to the experimental host backend and SDK-built DSP runtimes; default builds CPU/GPU only |
| `hexagon_dsp_arch` | `all` | Bundle v73/v75/v79/v81 and automatically match the device, or choose a single target for a smaller APK |
| `hexagon_toolchain` | `docker` | Pinned Snapdragon Docker image, or `sdk_archive` for a supplied SDK installation |
| `verify_example_apk` | `false` | Analyze/test Flutter, run Kotlin tests and build/verify the ARM64 example APK in a separate job |

GitHub CLI examples:

```bash
gh workflow run build-native-android.yml --ref feat/mnn-android-backends
# Use the Docker image's SDK directly:
gh workflow run build-native-android.yml --ref feat/mnn-android-backends \
  -f include_hexagon=true -f hexagon_dsp_arch=all -f verify_example_apk=true
```

Pushing a `native-v*` tag builds the CPU/GPU bundle and verifies
the example APK. Hexagon is built only by a manual run with `include_hexagon=true`.
Every native build runs the adapter/log bridge and tokenizer regression checks.
The workflow uploads Actions
artifacts; it does not publish GitHub Releases or pub.dev packages.

### Download and install the artifacts

The common artifact is named `mnn-engine-android-arm64-common-<MNN-short-commit>`.
Hexagon builds use `mnn-engine-android-arm64-hexagon-all-<MNN-short-commit>`; a
single-target build uses its architecture in place of `all`. The ZIP preserves
the plugin's directory layout:

```text
android/src/main/jniLibs/arm64-v8a/     # libMNN.so, libmnn_engine_jni.so, optional stub
android/src/main/assets/mnn/hexagon/   # optional DSP runtime and manifest
  manifest.json                      # schema 2; provenance and hashes per architecture
  v73/ v75/ v79/ v81/                 # each holds a skeleton and both DSP C++ libraries
native/android-arm64-v8a.json          # versions, flags, hashes, packaged capabilities
native/github-actions.json            # plugin revision, run URL, SDK/compiler/image provenance
.native/generated/arm64-v8a/           # unstripped libraries and verification inputs
SHA256SUMS
LICENSE
THIRD_PARTY_NOTICES.md
third_party_licenses/
```

Check out the matching plugin revision and initialize the MNN submodule, then
extract the ZIP into the plugin root. There is no nested tar archive. The
`.native/generated` files support verification/debugging and are not packaged
into consumer APKs. Verify in Linux/WSL with:

```bash
sha256sum --check SHA256SUMS
bash scripts/verify_mnn_artifacts.sh "$PWD"
```

Before installing a different artifact, including a single-target or common
package over an all-architecture package, first remove the old
generated `android/src/main/jniLibs/arm64-v8a/libMNN_htpops.so` and
`android/src/main/assets/mnn/hexagon/` to avoid a manifest mismatch.

The run summary reports the actual MNN version, JNI ABI, compiled backends and
DSP packaging state. Native artifacts are uploaded before the optional example
job starts, so an example failure does not prevent downloading the libraries.
Build logs are uploaded as `mnn-native-logs-*` / `mnn-example-logs-*`, including
logs produced before a failure. Libraries are retained for 30 days; APKs and
logs for 14 days.

### Hexagon on GitHub-hosted runners

Keep `hexagon_toolchain=docker` to use the Snapdragon image recommended by
llama.cpp's Snapdragon documentation. The v0.7 image is pinned by digest:

```text
ghcr.io/snapdragon-toolchain/arm64-android@sha256:91714433626f0d94a926538a1e46ec43756c5b8e3262b91b95df1e812940aed1
```

It includes **Hexagon SDK 6.6.0.0, Hexagon Clang 19.0.07 and CMake 3.31.6**.
Actions mounts its NDK r27d read-only for the Android stub. The build container
has no network access; only the artifact host mount is writable. The
image takes approximately 10 GB unpacked, so its first pull needs adequate
network access and disk space. The artifact manifest records SDK/compiler
versions, the image digest/ID and link verification details.

Actual v73/v75/v79/v81 DSP builds using this image have passed locally. ServLlama
Debug and example Release APKs containing all four targets have passed artifact validation,
along with local common/Hexagon bundle, ZIP transfer and consumer checks. The default image is
stored in `scripts/hexagon/toolchain-image.txt`; `MNN_HEXAGON_DOCKER_IMAGE` can
override it. No SDK secret is needed for Docker builds.

### Optional: supply an SDK archive

These settings apply only to `hexagon_toolchain=sdk_archive`. Prepare a complete,
relocatable Linux SDK installation with its matching toolchain, under its license
terms. The current adapter uses SDK public headers and CMake toolchains; the
version actually tested here is 6.6.0.0. Archive the installed directory,
preserving executable modes and internal relative symlinks:

```bash
tar -C /path/to/sdk-parent -czf hexagon-sdk-linux.tar.gz hexagon-sdk
sha256sum hexagon-sdk-linux.tar.gz
```

The archive root or one top-level directory must contain `setup_sdk_env.source`
and `build/cmake/hexagon_fun.cmake`. Use a prepared installation, not an installer
or a download/login page. Host the tar.gz / tar.xz archive at a URL the runner
can download, and configure **Settings → Secrets and variables → Actions**:

| Type | Name | Value |
| --- | --- | --- |
| Repository secret | `HEXAGON_SDK_ARCHIVE_URL` | Direct HTTPS download URL; signed URLs must remain valid for the build |
| Repository variable | `HEXAGON_SDK_ARCHIVE_SHA256` | The matching 64-character archive SHA256 |

Enable `include_hexagon` and select `sdk_archive`. The workflow checks options,
archive checksums and extraction boundaries before invoking CMake directly. It does not
upload the SDK installation or its download URL, and the MNN/JNI build cache
does not cache the SDK. Distribute runtime binaries under the SDK's terms.

Missing SDK configuration, checksum failures or SDK build failures fail the
Hexagon build. They do not produce a placeholder NPU package. Leave
`include_hexagon=false` to build the common libraries without the SDK.

Actions cannot validate Android drivers, FastRPC access or device inference.
Actual SDK/DSP compilation in Docker, workflow syntax and the extraction,
packaging and link checks have passed locally. This workflow revision has not
run on a remote runner yet. See ServLlama's implementation report and acceptance
guide for device validation status.

The example has an `integration_test` development dependency. With Flutter
3.35.2, keep pub enabled in `flutter build apk --release` so Flutter regenerates
the release plugin registrant. Adding `--no-pub` can retain a debug registrant
and fail with a missing integration-test Android class.

## Local build on Windows + WSL2

Run these commands from the plugin root in PowerShell 7:

```powershell
git submodule update --init --recursive
pwsh -File .\scripts\prepare_mnn_build_env.ps1
pwsh -File .\scripts\build_mnn_android.ps1
pwsh -File .\scripts\verify_mnn_artifacts.ps1
pwsh -File .\scripts\package_mnn_artifacts.ps1
```

If your WSL distribution is not named `Ubuntu-22.04`, pass its name to each
PowerShell script with `-Distro <name>`.

The build first creates temporary files under `.native/generated/`. Packaging
then validates and copies the release libraries to:

```text
android/src/main/jniLibs/arm64-v8a/
├── libMNN.so
└── libmnn_engine_jni.so
```

It also updates `native/android-arm64-v8a.json`, which records the native
version, hashes, and build metadata.

## Local build on Linux

Install the Android SDK packages for API 35, NDK `27.3.13750724`, and CMake
`3.22.1`, then set the toolchain paths:

```bash
export ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/27.3.13750724"
export MNN_CMAKE="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
```

From the plugin root:

```bash
git submodule update --init --recursive
bash scripts/build_mnn_android.sh "$PWD"
bash scripts/verify_mnn_artifacts.sh "$PWD"
bash scripts/package_mnn_artifacts.sh "$PWD"
```

## Verify the result

Run the Dart checks and build the ARM64 example APK:

```powershell
flutter analyze
flutter test

Push-Location example
flutter pub get
flutter build apk --release --target-platform android-arm64
Pop-Location

pwsh -File .\scripts\verify_mnn_artifacts.ps1 `
  -ApkPath <absolute-path-to-app-release.apk>
```

At minimum, confirm that the example can initialize the plugin and load a
model on an ARM64 device.

## Optional Hexagon runtime (Linux / WSL)

With Docker running in WSL/Linux, use the pinned image from the plugin root.
The default builds v73/v75/v79/v81, with one shared ARM64 stub and automatic device matching:

```bash
export ANDROID_NDK="$HOME/android-ndk-r27d"
MNN_HEXAGON=ON bash scripts/build_mnn_android.sh "$PWD"
bash scripts/build_hexagon_docker.sh "$PWD" all
MNN_HEXAGON_ARTIFACTS="$PWD/.native/hexagon" \
  bash scripts/package_mnn_artifacts.sh "$PWD"
```

The wrapper pulls the image only if absent, supports paths containing spaces,
and runs the container as the repository directory's owner. Parallelism defaults
to 4 (`MNN_BUILD_JOBS`). Without `ANDROID_NDK`, it uses the image's NDK r29 and
records that version; this standalone v73 build also passed locally. Actions
always supplies r27d. If the WSL account lacks
Docker socket access, run the wrapper using `wsl.exe -d Ubuntu-22.04 -u root --
...`; the container still runs as the repository owner.

An installed Linux [Qualcomm Hexagon SDK](https://www.qualcomm.com/developer/software/hexagon-npu-sdk)
can also be used directly:

```bash
export HEXAGON_SDK_ROOT=/absolute/path/to/hexagon-sdk
# HEXAGON_TOOLS_ROOT is optional; otherwise read from hexagon_sdk.json.
bash scripts/build_hexagon_android.sh "$PWD" all
```

The adapter copies public MNN sources and invokes SDK CMake toolchains and QAIC
directly, without `setup_sdk_env.source` or `build_cmake`. Community SDK lacks
the old `utils/examples` helper, so `scripts/hexagon/` supplies MNN's domain
lookup using `domain_default.h` and the cDSP architecture query using `remote.h`.
Before cross-compiling, tests use real SDK headers with a simulated FastRPC
driver to check BCD decoding, query errors and missing capability APIs. When
the image has no `cc`, these tests use the NDK Clang executable's Linux host
target. Neither MNN nor SDK files are modified; DSP operators are unchanged.

Build checks cover FastRPC exports, dynamic dependencies and C++ symbol
resolution. Remaining QuRT/FastRPC, POSIX and unwinder imports are supplied by
device firmware and recorded in `link-report.json`. Packaging checks the source
commit, hashes, ELF type, actual DSP architecture flags and stub 16 KB LOAD
alignment. The outputs are:

- `jniLibs/arm64-v8a/libMNN_htpops.so`: Android stub.
- `assets/mnn/hexagon/manifest.json`: schema 2, with resource hashes and build provenance for each architecture.
- `assets/mnn/hexagon/<architecture>/`: DSP skeleton, `libc++.so.1` and `libc++abi.so.1`.
- `native/android-arm64-v8a.json`: JNI ABI 8; compiled backends reflect actual build flags and `runtime.hexagon.dspArchitectures` lists the packaged targets.

The DSP files must remain assets, not ARM64 JNI libraries. On the first backend
capability request, FastRPC identifies the device ISA. Android verifies and
extracts only the exact match to `noBackupFilesDir/mnn/hexagon/<manifest-hash>/<architecture>/`
and sets `ADSP_LIBRARY_PATH`. Failed queries or missing matches make Hexagon
unavailable without guessing an ISA. The host must keep `useLegacyPackaging=true`.
OEM `libcdsprpc.so` comes from the device and is not copied into the package.
SDK runtime redistribution follows the SDK's license terms.

For a smaller targeted test build, pass `v79` instead of `all` and package with
`MNN_HEXAGON_ARTIFACTS="$PWD/.native/hexagon/v79"`. That bundle enables Hexagon
only on a matching v79 device. Passing the parent `.native/hexagon` requires
all four targets and fails if one is missing. Unrelated experiment directories
such as `v73-ndk-r29` are not automatically included.

Running packaging without `MNN_HEXAGON_ARTIFACTS` removes previous optional DSP
output. For a default CPU/GPU build, first rebuild with `MNN_HEXAGON=OFF` (the
default), then package without DSP resources. Passing DSP resources to a build
with the host backend disabled is rejected. The PowerShell build wrapper exposes
`-IncludeHexagon` for the same opt-in. SDK absence does not block the default build.
Device correctness and performance checks remain
required even when DSP compilation and packaging succeed.

## When to rebuild

Rebuild when changing MNN or JNI native code, the MNN commit, the NDK/CMake
version, or native build flags. Dart-only or documentation changes can reuse
the existing libraries.

## Common issues

- **Missing WSL distribution:** install Ubuntu 22.04 or pass `-Distro`.
- **Missing NDK/CMake:** set `ANDROID_NDK` and `MNN_CMAKE` to the pinned
  versions above.
- **Unsupported ABI:** configure the host app for `arm64-v8a` only.
- **Missing submodule:** run `git submodule update --init --recursive`.
