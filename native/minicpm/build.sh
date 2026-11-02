#!/usr/bin/env bash
# Build a pinned, CPU-only, headless MiniCPM HTTP runtime. All inference libs static.
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
work=${MINICPM_BUILD_ROOT:-"$repo/.minicpm-build"}
mode=${1:-host}
src="$work/llama.cpp-omni"
mkdir -p "$work"
if [[ ! -d "$src/.git" ]]; then
  git clone --filter=blob:none https://github.com/tc-mb/llama.cpp-omni.git "$src"
  git -C "$src" checkout --detach e0eca9d8f57ac8f5a5ad6191a8007b93436c1e71
fi
[[ $(git -C "$src" rev-parse HEAD) == e0eca9d8f57ac8f5a5ad6191a8007b93436c1e71 ]] || { echo 'Unexpected source revision' >&2; exit 1; }
patch="$repo/patches/minicpm/servllama-http.patch"
if git -C "$src" apply --check "$patch" 2>/dev/null; then git -C "$src" apply "$patch"; else git -C "$src" apply --reverse --check "$patch"; fi
cp "$here/main.cpp" "$src/tools/server/servllama-main.cpp"
printf '#pragma once\n// Headless ServLlama runtime.\n' > "$src/tools/server/ui.h"
args=(-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF)
if [[ "$mode" == android ]]; then
  : "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 27.0.12077973}"
  args+=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8-a -DGGML_OPENMP=OFF)
elif [[ "$mode" == host ]]; then
  args+=(-DGGML_NATIVE=ON -DGGML_OPENMP=ON)
else echo 'Usage: build.sh host|android' >&2; exit 1; fi
cmake -S "$src" -B "$work/build-$mode" "${args[@]}"
cmake --build "$work/build-$mode" --target minicpm-server -j "${BUILD_JOBS:-4}"
if [[ "$mode" == android ]]; then
  python3 "$here/package_android.py" "$work" --ndk "$ANDROID_NDK_HOME"
fi
