#!/usr/bin/env python3
"""Validate and package SDK-built DSP assets separately from Android libraries."""
import hashlib
import json
import pathlib
import re
import shutil
import struct
import subprocess
import sys

STUB = "libMNN_htpops.so"
DSP_FILES = ("libMNN_htpops_skel.so", "libc++.so.1", "libc++abi.so.1")
ARCHITECTURES = ("v73", "v75", "v79", "v81")
STUB_ABI_VERSION = 1


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def verify_elf(path, machine, architecture=None):
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError(f"Not a little-endian ELF: {path}")
    if struct.unpack_from("<H", data, 18)[0] != machine:
        raise ValueError(f"Wrong ELF architecture: {path}")
    if struct.unpack_from("<H", data, 16)[0] != 3:
        raise ValueError(f"Expected a shared object (ET_DYN): {path}")
    if machine == 164 and data[4] != 1:
        raise ValueError(f"Expected Hexagon ELF32: {path}")
    if machine == 164 and architecture:
        flags = struct.unpack_from("<I", data, 36)[0]
        if flags & 0xff != int(architecture[1:], 16):
            raise ValueError(f"DSP architecture flags do not match {architecture}: {path}")
    if machine == 183:  # Android AArch64
        if data[4] != 2:
            raise ValueError(f"Expected ELF64: {path}")
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        loads = 0
        for index in range(count):
            entry = offset + index * entry_size
            if struct.unpack_from("<I", data, entry)[0] != 1:
                continue
            loads += 1
            file_offset, address = struct.unpack_from("<QQ", data, entry + 8)
            alignment = struct.unpack_from("<Q", data, entry + 48)[0]
            if alignment < 16384 or (file_offset - address) % alignment:
                raise ValueError(f"Android stub must have 16 KB LOAD alignment: {path}")
        if not loads:
            raise ValueError(f"No LOAD segments: {path}")


def validate_files(files):
    if set(files) != set(DSP_FILES):
        raise ValueError("Incomplete Hexagon DSP asset manifest")
    for name, item in files.items():
        if not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) or item["sizeBytes"] <= 0:
            raise ValueError(f"Invalid Hexagon asset metadata: {name}")


def validate(directory, expected_commit, expected_architecture=None):
    info = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if info["schemaVersion"] != 1 or info.get("stubAbiVersion") != STUB_ABI_VERSION:
        raise ValueError("Rebuild Hexagon artifacts with the current architecture-query stub ABI")
    if info["mnnCommit"] != expected_commit:
        raise ValueError("Hexagon artifact commit does not match the bundled MNN")
    if info["dspArchitecture"] not in ARCHITECTURES:
        raise ValueError("Unsupported Hexagon DSP architecture")
    if expected_architecture and info["dspArchitecture"] != expected_architecture:
        raise ValueError(f"Expected {expected_architecture} artifacts in {directory}")
    validate_files(info["files"])
    verify_elf(directory / STUB, 183)
    if digest(directory / STUB) != info["stubSha256"]:
        raise ValueError("Hexagon stub checksum mismatch")
    for name in DSP_FILES:
        verify_elf(directory / name, 164, info["dspArchitecture"])  # Qualcomm Hexagon (ELF32)
        if (digest(directory / name) != info["files"][name]["sha256"] or
                (directory / name).stat().st_size != info["files"][name]["sizeBytes"]):
            raise ValueError(f"Hexagon asset checksum or size mismatch: {name}")
    return info


def validate_bundle_manifest(info, expected_commit):
    if info["schemaVersion"] != 2 or info["stubAbiVersion"] != STUB_ABI_VERSION:
        raise ValueError("Unsupported multi-architecture Hexagon manifest or stub ABI")
    if info["mnnCommit"] != expected_commit:
        raise ValueError("Hexagon bundle commit does not match the bundled MNN")
    if not re.fullmatch(r"[0-9a-f]{64}", info["stubSha256"]):
        raise ValueError("Invalid Hexagon stub checksum")
    if not info["architectures"] or set(info["architectures"]) - set(ARCHITECTURES):
        raise ValueError("Unsupported architecture in the Hexagon bundle")
    for entry in info["architectures"].values():
        validate_files(entry["files"])
    return {f"{arch}/{name}": item
            for arch, entry in sorted(info["architectures"].items()) for name, item in entry["files"].items()}


def validate_packaged_manifest(native_manifest, bundle):
    files = validate_bundle_manifest(bundle, native_manifest["source"]["mnnCommit"])
    hexagon = native_manifest["runtime"]["hexagon"]
    if (hexagon["dspArchitectures"] != sorted(bundle["architectures"]) or
            hexagon["assets"] != files or hexagon["stubAbiVersion"] != bundle["stubAbiVersion"] or
            native_manifest["libraries"][STUB]["sha256"] != bundle["stubSha256"]):
        raise ValueError("Native and Hexagon bundle manifests disagree")
    return files


def describe(directory, commit, architecture, build_info=None):
    info = {
        "schemaVersion": 1,
        "stubAbiVersion": STUB_ABI_VERSION,
        "mnnCommit": commit,
        "dspArchitecture": architecture,
        "stubSha256": digest(directory / STUB),
        "files": {name: {"sha256": digest(directory / name), "sizeBytes": (directory / name).stat().st_size}
                  for name in DSP_FILES},
    }
    if build_info:
        info["build"] = json.loads(pathlib.Path(build_info).read_text(encoding="utf-8"))
    write_json(directory / "manifest.json", info)
    validate(directory, commit)


def package(plugin, source, readelf):
    manifest_path = plugin / "native/android-arm64-v8a.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    hexagon_compiled = "MNN_HEXAGON=ON" in manifest["build"]["cmakeFlags"]
    if source and not hexagon_compiled:
        raise ValueError("Rebuild MNN with MNN_HEXAGON=ON before packaging Hexagon DSP runtimes")
    assets = plugin / "android/src/main/assets/mnn/hexagon"
    stub = plugin / "android/src/main/jniLibs/arm64-v8a" / STUB
    sources = {}
    if source:
        if (source / "manifest.json").is_file():
            info = validate(source, manifest["source"]["mnnCommit"])
            sources[info["dspArchitecture"]] = (source, info)
        else:
            # 'all' means the complete supported set. Ignore unrelated build
            # experiments, but never silently produce a partial multi-arch APK.
            for arch in ARCHITECTURES:
                directory = source / arch
                sources[arch] = (directory, validate(directory, manifest["source"]["mnnCommit"], arch))
    bundle = None
    build_id = None
    if sources:
        stub_source, first = next(iter(sources.values()))
        if any(info["stubSha256"] != first["stubSha256"] for _, info in sources.values()):
            raise ValueError("Hexagon architectures must share an identical Android stub")
        bundle = {
            "schemaVersion": 2,
            "stubAbiVersion": STUB_ABI_VERSION,
            "mnnCommit": first["mnnCommit"],
            "stubSha256": first["stubSha256"],
            "architectures": {arch: {key: info[key] for key in ("files", "build") if key in info}
                              for arch, (_, info) in sources.items()},
        }
        files = validate_bundle_manifest(bundle, manifest["source"]["mnnCommit"])
        notes = subprocess.check_output([readelf, "--notes", "--dyn-syms", "--wide", str(stub_source / STUB)], text=True)
        build_id = re.search(r"Build ID:\s*([0-9a-fA-F]+)", notes)
        if not build_id:
            raise ValueError("Hexagon Android stub has no GNU Build ID")
        if not any(len(fields) >= 8 and fields[6] != "UND" and fields[4] == "GLOBAL" and
                   fields[7] == "mnn_engine_query_hexagon_arch" for fields in map(str.split, notes.splitlines())):
            raise ValueError("Hexagon Android stub is missing its architecture-query export")
    # These exact paths are generated by this script; never follow an output
    # symlink outside the plugin when replacing a previous optional bundle.
    for target in (assets, stub):
        target.resolve().relative_to(plugin.resolve())
    if assets.exists():
        shutil.rmtree(assets)
    if stub.exists():
        stub.unlink()
    manifest["libraries"].pop(STUB, None)
    manifest["runtime"] = {
        "compiledBackends": ["cpu", "opencl", "vulkan"] + (["hexagon"] if hexagon_compiled else []),
        "vulkanMode": "buffer",
        "hexagon": {"runtimePackaged": bundle is not None},
    }
    if bundle:
        assets.mkdir(parents=True)
        stub.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(stub_source / STUB, stub)
        for arch, (directory, _) in sources.items():
            (assets / arch).mkdir()
            for name in DSP_FILES:
                shutil.copy2(directory / name, assets / arch / name)
        write_json(assets / "manifest.json", bundle)
        manifest["libraries"][STUB] = {
            "sha256": digest(stub), "sizeBytes": stub.stat().st_size, "buildId": build_id.group(1).lower(),
        }
        manifest["runtime"]["hexagon"].update({
            "stubAbiVersion": STUB_ABI_VERSION,
            "dspArchitectures": sorted(sources),
            "assetManifestSha256": digest(assets / "manifest.json"),
            "assets": files,
        })
        validate_packaged_manifest(manifest, bundle)
    write_json(manifest_path, manifest)
    print("Hexagon DSP runtimes packaged: " + ", ".join(sources) if bundle
          else "Hexagon DSP runtime not packaged; compiled backends: " + ", ".join(manifest["runtime"]["compiledBackends"]))


if __name__ == "__main__":
    try:
        if sys.argv[1] == "describe":
            describe(pathlib.Path(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5] if len(sys.argv) > 5 else None)
        elif sys.argv[1] == "package":
            package(pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]) if sys.argv[3] else None, sys.argv[4])
        else:
            raise ValueError("Expected describe or package")
    except (ValueError, KeyError, OSError, struct.error) as error:
        raise SystemExit(f"Hexagon artifact error: {error}") from error
