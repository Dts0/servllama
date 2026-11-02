#!/usr/bin/env bash
set -euo pipefail

plugin_root="${1:?Plugin root is required}"
generated_dir="${plugin_root}/.native/generated/arm64-v8a"
bundled_dir="${plugin_root}/android/src/main/jniLibs/arm64-v8a"
build_info="${generated_dir}/mnn_build_info.json"
manifest_dir="${plugin_root}/native"
manifest_path="${manifest_dir}/android-arm64-v8a.json"
verifier_script="${plugin_root}/scripts/verify_mnn_artifacts.sh"
android_ndk="${ANDROID_NDK:-${HOME}/android-ndk-r27d}"
toolchain_bin="${android_ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin"
strip_bin="${toolchain_bin}/llvm-strip"
readelf_bin="${toolchain_bin}/llvm-readelf"

fail() {
    printf 'MNN artifact packaging failed: %s\n' "$1" >&2
    exit 40
}

[[ -f "${verifier_script}" ]] || fail "missing ${verifier_script}"
[[ -x "${strip_bin}" ]] || fail "llvm-strip was not found under ${android_ndk}"
[[ -x "${readelf_bin}" ]] || fail "llvm-readelf was not found under ${android_ndk}"
[[ -s "${build_info}" ]] || fail "missing ${build_info}"

bash "${verifier_script}" "${plugin_root}"

# Reject a mismatched opt-in before replacing the current libraries/manifest.
if [[ -n "${MNN_HEXAGON_ARTIFACTS:-}" ]] && ! grep -Fq '"MNN_HEXAGON=ON"' "${build_info}"; then
    fail "Rebuild with MNN_HEXAGON=ON before packaging Hexagon DSP runtimes"
fi

mkdir -p "${bundled_dir}" "${manifest_dir}"
install -m 0644 "${generated_dir}/libMNN.so" "${bundled_dir}/libMNN.so"
install -m 0644 "${generated_dir}/libmnn_engine_jni.so" "${bundled_dir}/libmnn_engine_jni.so"

"${strip_bin}" --strip-unneeded "${bundled_dir}/libMNN.so"
"${strip_bin}" --strip-unneeded "${bundled_dir}/libmnn_engine_jni.so"

for artifact in "${bundled_dir}/libMNN.so" "${bundled_dir}/libmnn_engine_jni.so"; do
    header="$("${readelf_bin}" -hW "${artifact}")"
    grep -q 'Class:[[:space:]]*ELF64' <<<"${header}" || fail "${artifact} is not ELF64"
    grep -q 'Type:[[:space:]]*DYN' <<<"${header}" || fail "${artifact} is not a shared object"
    grep -q 'Machine:[[:space:]]*AArch64' <<<"${header}" || fail "${artifact} is not AArch64"

    load_count=0
    while read -r offset virtual_address alignment; do
        [[ -n "${alignment:-}" ]] || continue
        load_count=$((load_count + 1))
        offset_value=$((offset))
        address_value=$((virtual_address))
        alignment_value=$((alignment))
        (( alignment_value >= 0x4000 )) ||
            fail "${artifact} has LOAD alignment ${alignment}, expected at least 0x4000"
        (( (offset_value - address_value) % alignment_value == 0 )) ||
            fail "${artifact} has a LOAD segment with incongruent offset and virtual address"
    done < <("${readelf_bin}" -lW "${artifact}" | awk '$1 == "LOAD" { print $2, $3, $NF }')
    (( load_count > 0 )) || fail "${artifact} has no LOAD segments"
done

"${readelf_bin}" -dW "${bundled_dir}/libmnn_engine_jni.so" | grep -q 'Shared library: \[libMNN.so\]' ||
    fail "bundled libmnn_engine_jni.so does not depend on libMNN.so"

required_symbols=(
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeGetVersion'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeConfigureBackends'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeDetectHexagonArchitecture'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeGetBackendCapabilities'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeCreate'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeGenerate'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeCancel'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeReset'
    'Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeRelease'
)
symbols="$("${readelf_bin}" -Ws "${bundled_dir}/libmnn_engine_jni.so")"
for symbol in "${required_symbols[@]}"; do
    grep -q "${symbol}" <<<"${symbols}" || fail "bundled JNI library is missing ${symbol}"
done

manifest_tmp="${manifest_path}.tmp"
cleanup_manifest() {
    rm -f -- "${manifest_tmp}"
}
trap cleanup_manifest EXIT

python3 - \
    "${build_info}" \
    "${plugin_root}/MNN/include/MNN/MNNDefine.h" \
    "${readelf_bin}" \
    "${bundled_dir}/libMNN.so" \
    "${bundled_dir}/libmnn_engine_jni.so" \
    "${manifest_tmp}" <<'PY'
import hashlib
import json
import pathlib
import re
import subprocess
import sys

build_info_path, version_header_path, readelf, mnn_path, jni_path, output_path = sys.argv[1:]

with open(build_info_path, "r", encoding="utf-8") as stream:
    build_info = json.load(stream)

version_header = pathlib.Path(version_header_path).read_text(encoding="utf-8")

def version_component(name: str) -> str:
    match = re.search(rf"#define\s+{name}\s+(\d+)", version_header)
    if match is None:
        raise SystemExit(f"Unable to read {name} from {version_header_path}")
    return match.group(1)

def artifact_info(path_text: str) -> dict[str, object]:
    path = pathlib.Path(path_text)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    notes = subprocess.check_output([readelf, "-n", str(path)], text=True)
    build_id_match = re.search(r"Build ID:\s*([0-9a-fA-F]+)", notes)
    if build_id_match is None:
        raise SystemExit(f"No GNU Build ID found in {path}")
    return {
        "buildId": build_id_match.group(1).lower(),
        "sha256": digest,
        "sizeBytes": path.stat().st_size,
    }

mnn_version = ".".join(
    version_component(name)
    for name in ("MNN_VERSION_MAJOR", "MNN_VERSION_MINOR", "MNN_VERSION_PATCH")
)

manifest = {
    "schemaVersion": 1,
    "source": {
        "mnnCommit": build_info["mnnCommit"],
        "mnnRepository": "https://github.com/alibaba/MNN",
        "mnnVersion": mnn_version,
    },
    "target": {
        "abi": build_info["abi"],
        "androidPlatform": build_info["androidPlatform"],
        "minimumAndroidApi": 28,
    },
    "toolchain": {
        "cmakeVersion": build_info["cmakeVersion"],
        "ndkVersion": build_info["ndkVersion"],
        "ninjaVersion": build_info["ninjaVersion"],
    },
    "build": {
        "buildType": build_info["buildType"],
        "cmakeFlags": build_info["cmakeFlags"],
        "fingerprint": build_info["fingerprint"],
        "mnnFingerprint": build_info["mnnFingerprint"],
        "nativeAdapterAbiVersion": build_info["nativeAdapterAbiVersion"],
    },
    "libraries": {
        "libMNN.so": artifact_info(mnn_path),
        "libmnn_engine_jni.so": artifact_info(jni_path),
    },
}

with open(output_path, "w", encoding="utf-8", newline="\n") as stream:
    json.dump(manifest, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")
PY

mv -f "${manifest_tmp}" "${manifest_path}"
trap - EXIT

python3 "${plugin_root}/scripts/hexagon_artifacts.py" package \
    "${plugin_root}" "${MNN_HEXAGON_ARTIFACTS:-}" "${readelf_bin}"

printf 'Bundled MNN native artifacts updated:\n'
printf '  %s\n' "${bundled_dir}/libMNN.so"
printf '  %s\n' "${bundled_dir}/libmnn_engine_jni.so"
printf '  %s\n' "${manifest_path}"
