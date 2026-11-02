"""Audit a built ServLlama APK, without installing it or loading native code.

Checks ABI, ELF LOAD alignment, pinned speech/MNN and official llama.cpp hashes,
runtime dependencies, the embedded manifest/license and unchanged Hexagon DSP
kernels. Android manifest version, signing and device execution remain separate.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

from prepare_llama_runtime import (
    ANDROID_SYSTEM, BUILD, COMMIT, DSP_NAMES, VARIANTS, VERSION,
    elf_metadata, runtime_library,
)


ROOT = Path(__file__).resolve().parents[1]
MNN_VERSION = "3.6.2"
MNN_COMMIT = "7e3c6fb5225d3ccf22807ee641332669df34650d"
MNN_MANIFEST_ASSET = "packages/mnn_engine/native/android-arm64-v8a.json"
REQUIRED = {
    "libflutter.so",
    "libllama-server.so",
    "libMNN.so",
    "libmnn_engine_jni.so",
    "libsherpa-onnx-c-api.so",
    "libonnxruntime.so",
    "libservllama_crispasr.so",
    "libsqlite3.so",
    "libminicpm-server.so",
}


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def elf_loads(data):
    if data[:4] != b"\x7fELF" or data[5:6] != b"\x01":
        raise ValueError("Expected a little-endian ELF")
    machine = struct.unpack_from("<H", data, 18)[0]
    if data[4] == 2:
        table = struct.unpack_from("<Q", data, 32)[0]
        stride, count = struct.unpack_from("<HH", data, 54)
        header_format = "<IIQQQQQQ"
        offset_index, address_index, align_index = 2, 3, 7
    elif data[4] == 1:
        table = struct.unpack_from("<I", data, 28)[0]
        stride, count = struct.unpack_from("<HH", data, 42)
        header_format = "<IIIIIIII"
        offset_index, address_index, align_index = 1, 2, 7
    else:
        raise ValueError("Unsupported ELF class")
    if stride < struct.calcsize(header_format) or count == 0:
        raise ValueError("Invalid program header table")
    loads = []
    for index in range(count):
        header = struct.unpack_from(header_format, data, table + index * stride)
        if header[0] == 1:
            loads.append({
                "offset": header[offset_index],
                "address": header[address_index],
                "alignment": header[align_index],
            })
    if not loads:
        raise ValueError("ELF has no LOAD segments")
    return machine, loads


def audit(apk, manifest_path, jni_directory, llama_manifest_path=None,
          mnn_manifest_path=None):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    pinned = manifest["library"]
    llama_manifest_path = llama_manifest_path or ROOT / "assets/bin/llama_server_manifest.json"
    llama = json.loads(llama_manifest_path.read_text(encoding="utf-8"))
    mnn_manifest_path = mnn_manifest_path or ROOT / MNN_MANIFEST_ASSET
    mnn = json.loads(mnn_manifest_path.read_text(encoding="utf-8"))
    mnn_records = mnn["libraries"]
    mini_path = ROOT / "assets/bin/minicpm_server_manifest.json"
    mini = json.loads(mini_path.read_text(encoding="utf-8"))
    mini_records = {item["name"]: item for item in mini["libraries"]}
    variant = llama["variant"]
    spec = VARIANTS[variant]
    llama_records = {record["name"]: record for record in llama["libraries"]}
    errors, libraries = [], []
    if set(mini_records) != {"libminicpm-server.so"} or mini.get("abi") != "arm64-v8a":
        errors.append("MiniCPM runtime must be a single isolated ARM64 executable")
    if (mnn["source"]["mnnVersion"] != MNN_VERSION or
            mnn["source"]["mnnCommit"] != MNN_COMMIT or
            mnn["target"]["abi"] != "arm64-v8a" or
            mnn["target"]["minimumAndroidApi"] != 28 or
            mnn["build"]["nativeAdapterAbiVersion"] != 8 or
            set(mnn["runtime"]["compiledBackends"]) != {"cpu", "opencl", "vulkan"} or
            mnn["runtime"]["vulkanMode"] != "buffer" or
            mnn["runtime"]["hexagon"]["runtimePackaged"] is not False):
        errors.append("MNN provenance/backends do not match the pinned 3.6.2 build")
    if set(mnn_records) != {"libMNN.so", "libmnn_engine_jni.so"}:
        errors.append("Unexpected MNN native library set")
    if (llama["version"] != VERSION or llama["source"]["commit"] != COMMIT or
            llama["source"]["tag"] != VERSION or llama["source"]["buildTag"] != BUILD or
            llama["minSdk"] != spec["minSdk"] or
            llama["artifact"]["sha256"] != spec["sha256"] or
            llama["artifact"].get("unmodified") is not True):
        errors.append("llama.cpp provenance does not match the pinned official release")
    if set(llama_records) != spec["required"] or len(llama_records) != len(llama["libraries"]):
        errors.append("Runtime manifest libraries do not match the selected official variant")
    with zipfile.ZipFile(apk) as archive:
        try:
            embedded_mini = json.loads(archive.read(
                "assets/flutter_assets/assets/bin/minicpm_server_manifest.json"))
            if embedded_mini != mini:
                errors.append("Embedded MiniCPM manifest differs from build manifest")
            license_info = mini["license"]
            license_data = archive.read("assets/flutter_assets/" + license_info["asset"])
            if hashlib.sha256(license_data).hexdigest() != license_info["sha256"]:
                errors.append("MiniCPM license checksum mismatch")
        except (KeyError, ValueError) as error:
            errors.append(f"Missing or invalid MiniCPM manifest/license: {error}")
        try:
            if json.loads(archive.read("assets/flutter_assets/" + MNN_MANIFEST_ASSET)) != mnn:
                errors.append("Embedded MNN manifest differs from build manifest")
        except (KeyError, ValueError) as error:
            errors.append(f"Missing or invalid MNN manifest: {error}")
        asset = "assets/flutter_assets/assets/bin/llama_server_manifest.json"
        try:
            if json.loads(archive.read(asset)) != llama:
                errors.append("Embedded llama.cpp manifest differs from build manifest")
            license_data = archive.read("assets/flutter_assets/" + llama["license"]["asset"])
            if hashlib.sha256(license_data).hexdigest() != llama["license"]["sha256"]:
                errors.append("Embedded llama.cpp license differs from official license")
        except (KeyError, ValueError) as error:
            errors.append(f"Missing or invalid llama.cpp manifest/license: {error}")
        entries = [entry for entry in archive.infolist()
                   if entry.filename.startswith("lib/") and not entry.is_dir()]
        names = [entry.filename for entry in entries]
        abis = sorted({name.split("/")[1] for name in names})
        if abis != ["arm64-v8a"]:
            errors.append(f"Unexpected APK ABIs: {abis}")
        if len(names) != len(set(names)):
            errors.append("Duplicate native ZIP entries")
        packaged_names = {name.split("/")[-1] for name in names}
        missing = (REQUIRED | spec["required"]) - packaged_names
        if missing:
            errors.append(f"Missing libraries: {sorted(missing)}")
        extra_runtime = {name for name in packaged_names if runtime_library(name)} - spec["required"]
        if extra_runtime:
            errors.append(f"Unexpected/mixed llama.cpp libraries: {sorted(extra_runtime)}")
        for entry in entries:
            name = entry.filename.split("/")[-1]
            data = archive.read(entry)
            digest = hashlib.sha256(data).hexdigest()
            record = {"path": entry.filename, "bytes": len(data), "sha256": digest,
                      "zip_compression": entry.compress_type}
            try:
                machine, loads = elf_loads(data)
                record.update(machine=machine, load_segments=loads)
                if name.startswith("libggml-htp-") and name.endswith(".so"):
                    original = jni_directory / name
                    if machine != 164:
                        errors.append(f"{name}: expected Hexagon ELF")
                    if not original.is_file() or sha256_file(original) != digest:
                        errors.append(f"{name}: missing source or DSP bytes changed")
                    record["dsp_source_matches"] = original.is_file() and (
                        sha256_file(original) == digest)
                else:
                    if machine != 183:
                        errors.append(f"{name}: expected AArch64 ELF")
                    if any(load["alignment"] < 16384 or
                           (load["address"] - load["offset"]) % 16384
                           for load in loads):
                        errors.append(f"{name}: LOAD segments are not 16 KiB aligned")
                if name == pinned["name"] and (
                        digest != pinned["sha256"] or len(data) != pinned["bytes"]):
                    errors.append(f"{name}: differs from pinned build manifest")
                if name in mnn_records:
                    expected = mnn_records[name]
                    matches = digest == expected["sha256"] and len(data) == expected["sizeBytes"]
                    record["mnn_source_matches"] = matches
                    if not matches:
                        errors.append(f"{name}: differs from pinned MNN build manifest")
                    dynamic = elf_metadata(data)
                    record["needed"] = dynamic["needed"]
                    unresolved = set(dynamic["needed"]) - (set(mnn_records) | ANDROID_SYSTEM)
                    if unresolved:
                        errors.append(f"{name}: unresolved MNN dependencies: {sorted(unresolved)}")
                if name in mini_records:
                    expected = mini_records[name]
                    matches = digest == expected["sha256"] and len(data) == expected["bytes"]
                    record["minicpm_source_matches"] = matches
                    if not matches:
                        errors.append(f"{name}: differs from pinned MiniCPM manifest")
                    dynamic = elf_metadata(data)
                    record["needed"] = dynamic["needed"]
                    # The fork is statically linked; it may not resolve official llama/ggml libraries.
                    unresolved = set(dynamic["needed"]) - ANDROID_SYSTEM
                    if unresolved:
                        errors.append(f"{name}: non-system dependencies break isolation: {sorted(unresolved)}")
                if name in llama_records:
                    expected = llama_records[name]
                    matches = digest == expected["sha256"] and len(data) == expected["bytes"]
                    record["official_source_matches"] = matches
                    if not matches:
                        errors.append(f"{name}: differs from unmodified official runtime")
                    dynamic = elf_metadata(data)
                    record["needed"] = dynamic["needed"]
                    allowed = {"libc.so", "libgcc.so"} if name in DSP_NAMES else (
                        spec["required"] | ANDROID_SYSTEM | spec["external"])
                    unresolved = set(dynamic["needed"]) - allowed
                    if unresolved:
                        errors.append(f"{name}: unresolved dependencies: {sorted(unresolved)}")
            except (IndexError, ValueError, struct.error) as error:
                errors.append(f"{name}: {error}")
            libraries.append(record)
    return {"apk": str(apk), "bytes": apk.stat().st_size,
            "sha256": sha256_file(apk), "abis": abis,
            "llama_version": llama["version"], "llama_variant": variant,
            "mnn_version": mnn["source"]["mnnVersion"],
            "mnn_commit": mnn["source"]["mnnCommit"],
            "mnn_backends": mnn["runtime"]["compiledBackends"],
            "runtime_min_sdk": llama["minSdk"],
            "external_dependencies": sorted(spec["external"]),
            "libraries": libraries, "errors": errors, "passed": not errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--manifest", type=Path,
                        default=ROOT / "native/speech/android-arm64-v8a.json")
    parser.add_argument("--jni-dir", type=Path,
                        default=ROOT / "android/app/src/main/jniLibs/arm64-v8a")
    parser.add_argument("--llama-manifest", type=Path,
                        default=ROOT / "assets/bin/llama_server_manifest.json")
    parser.add_argument("--mnn-manifest", type=Path,
                        default=ROOT / MNN_MANIFEST_ASSET)
    args = parser.parse_args()
    try:
        report = audit(args.apk, args.manifest, args.jni_dir, args.llama_manifest,
                       args.mnn_manifest)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(2, f"Cannot audit APK: {error}\n")
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
