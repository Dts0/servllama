#!/usr/bin/env bash
set -euo pipefail

plugin_root="${1:?Plugin root is required}"
mnn_root="${plugin_root}/MNN"
cmake_bin="${MNN_CMAKE:-${HOME}/.local/share/mnn_engine/toolchains/cmake-3.22.1/bin/cmake}"
android_ndk="${ANDROID_NDK:-${HOME}/android-ndk-r27d}"
ninja_bin="${MNN_NINJA:-/usr/bin/ninja}"
verifier_script="${plugin_root}/scripts/verify_mnn_artifacts.sh"
adapter_abi_version="8"
expected_cmake_version="3.22.1"
hexagon_enabled="${MNN_HEXAGON:-OFF}"
case "${hexagon_enabled}" in
    ON|OFF) ;;
    *) printf 'MNN_HEXAGON must be ON or OFF.\n' >&2; exit 16 ;;
esac

if [[ ! -x "${cmake_bin}" ]]; then
    printf 'CMake %s is missing. Run scripts/prepare_mnn_build_env.ps1 first.\n' "${expected_cmake_version}" >&2
    exit 10
fi
cmake_version_line="$("${cmake_bin}" --version | sed -n '1p')"
if [[ "${cmake_version_line}" =~ ^cmake\ version\ ([0-9]+\.[0-9]+\.[0-9]+)([-+].*)?$ ]]; then
    cmake_version="${BASH_REMATCH[1]}"
else
    printf 'Unable to parse the CMake version reported by %s: %s\n' "${cmake_bin}" "${cmake_version_line}" >&2
    exit 11
fi
if [[ "${cmake_version}" != "${expected_cmake_version}" ]]; then
    printf 'MNN_CMAKE must point to CMake %s, but reported %s: %s\n' \
        "${expected_cmake_version}" "${cmake_version_line}" "${cmake_bin}" >&2
    exit 11
fi
if [[ ! -f "${android_ndk}/build/cmake/android.toolchain.cmake" ]]; then
    printf 'Android NDK toolchain was not found under %s\n' "${android_ndk}" >&2
    exit 12
fi
if [[ ! -x "${ninja_bin}" ]]; then
    printf 'Ninja was not found at %s\n' "${ninja_bin}" >&2
    exit 13
fi
if [[ ! -f "${mnn_root}/CMakeLists.txt" ]]; then
    printf 'MNN submodule is missing at %s\n' "${mnn_root}" >&2
    exit 14
fi
if [[ ! -f "${verifier_script}" ]]; then
    printf 'Artifact verifier is missing at %s\n' "${verifier_script}" >&2
    exit 15
fi

mnn_commit="$(git -C "${mnn_root}" rev-parse HEAD)"
expected_mnn_commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["upstream"]["commit"])' "${plugin_root}/native/source-pin.json")"
if [[ "${mnn_commit}" != "${expected_mnn_commit}" ]] || [[ -n "$(git -C "${mnn_root}" status --porcelain)" ]]; then
    printf 'MNN source must be clean and pinned to %s. Run scripts/prepare_mnn_source.sh.\n' "${expected_mnn_commit}" >&2
    exit 17
fi
ndk_revision="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "${android_ndk}/source.properties")"
ninja_version="$("${ninja_bin}" --version)"
flags_key='CMAKE_BUILD_TYPE=Release|ANDROID_ABI=arm64-v8a|ANDROID_PLATFORM=android-28|ANDROID_STL=c++_static|ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON|MNN_BUILD_SHARED_LIBS=ON|MNN_BUILD_FOR_ANDROID_COMMAND=ON|MNN_BUILD_LLM=ON|MNN_BUILD_LLM_OMNI=ON|MNN_LOW_MEMORY=ON|MNN_SUPPORT_TRANSFORMER_FUSE=ON|MNN_ARM82=ON|MNN_USE_LOGCAT=ON|MNN_SEP_BUILD=OFF|MNN_KLEIDIAI=OFF|MNN_BUILD_DIFFUSION=OFF|MNN_BUILD_OPENCV=ON|MNN_IMGCODECS=ON|MNN_BUILD_AUDIO=ON|MNN_OPENCL=ON|MNN_VULKAN=ON|MNN_VULKAN_IMAGE=OFF|MNN_QNN=OFF|MNN_BUILD_TEST=OFF|MNN_BUILD_BENCHMARK=OFF'
flags_key+="|MNN_HEXAGON=${hexagon_enabled}"
adapter_hash="$(find "${plugin_root}/android/src/main/cpp" -maxdepth 1 -type f \( -name '*.cpp' -o -name '*.hpp' -o -name 'CMakeLists.txt' \) -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')"
flags_key+='|MNN_ENGINE_LOG_BRIDGE=ON|MNN_ENGINE_TOKENIZER_ADDED_TOKENS=ON|MNN_ENGINE_PREFILL_CANCELLATION=ON'
integration_hash="$(sha256sum "${plugin_root}/scripts/cmake/mnn_log_bridge.cmake" \
    "${plugin_root}/scripts/cmake/mnn_tokenizer_compat.cmake" \
    "${plugin_root}/scripts/cmake/mnn_prefill_compat.cmake" \
    "${plugin_root}/android/src/main/cpp/mnn_prefill_control.cpp" \
    "${plugin_root}/android/src/main/cpp/mnn_prefill_control.hpp" \
    "${plugin_root}/android/src/main/cpp/mnn_log_bridge.cpp" \
    "${plugin_root}/android/src/main/cpp/mnn_log_bridge.hpp" | sha256sum | awk '{print $1}')"
mnn_fingerprint="$(printf '%s\n%s\n%s\n%s\n%s\n%s\n' "${mnn_commit}" "${ndk_revision}" "${cmake_version}" "${ninja_version}" "${flags_key}" "${integration_hash}" | sha256sum | cut -c1-24)"
fingerprint="$(printf '%s\n%s\n%s\n' "${mnn_fingerprint}" "${adapter_hash}" "${adapter_abi_version}" | sha256sum | cut -c1-24)"
workspace_id="$(printf '%s' "${plugin_root}" | sha256sum | cut -c1-12)"
workspace_cache="${HOME}/.cache/mnn_engine/${workspace_id}"
mnn_build_dir="${workspace_cache}/${mnn_fingerprint}/mnn"
jni_build_dir="${workspace_cache}/${fingerprint}/jni"
generated_dir="${plugin_root}/.native/generated/arm64-v8a"
build_info_path="${generated_dir}/mnn_build_info.json"

if [[ -f "${generated_dir}/libMNN.so" ]] &&
   [[ -f "${generated_dir}/libmnn_engine_jni.so" ]] &&
   [[ -f "${build_info_path}" ]] &&
   grep -q "\"fingerprint\": \"${fingerprint}\"" "${build_info_path}"; then
    printf 'MNN native artifacts are up to date: %s\n' "${fingerprint}"
    bash "${verifier_script}" "${plugin_root}"
    exit 0
fi

mkdir -p "${mnn_build_dir}" "${jni_build_dir}" "${generated_dir}"

"${cmake_bin}" -S "${mnn_root}" -B "${mnn_build_dir}" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${ninja_bin}" \
    -DCMAKE_TOOLCHAIN_FILE="${android_ndk}/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PROJECT_MNN_INCLUDE="${plugin_root}/scripts/cmake/mnn_log_bridge.cmake" \
    -DMNN_ENGINE_LOG_BRIDGE=ON \
    -DMNN_ENGINE_TOKENIZER_ADDED_TOKENS=ON \
    -DMNN_ENGINE_PREFILL_CANCELLATION=ON \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DMNN_BUILD_SHARED_LIBS=ON \
    -DMNN_BUILD_FOR_ANDROID_COMMAND=ON \
    -DMNN_BUILD_LLM=ON \
    -DMNN_BUILD_LLM_OMNI=ON \
    -DMNN_LOW_MEMORY=ON \
    -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
    -DMNN_ARM82=ON \
    -DMNN_USE_LOGCAT=ON \
    -DMNN_SEP_BUILD=OFF \
    -DMNN_KLEIDIAI=OFF \
    -DMNN_BUILD_DIFFUSION=OFF \
    -DMNN_BUILD_OPENCV=ON \
    -DMNN_IMGCODECS=ON \
    -DMNN_BUILD_AUDIO=ON \
    -DMNN_OPENCL=ON \
    -DMNN_VULKAN=ON \
    -DMNN_VULKAN_IMAGE=OFF \
    -DMNN_HEXAGON="${hexagon_enabled}" \
    -DMNN_QNN=OFF \
    -DMNN_BUILD_TEST=OFF \
    -DMNN_BUILD_BENCHMARK=OFF

"${cmake_bin}" --build "${mnn_build_dir}" --target MNN --parallel "${MNN_BUILD_JOBS:-$(nproc)}"
mnn_library="$(find "${mnn_build_dir}" -type f -name 'libMNN.so' -print -quit)"
if [[ -z "${mnn_library}" ]]; then
    printf 'libMNN.so was not produced.\n' >&2
    exit 20
fi

"${cmake_bin}" -S "${plugin_root}/android/src/main/cpp" -B "${jni_build_dir}" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${ninja_bin}" \
    -DCMAKE_TOOLCHAIN_FILE="${android_ndk}/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DMNN_ROOT="${mnn_root}" \
    -DMNN_LIBRARY="${mnn_library}" \
    -DMNN_OPENCL=ON \
    -DMNN_VULKAN=ON \
    -DMNN_HEXAGON="${hexagon_enabled}" \
    -DMNN_COMMIT="${mnn_commit}"

"${cmake_bin}" --build "${jni_build_dir}" --parallel "${MNN_BUILD_JOBS:-$(nproc)}"
jni_library="${jni_build_dir}/libmnn_engine_jni.so"
if [[ ! -f "${jni_library}" ]]; then
    printf 'libmnn_engine_jni.so was not produced.\n' >&2
    exit 21
fi

file "${mnn_library}" | grep -q 'ARM aarch64'
file "${jni_library}" | grep -q 'ARM aarch64'

staging_output="$(mktemp -d "${plugin_root}/.native/.generated-arm64-v8a.XXXXXX")"
cleanup_output() {
    if [[ -n "${staging_output:-}" ]] && [[ -d "${staging_output}" ]]; then
        rm -rf -- "${staging_output}"
    fi
}
trap cleanup_output EXIT

cp -f "${mnn_library}" "${staging_output}/libMNN.so"
cp -f "${jni_library}" "${staging_output}/libmnn_engine_jni.so"
wsl_distribution="$(. /etc/os-release && printf '%s %s' "${NAME}" "${VERSION_ID}")"
host_architecture="$(uname -m)"
cat > "${staging_output}/mnn_build_info.json" <<EOF
{
  "fingerprint": "${fingerprint}",
  "mnnFingerprint": "${mnn_fingerprint}",
  "mnnCommit": "${mnn_commit}",
  "ndkVersion": "${ndk_revision}",
  "cmakeVersion": "${cmake_version}",
  "cmakePath": "${cmake_bin}",
  "ninjaVersion": "${ninja_version}",
  "abi": "arm64-v8a",
  "androidPlatform": "android-28",
  "buildType": "Release",
  "nativeAdapterAbiVersion": ${adapter_abi_version},
  "wslDistribution": "${wsl_distribution}",
  "hostArchitecture": "${host_architecture}",
  "cmakeFlags": [
    "CMAKE_BUILD_TYPE=Release",
    "ANDROID_ABI=arm64-v8a",
    "ANDROID_PLATFORM=android-28",
    "ANDROID_STL=c++_static",
    "ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
    "MNN_BUILD_SHARED_LIBS=ON",
    "MNN_BUILD_FOR_ANDROID_COMMAND=ON",
    "MNN_BUILD_LLM=ON",
    "MNN_BUILD_LLM_OMNI=ON",
    "MNN_LOW_MEMORY=ON",
    "MNN_SUPPORT_TRANSFORMER_FUSE=ON",
    "MNN_ARM82=ON",
    "MNN_USE_LOGCAT=ON",
    "MNN_ENGINE_LOG_BRIDGE=ON",
    "MNN_ENGINE_TOKENIZER_ADDED_TOKENS=ON",
    "MNN_ENGINE_PREFILL_CANCELLATION=ON",
    "MNN_SEP_BUILD=OFF",
    "MNN_KLEIDIAI=OFF",
    "MNN_BUILD_DIFFUSION=OFF",
    "MNN_BUILD_OPENCV=ON",
    "MNN_IMGCODECS=ON",
    "MNN_BUILD_AUDIO=ON",
    "MNN_OPENCL=ON",
    "MNN_VULKAN=ON",
    "MNN_VULKAN_IMAGE=OFF",
    "MNN_HEXAGON=${hexagon_enabled}",
    "MNN_QNN=OFF",
    "MNN_BUILD_TEST=OFF",
    "MNN_BUILD_BENCHMARK=OFF"
  ]
}
EOF

mv -f "${staging_output}/libMNN.so" "${generated_dir}/libMNN.so"
mv -f "${staging_output}/libmnn_engine_jni.so" "${generated_dir}/libmnn_engine_jni.so"
mv -f "${staging_output}/mnn_build_info.json" "${build_info_path}"
rmdir "${staging_output}"
staging_output=""

bash "${verifier_script}" "${plugin_root}"

printf 'MNN native build completed: %s\n' "${generated_dir}"
