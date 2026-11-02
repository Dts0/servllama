#!/usr/bin/env bash
set -euo pipefail

plugin_root="$(realpath "${1:?Plugin root is required}")"
dsp_target="${2:-all}"
sdk_root="${HEXAGON_SDK_ROOT:-}"
if [[ -z "${sdk_root}" || ! -f "${sdk_root}/build/cmake/hexagon_toolchain.cmake" ]]; then
    printf 'Set HEXAGON_SDK_ROOT to an installed Hexagon SDK, or use scripts/build_hexagon_docker.sh.\n' >&2
    exit 60
fi
case "${dsp_target}" in
    all) dsp_arches=(v73 v75 v79 v81) ;;
    v73|v75|v79|v81) dsp_arches=("${dsp_target}") ;;
    *) printf 'Supported FP16 HMX DSP targets: all, v73, v75, v79, v81; got: %s\n' "${dsp_target}" >&2; exit 61 ;;
esac

sdk_root="$(realpath "${sdk_root}")"
tools_root="${HEXAGON_TOOLS_ROOT:-}"
if [[ -z "${tools_root}" ]]; then
    tools_root="$(python3 - "${sdk_root}" <<'PY'
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
info = json.loads((root / "hexagon_sdk.json").read_text())
print(root / next(tool["path"] for tool in info["root"]["tools"]["info"] if tool["name"] == "Hexagon Tools"))
PY
    )"
fi
tools_root="$(realpath "${tools_root}")"
android_ndk="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-${HOME}/android-ndk-r27d}}"
cmake_bin="${MNN_HEXAGON_CMAKE:-cmake}"
jobs="${MNN_BUILD_JOBS:-4}"
compiler="${tools_root}/Tools/bin/hexagon-clang"
strip_bin="${android_ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
readelf_bin="${android_ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
for required in "${compiler}" "${strip_bin}" "${sdk_root}/ipc/fastrpc/qaic/bin/qaic"; do
    [[ -x "${required}" ]] || { printf 'Missing tool: %s\n' "${required}" >&2; exit 62; }
done
for dsp_arch in "${dsp_arches[@]}"; do
    cxx_lib_dir="${tools_root}/Tools/target/hexagon/lib/${dsp_arch}/G0/pic"
    for name in libc++.so.1 libc++abi.so.1; do
        [[ -s "${cxx_lib_dir}/${name}" ]] || { printf 'Missing DSP library: %s/%s\n' "${cxx_lib_dir}" "${name}" >&2; exit 64; }
    done
done

mnn_root="${plugin_root}/MNN"
commit="$(git -C "${mnn_root}" rev-parse HEAD)"
sdk_key="$(printf '%s\n' "${sdk_root}" "${tools_root}" "${android_ndk}" | sha256sum | cut -c1-12)"
work_root="${MNN_HEXAGON_WORK_ROOT:-${HOME}/.cache/mnn_engine/hexagon/${commit}/${sdk_key}}"
mkdir -p "${work_root}/source/backend" "${work_root}/3rd_party" "${work_root}/adapter"
work_root="$(realpath "${work_root}")"
# Use a path without spaces for SDK CMake commands and leave the submodule intact.
# Do not preserve Windows timestamps: WSL clock skew can make Ninja repeatedly
# reconfigure until copied CMake inputs are no longer dated in the future.
cp -R "${mnn_root}/source/backend/hexagon" "${work_root}/source/backend/"
cp -R "${mnn_root}/3rd_party/flatbuffers" "${work_root}/3rd_party/"
cp -R "${plugin_root}/scripts/hexagon/." "${work_root}/adapter/"

# Exercise the real SDK ABI with a simulated OEM driver before cross-compiling.
# The second executable leaves the weak capability API unresolved.
host_cc=(cc)
if ! command -v cc >/dev/null 2>&1; then
    # The Snapdragon image has Linux headers/libs but no cc command. The NDK's
    # Clang executable also targets host Linux when no Android target is set.
    host_cc=("${android_ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --sysroot=/ -fuse-ld=lld)
fi
for test_mode in driver missing_control; do
    test_flags=()
    if [[ "${test_mode}" == missing_control ]]; then test_flags+=(-DMNN_TEST_MISSING_CONTROL); fi
    "${host_cc[@]}" -std=c11 -Wall -Wextra "${test_flags[@]}" \
        -I"${sdk_root}/incs" -I"${sdk_root}/incs/stddef" \
        "${work_root}/adapter/dsp_capabilities_utils.c" "${work_root}/adapter/test_dsp_arch_query.c" \
        -o "${work_root}/arch-query-${test_mode}"
    "${work_root}/arch-query-${test_mode}"
done

common=(
    -S "${work_root}/adapter" -G Ninja -DCMAKE_BUILD_TYPE=Release
    "-DMNN_HTP_SOURCE=${work_root}/source/backend/hexagon/htp-ops-lib"
    "-DHEXAGON_SDK_ROOT=${sdk_root}"
    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON -DCMAKE_WARN_DEPRECATED=OFF
)
"${cmake_bin}" "${common[@]}" -B "${work_root}/android" \
    "-DCMAKE_TOOLCHAIN_FILE=${android_ndk}/build/cmake/android.toolchain.cmake" \
    "-DANDROID_NDK=${android_ndk}" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=none -DOS_TYPE=HLOS -DDSP_TYPE=3 -DPREBUILT_LIB_DIR=android_aarch64 \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
    "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${work_root}/android/ship"
"${cmake_bin}" --build "${work_root}/android" --parallel "${jobs}"

tools_major="$(basename "${tools_root}" | cut -d. -f1)"
for dsp_arch in "${dsp_arches[@]}"; do
    dsp_build="${work_root}/dsp/${dsp_arch}"
    cxx_lib_dir="${tools_root}/Tools/target/hexagon/lib/${dsp_arch}/G0/pic"
    "${cmake_bin}" "${common[@]}" -B "${dsp_build}" \
        "-DCMAKE_TOOLCHAIN_FILE=${sdk_root}/build/cmake/hexagon_toolchain.cmake" \
        "-DHEXAGON_TOOLS_ROOT=${tools_root}" \
        -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY -DOS_TYPE=DSP \
        "-DDSP_ARCH=${dsp_arch}" "-DDSP_VERSION=${dsp_arch}" \
        "-DPREBUILT_LIB_DIR=hexagon_toolv${tools_major}_${dsp_arch}" -DQURT_OS=1 \
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${dsp_build}/ship"
    "${cmake_bin}" --build "${dsp_build}" --parallel "${jobs}"

    output="${plugin_root}/.native/hexagon/${dsp_arch}"
    mkdir -p "${output}"
    # SDK runtime files are read-only; install replaceable copies on DrvFS.
    # Every DSP target receives the exact same host stub from the single build.
    install -m 644 "${work_root}/android/ship/libMNN_htpops.so" "${output}/"
    install -m 644 "${dsp_build}/ship/libMNN_htpops_skel.so" "${output}/"
    install -m 644 "${cxx_lib_dir}/libc++.so.1" "${cxx_lib_dir}/libc++abi.so.1" "${output}/"
    "${strip_bin}" --strip-unneeded "${output}/libMNN_htpops.so"
    python3 "${plugin_root}/scripts/verify_hexagon_link.py" "${output}" "${cxx_lib_dir}" "${readelf_bin}" "${output}/link-report.json"
    python3 - "${plugin_root}" "${output}" "${sdk_root}" "${tools_root}" "${android_ndk}" "${cmake_bin}" <<'PY'
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
plugin, output, sdk, tools, ndk = map(pathlib.Path, sys.argv[1:6])
sdk_info = json.loads((sdk / "hexagon_sdk.json").read_text())
sources = [plugin / "scripts" / name for name in (
    "build_hexagon_android.sh", "build_hexagon_docker.sh", "verify_hexagon_link.py", "hexagon_artifacts.py")]
sources.extend(sorted((plugin / "scripts/hexagon").glob("*")))
info = {
    "sdkVersion": sdk_info["root"]["id"]["version"],
    "compilerVersion": subprocess.check_output([str(tools / "Tools/bin/hexagon-clang"), "--version"], text=True).splitlines()[0],
    "ndkVersion": re.search(r"Pkg.Revision\s*=\s*(\S+)", (ndk / "source.properties").read_text())[1],
    "cmakeVersion": subprocess.check_output([sys.argv[6], "--version"], text=True).splitlines()[0],
    "androidPlatform": "android-28",
    "architectureQueryTests": ["sdk_headers_mock_driver", "missing_capability_api"],
    "containerImage": os.environ.get("MNN_HEXAGON_BUILD_IMAGE"),
    "containerImageId": os.environ.get("MNN_HEXAGON_BUILD_IMAGE_ID"),
    "buildInputs": {str(path.relative_to(plugin)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
    "linkVerification": json.loads((output / "link-report.json").read_text()),
}
(output / "build-info.json").write_text(json.dumps(info, indent=2, sort_keys=True) + "\n")
PY
    python3 "${plugin_root}/scripts/hexagon_artifacts.py" describe "${output}" "${commit}" "${dsp_arch}" "${output}/build-info.json"
done
package_source="${plugin_root}/.native/hexagon"
if [[ "${dsp_target}" != all ]]; then package_source+="/${dsp_target}"; fi
printf 'Hexagon artifacts prepared: %s\nPackage with MNN_HEXAGON_ARTIFACTS=%q bash scripts/package_mnn_artifacts.sh %q\n' \
    "${dsp_arches[*]}" "${package_source}" "${plugin_root}"
