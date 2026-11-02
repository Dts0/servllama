#!/usr/bin/env bash
set -euo pipefail

plugin_root="${1:?Plugin root is required}"
apk_path="${2:-}"
generated_dir="${plugin_root}/.native/generated/arm64-v8a"
mnn_library="${generated_dir}/libMNN.so"
jni_library="${generated_dir}/libmnn_engine_jni.so"
build_info="${generated_dir}/mnn_build_info.json"
android_ndk="${ANDROID_NDK:-${HOME}/android-ndk-r27d}"
readelf_bin="${android_ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

fail() {
    printf 'MNN artifact verification failed: %s\n' "$1" >&2
    exit 30
}

[[ -x "${readelf_bin}" ]] || fail "llvm-readelf was not found under ${android_ndk}"
[[ -s "${mnn_library}" ]] || fail "missing or empty ${mnn_library}"
[[ -s "${jni_library}" ]] || fail "missing or empty ${jni_library}"
[[ -s "${build_info}" ]] || fail "missing or empty ${build_info}"

verify_elf() {
    local artifact="$1"
    local header
    header="$("${readelf_bin}" -hW "${artifact}")"
    grep -q 'Class:[[:space:]]*ELF64' <<<"${header}" || fail "${artifact} is not ELF64"
    grep -q 'Type:[[:space:]]*DYN' <<<"${header}" || fail "${artifact} is not a shared object"
    grep -q 'Machine:[[:space:]]*AArch64' <<<"${header}" || fail "${artifact} is not AArch64"

    local load_count=0
    while read -r offset virtual_address alignment; do
        [[ -n "${alignment:-}" ]] || continue
        load_count=$((load_count + 1))
        local offset_value=$((offset))
        local address_value=$((virtual_address))
        local alignment_value=$((alignment))
        (( alignment_value >= 0x4000 )) || fail "${artifact} has LOAD alignment ${alignment}, expected at least 0x4000"
        (( (offset_value - address_value) % alignment_value == 0 )) ||
            fail "${artifact} has a LOAD segment whose offset and virtual address are not alignment-congruent"
    done < <("${readelf_bin}" -lW "${artifact}" | awk '$1 == "LOAD" { print $2, $3, $NF }')
    (( load_count > 0 )) || fail "${artifact} has no LOAD segments"
}

verify_elf "${mnn_library}"
verify_elf "${jni_library}"

"${readelf_bin}" -dW "${jni_library}" | grep -q 'Shared library: \[libMNN.so\]' ||
    fail "libmnn_engine_jni.so does not declare libMNN.so as a dependency"

required_symbols=(
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeGetVersion'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeConfigureBackends'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeDetectHexagonArchitecture'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeGetBackendCapabilities'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeTakeDiagnosticLogs'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeCreate'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeGenerate'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeCancel'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeReset'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeRelease'
)
symbols="$("${readelf_bin}" -Ws "${jni_library}")"
for symbol in "${required_symbols[@]}"; do
    grep -q "${symbol}" <<<"${symbols}" || fail "missing JNI export ${symbol}"
done

mnn_symbols="$("${readelf_bin}" --dyn-syms -W "${mnn_library}")"
for symbol in mnn_engine_set_native_log_sink mnn_engine_set_prefill_cancel_callback; do
    awk -v name="${symbol}" '$7 != "UND" && $8 == name { found = 1 } END { exit !found }' \
        <<<"${mnn_symbols}" || fail "libMNN.so is missing integration export ${symbol}"
done

python3 - "${build_info}" "${plugin_root}/MNN" <<'PY'
import json
import subprocess
import sys

build_info_path, mnn_root = sys.argv[1:]
with open(build_info_path, "r", encoding="utf-8") as stream:
    info = json.load(stream)

required = {
    "fingerprint",
    "mnnFingerprint",
    "mnnCommit",
    "ndkVersion",
    "cmakeVersion",
    "cmakePath",
    "ninjaVersion",
    "abi",
    "androidPlatform",
    "buildType",
    "nativeAdapterAbiVersion",
    "wslDistribution",
    "hostArchitecture",
    "cmakeFlags",
}
missing = sorted(required.difference(info))
if missing:
    raise SystemExit(f"mnn_build_info.json is missing fields: {', '.join(missing)}")
if info["abi"] != "arm64-v8a":
    raise SystemExit(f"unexpected ABI in build info: {info['abi']}")
if info["androidPlatform"] != "android-28":
    raise SystemExit(f"unexpected Android platform in build info: {info['androidPlatform']}")
if info["cmakeVersion"] != "3.22.1":
    raise SystemExit(f"unexpected CMake version in build info: {info['cmakeVersion']}")
if int(info["nativeAdapterAbiVersion"]) < 8:
    raise SystemExit("nativeAdapterAbiVersion must be at least 8")
flags = set(info["cmakeFlags"])
if len(flags & {"MNN_HEXAGON=ON", "MNN_HEXAGON=OFF"}) != 1:
    raise SystemExit("build info must specify exactly one MNN_HEXAGON mode")
for expected in (
    "MNN_BUILD_FOR_ANDROID_COMMAND=ON",
    "MNN_ENGINE_LOG_BRIDGE=ON",
    "MNN_ENGINE_TOKENIZER_ADDED_TOKENS=ON",
    "MNN_ENGINE_PREFILL_CANCELLATION=ON",
    "MNN_BUILD_LLM_OMNI=ON",
    "MNN_KLEIDIAI=OFF",
    "MNN_OPENCL=ON",
    "MNN_VULKAN=ON",
    "MNN_VULKAN_IMAGE=OFF",
    "ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
):
    if expected not in flags:
        raise SystemExit(f"build info is missing required flag: {expected}")
commit = subprocess.check_output(
    ["git", "-C", mnn_root, "rev-parse", "HEAD"], text=True
).strip()
if info["mnnCommit"] != commit:
    raise SystemExit(
        f"build info MNN commit {info['mnnCommit']} does not match submodule {commit}"
    )
PY

if [[ -n "${apk_path}" ]]; then
    [[ -s "${apk_path}" ]] || fail "APK does not exist: ${apk_path}"
    command -v unzip >/dev/null 2>&1 || fail "unzip is required for APK verification"
    required_entries=(
        'lib/arm64-v8a/libMNN.so'
        'lib/arm64-v8a/libmnn_engine_jni.so'
        'assets/mnn_test_page.html'
    )
    apk_entries="$(unzip -Z1 "${apk_path}")"
    for entry in "${required_entries[@]}"; do
        grep -Fxq "${entry}" <<<"${apk_entries}" || fail "APK is missing ${entry}"
    done
    packaged_dir="$(mktemp -d)"
    cleanup_packaged() {
        rm -rf -- "${packaged_dir}"
    }
    trap cleanup_packaged EXIT
    unzip -p "${apk_path}" 'lib/arm64-v8a/libMNN.so' > "${packaged_dir}/libMNN.so"
    unzip -p "${apk_path}" 'lib/arm64-v8a/libmnn_engine_jni.so' > "${packaged_dir}/libmnn_engine_jni.so"
    verify_elf "${packaged_dir}/libMNN.so"
    verify_elf "${packaged_dir}/libmnn_engine_jni.so"
    "${readelf_bin}" -dW "${packaged_dir}/libmnn_engine_jni.so" | grep -q 'Shared library: \[libMNN.so\]' ||
        fail "packaged libmnn_engine_jni.so does not depend on libMNN.so"
    packaged_symbols="$("${readelf_bin}" -Ws "${packaged_dir}/libmnn_engine_jni.so")"
    for symbol in "${required_symbols[@]}"; do
        grep -q "${symbol}" <<<"${packaged_symbols}" || fail "packaged JNI library is missing ${symbol}"
    done
    build_id() {
        "${readelf_bin}" -n "$1" | sed -n 's/.*Build ID: //p' | head -n 1
    }
    [[ -n "$(build_id "${mnn_library}")" ]] || fail "generated libMNN.so has no GNU Build ID"
    [[ -n "$(build_id "${jni_library}")" ]] || fail "generated libmnn_engine_jni.so has no GNU Build ID"
    [[ "$(build_id "${mnn_library}")" == "$(build_id "${packaged_dir}/libMNN.so")" ]] ||
        fail "packaged libMNN.so Build ID does not match the generated artifact"
    [[ "$(build_id "${jni_library}")" == "$(build_id "${packaged_dir}/libmnn_engine_jni.so")" ]] ||
        fail "packaged libmnn_engine_jni.so Build ID does not match the generated artifact"
    packaged_test_page_hash="$(unzip -p "${apk_path}" 'assets/mnn_test_page.html' | sha256sum | awk '{print $1}')"
    source_test_page_hash="$(sha256sum "${plugin_root}/android/src/main/assets/mnn_test_page.html" | awk '{print $1}')"
    [[ "${packaged_test_page_hash}" == "${source_test_page_hash}" ]] ||
        fail "packaged mnn_test_page.html does not match the plugin asset"
    python3 - "${plugin_root}/native/android-arm64-v8a.json" "${apk_path}" "${packaged_dir}" "${plugin_root}/scripts" <<'PY'
import hashlib
import json
import pathlib
import sys
import zipfile

sys.path.insert(0, sys.argv[4])
from hexagon_artifacts import validate_packaged_manifest, verify_elf

with open(sys.argv[1], encoding="utf-8") as stream:
    manifest = json.load(stream)
hexagon = manifest["runtime"]["hexagon"]
with zipfile.ZipFile(sys.argv[2]) as apk:
    stub = "lib/arm64-v8a/libMNN_htpops.so"
    dsp_names = {"libMNN_htpops_skel.so", "libc++.so.1", "libc++abi.so.1"}
    if any(name.startswith("lib/") and pathlib.PurePosixPath(name).name in dsp_names for name in apk.namelist()):
        raise SystemExit("DSP libraries must be APK assets, not Android JNI libraries")
    if hexagon["runtimePackaged"]:
        if stub not in apk.namelist():
            raise SystemExit("APK is missing the Hexagon Android stub")
        (pathlib.Path(sys.argv[3]) / "libMNN_htpops.so").write_bytes(apk.read(stub))
        prefix = "assets/mnn/hexagon/"
        manifest_bytes = apk.read(prefix + "manifest.json")
        if hashlib.sha256(manifest_bytes).hexdigest() != hexagon["assetManifestSha256"]:
            raise SystemExit("APK Hexagon manifest checksum mismatch")
        files = validate_packaged_manifest(manifest, json.loads(manifest_bytes))
        expected = {"manifest.json": hexagon["assetManifestSha256"]}
        expected.update({name: item["sha256"] for name, item in files.items()})
        actual = [name[len(prefix):] for name in apk.namelist() if name.startswith(prefix) and not name.endswith("/")]
        if set(actual) != set(expected) or len(actual) != len(expected):
            raise SystemExit("APK contains unexpected or missing Hexagon assets")
        for name, digest in expected.items():
            data = apk.read(prefix + name)
            if hashlib.sha256(data).hexdigest() != digest:
                raise SystemExit("APK Hexagon asset checksum mismatch: " + name)
            if name != "manifest.json":
                if len(data) != files[name]["sizeBytes"]:
                    raise SystemExit("APK Hexagon asset size mismatch: " + name)
                output = pathlib.Path(sys.argv[3]) / "dsp" / name
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(data)
                verify_elf(output, 164, name.split("/")[0])
    elif stub in apk.namelist() or any(name.startswith("assets/mnn/hexagon/") for name in apk.namelist()):
        raise SystemExit("APK unexpectedly contains Hexagon runtime files")
PY
    if [[ -f "${packaged_dir}/libMNN_htpops.so" ]]; then
        verify_elf "${packaged_dir}/libMNN_htpops.so"
        [[ "$(build_id "${plugin_root}/android/src/main/jniLibs/arm64-v8a/libMNN_htpops.so")" == \
           "$(build_id "${packaged_dir}/libMNN_htpops.so")" ]] || fail "packaged Hexagon stub Build ID mismatch"
        "${readelf_bin}" -dW "${packaged_dir}/libMNN_htpops.so" | grep -q 'Shared library: \[libcdsprpc.so\]' ||
            fail "packaged Hexagon stub does not depend on the OEM FastRPC driver"
        stub_symbols="$("${readelf_bin}" --dyn-syms --wide "${packaged_dir}/libMNN_htpops.so")"
        grep -q 'GLOBAL.*DEFAULT.*mnn_engine_query_hexagon_arch' <<<"${stub_symbols}" ||
            fail "packaged Hexagon stub is missing its architecture-query export"
    fi
    cleanup_packaged
    trap - EXIT
fi

printf 'MNN native artifacts verified: AArch64 ELF, 16KB LOAD alignment, JNI exports, build metadata%s\n' \
    "${apk_path:+, and APK contents}"
