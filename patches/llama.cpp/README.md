# llama.cpp Snapdragon backends (OpenCL + Hexagon)

Default workflow source: **llama.cpp v0.8.0**. The bundled app version remains defined by `assets/bin/llama_server_manifest.json`; changing the workflow does not replace its native binaries.

- Preset: `CMakeUserPresets.servllama.json`. Copy it to the llama.cpp root as `CMakeUserPresets.json`.
- Optional patch: `0001-hexagon-skip-unsupported-devices.patch` (written for v0.4.1)
- Image: `ghcr.io/snapdragon-toolchain/arm64-android:v0.8`
- Build script: `build_snapdragon_android.sh`

The optional patch stops llama.cpp v0.4.1 from registering a dummy `HTP0` on SoCs below Hexagon v73, such as Snapdragon 8 Gen 1. It is disabled by default for newer upstream releases, not automatically rewritten or silently skipped if selected.

`libggml-htp-v*.so` are Hexagon ELF files. Do not strip them with the Android NDK `llvm-strip`.

Image v0.8 provides Android NDK r29, Hexagon SDK 6.7.0.0 and Hexagon Tools 21.0.04. It does not set `OPENCL_SDK_ROOT`; OpenCL headers and `libOpenCL.so` come from the NDK sysroot. `HEXAGON_SDK_ROOT` and `HEXAGON_TOOLS_ROOT` are set in the image.

Upstream enabled Hexagon v85 and switched from `toolv19` to `toolv21` in [#30854](https://github.com/ggml-org/llama.cpp/pull/30854), together with image v0.8. Using image v0.7 (SDK 6.6 / Tools 19) with that newer source can compile a target named v85 with v68 flags and fail on missing HVX intrinsics such as `Q6_Vw_equals_Vsf`. Keep the source and toolchain compatible; do not disable compiler errors or force a different DSP architecture to work around this.

The image follows upstream's [Snapdragon CI](https://github.com/ggml-org/llama.cpp/blob/master/.github/workflows/build-and-test-snapdragon.yml) and [release workflow](https://github.com/ggml-org/llama.cpp/blob/master/.github/workflows/release.yml). Upstream publishes Android Snapdragon archives with CPU, OpenCL and Hexagon support. ServLlama still builds its own bundle: its preset retains Android API 28 and runtime-selected CPU variants instead of upstream's global ARMv8.7 flags, and the build script uses static libc++ and 16 KiB Android ELF alignment.

## GitHub Actions

`.github/workflows/build-llama-server-android.yml` is the release build. Run it manually with the desired tag; the default is `v0.8.0` with **Hexagon patch** unchecked.

The job checks out that tag, optionally applies the patch, and builds in the Snapdragon image. It uploads `llama-server-<tag>-android-arm64-v8a.tar.gz`. Copy both `android/app/src/main/jniLibs/arm64-v8a/` and `assets/bin/llama_server_manifest.json` from the archive over the app tree. The bundled manifest version is `<tag>` without the patch, or `<tag>+hexagon-skip-unsupported` with it. `BUILD_INFO.json` records the source revision, image/digest, SDK paths and patch name (or `null`).

If **Hexagon patch** is selected but does not apply, the job fails. Leave it unchecked for v0.8.0 or another source version that does not match the v0.4.1 patch. Older tags may also require the toolchain documented by that tag's upstream build guide.

Packaging verifies every `build_htp_skel(vNN)` target declared by the selected source, so newer releases must include v85 while older releases need not. `tool/verify_android_bundle.py` checks all `libggml-htp-*.so` as Hexagon ELF files and verifies they remain byte-identical to the JNI originals; new DSP versions are not mistaken for Android AArch64 libraries.

## Local build

```bash
git clone https://github.com/ggml-org/llama.cpp.git ~/src/llama.cpp
cd ~/src/llama.cpp
git checkout v0.8.0
cp /path/to/patches/llama.cpp/CMakeUserPresets.servllama.json CMakeUserPresets.json

docker run --rm -u "$(id -u):$(id -g)" \
  --platform linux/amd64 \
  -e HOME=/tmp \
  -e DEST_JNI=/workspace/dist/arm64-v8a \
  --volume "$HOME/src/llama.cpp":/workspace \
  --volume /path/to/patches/llama.cpp:/opt/servllama-patches:ro \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.8 \
  bash /opt/servllama-patches/build_snapdragon_android.sh /workspace
```

The script enables CPU variants, OpenCL, and Hexagon, builds llama-server and the HTP skels, strips Android ELF files, and copies the JNI whitelist to `DEST_JNI`.

## App behavior

The Flutter app runs `libllama-server.so --list-devices` with the same `LD_LIBRARY_PATH` and `ADSP_LIBRARY_PATH` as a normal start. Settings expose CPU and Hexagon. Hexagon takes a configurable offload layer count. OpenCL is still packaged, but it is hidden because Adreno results are not reliable yet. If the saved accelerator is unavailable, startup falls back to CPU.
