"""Offline tests: synthetic ELF/archive fixtures, never execute native code."""

import hashlib
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import prepare_llama_runtime as runtime
import verify_android_bundle as bundle


def elf(machine=183, alignment=16384, dependency=None):
    data = bytearray(512)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2 if dependency else 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    if dependency:
        struct.pack_into("<IIQQQQQQ", data, 120, 2, 6, 200, 200, 200, 48, 48, 8)
        struct.pack_into("<qQqQqQ", data, 200, 5, 300, 1, 1, 0, 0)
        encoded = dependency.encode("ascii") + b"\0"
        data[301:301 + len(encoded)] = encoded
    return bytes(data)


class RuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, variant="cpu", omitted=None, extra=None, dependency=None):
        path = self.root / f"{variant}.tar.gz"
        with tarfile.open(path, "w:gz") as archive:
            for name in sorted(runtime.VARIANTS[variant]["required"] | {"LICENSE"}):
                if name == omitted:
                    continue
                source_name = "llama-server" if name == "libllama-server.so" else name
                data = (b"MIT test license\n" if name == "LICENSE" else
                        elf(164 if name in runtime.DSP_NAMES else 183,
                            4096 if name in runtime.DSP_NAMES else 16384,
                            dependency if name == "libggml.so" else None))
                member = tarfile.TarInfo(f"llama-b11960/{source_name}")
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            if extra:
                archive.addfile(extra, io.BytesIO(b""))
        return path

    def prepare(self, variant="cpu"):
        archive = self.archive(variant)
        digest = runtime.sha256_file(archive)
        override = patch.dict(runtime.VARIANTS[variant], {"sha256": digest})
        override.start()
        self.addCleanup(override.stop)
        manifest = runtime.prepare(archive, variant, self.root)
        return manifest

    def make_apk(self, variant="cpu", mutate=None, extra=None, mnn_version=None,
                 mnn_embedded=True, mnn_dependency=None):
        manifest = self.prepare(variant)
        jni = self.root / "android/app/src/main/jniLibs/arm64-v8a"
        speech = elf()
        speech_manifest = self.root / "speech.json"
        speech_manifest.write_text(json.dumps({"library": {
            "name": "libservllama_crispasr.so", "bytes": len(speech),
            "sha256": hashlib.sha256(speech).hexdigest(),
        }}))
        mnn_bytes = elf(dependency=mnn_dependency)
        mnn_manifest = self.root / "mnn.json"
        mnn = {
            "source": {"mnnVersion": mnn_version or bundle.MNN_VERSION,
                       "mnnCommit": bundle.MNN_COMMIT},
            "target": {"abi": "arm64-v8a", "minimumAndroidApi": 28},
            "build": {"nativeAdapterAbiVersion": 8},
            "runtime": {"compiledBackends": ["cpu", "opencl", "vulkan"],
                        "vulkanMode": "buffer", "hexagon": {"runtimePackaged": False}},
            "libraries": {name: {"sha256": hashlib.sha256(mnn_bytes).hexdigest(),
                                 "sizeBytes": len(mnn_bytes)}
                          for name in ("libMNN.so", "libmnn_engine_jni.so")},
        }
        mnn_manifest.write_text(json.dumps(mnn))
        apk = self.root / "test.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name in sorted(bundle.REQUIRED | runtime.VARIANTS[variant]["required"]):
                data = (jni / name).read_bytes() if (jni / name).exists() else speech
                if name in mnn["libraries"]:
                    data = mnn_bytes
                if name == mutate:
                    data += b"modified"
                archive.writestr(f"lib/arm64-v8a/{name}", data)
            if extra:
                archive.writestr(f"lib/arm64-v8a/{extra}", elf())
            archive.writestr("assets/flutter_assets/assets/bin/llama_server_manifest.json",
                             json.dumps(manifest))
            archive.writestr("assets/flutter_assets/assets/licenses/llama-cpp-native.txt",
                             (self.root / "assets/licenses/llama-cpp-native.txt").read_bytes())
            if mnn_embedded:
                archive.writestr("assets/flutter_assets/" + bundle.MNN_MANIFEST_ASSET,
                                 json.dumps(mnn))
        return bundle.audit(apk, speech_manifest, jni,
                            self.root / "assets/bin/llama_server_manifest.json", mnn_manifest)

    def test_checksum_mismatch_writes_nothing(self):
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            runtime.prepare(self.archive(), root=self.root)
        self.assertFalse((self.root / "android").exists())

    def test_missing_required_file(self):
        with self.assertRaisesRegex(ValueError, "Missing runtime files"):
            runtime.read_payload(self.archive(omitted="libllama.so"), "cpu")

    def test_rejects_archive_traversal(self):
        member = tarfile.TarInfo("llama-b11960/../../outside")
        with self.assertRaisesRegex(ValueError, "Unsafe archive member"):
            runtime.read_payload(self.archive(extra=member), "cpu")
        self.assertFalse((self.root.parent / "outside").exists())

    def test_rejects_runtime_symlink(self):
        member = tarfile.TarInfo("llama-b11960/libllama.so")
        member.type, member.linkname = tarfile.SYMTYPE, "../../outside"
        with self.assertRaisesRegex(ValueError, "regular file"):
            runtime.read_payload(self.archive(omitted="libllama.so", extra=member), "cpu")

    def test_rejects_duplicate_runtime(self):
        member = tarfile.TarInfo("llama-b11960/subdir/libllama.so")
        with self.assertRaisesRegex(ValueError, "Duplicate runtime file"):
            runtime.read_payload(self.archive(extra=member), "cpu")

    def test_unresolved_native_dependency(self):
        payload = runtime.read_payload(self.archive(dependency="libmissing.so"), "cpu")
        with self.assertRaisesRegex(ValueError, "unpackaged dependencies"):
            runtime.verify_payload(payload, "cpu")

    def test_elf_dynamic_dependencies(self):
        self.assertEqual(runtime.elf_metadata(elf(dependency="libc.so"))["needed"], ["libc.so"])

    def test_rejects_bad_arm_alignment(self):
        payload = runtime.read_payload(self.archive(), "cpu")
        payload["libllama.so"] = ("test", elf(alignment=4096))
        with self.assertRaisesRegex(ValueError, "16 KiB"):
            runtime.verify_payload(payload, "cpu")

    def test_switch_cleans_only_owned_runtime(self):
        jni = self.root / "android/app/src/main/jniLibs/arm64-v8a"
        jni.mkdir(parents=True)
        (jni / "libggml-hexagon.so").write_bytes(b"stale")
        (jni / "libservllama_crispasr.so").write_bytes(b"keep")
        manifest = self.prepare()
        self.assertFalse((jni / "libggml-hexagon.so").exists())
        self.assertEqual((jni / "libservllama_crispasr.so").read_bytes(), b"keep")
        self.assertEqual(manifest["version"], "v0.8.0")
        self.assertEqual(manifest["minSdk"], 28)

    def test_cpu_audit_does_not_require_hexagon(self):
        report = self.make_apk()
        self.assertTrue(report["passed"], report["errors"])
        self.assertEqual(report["external_dependencies"], [])

    def test_snapdragon_audit_accepts_all_five_dsp_kernels(self):
        report = self.make_apk("snapdragon")
        self.assertTrue(report["passed"], report["errors"])
        self.assertEqual(report["runtime_min_sdk"], 34)
        self.assertEqual(report["external_dependencies"], ["libOpenCL.so"])
        self.assertEqual(sum(x.get("machine") == 164 for x in report["libraries"]), 5)

    def test_audit_rejects_modified_official_bytes(self):
        report = self.make_apk(mutate="libllama.so")
        self.assertFalse(report["passed"])
        self.assertTrue(any("unmodified official runtime" in x for x in report["errors"]))

    def test_audit_rejects_mixed_runtime(self):
        report = self.make_apk(extra="libggml-hexagon.so")
        self.assertFalse(report["passed"])
        self.assertTrue(any("mixed llama.cpp" in x for x in report["errors"]))

    def test_audit_verifies_mnn_362(self):
        report = self.make_apk()
        self.assertEqual(report["mnn_version"], "3.6.2")
        self.assertEqual(sum(x.get("mnn_source_matches", False)
                             for x in report["libraries"]), 2)

    def test_audit_rejects_old_mnn_version(self):
        report = self.make_apk(mnn_version="3.6.1")
        self.assertFalse(report["passed"])
        self.assertTrue(any("MNN provenance" in x for x in report["errors"]))

    def test_audit_rejects_modified_mnn_bytes(self):
        report = self.make_apk(mutate="libMNN.so")
        self.assertFalse(report["passed"])
        self.assertTrue(any("pinned MNN build" in x for x in report["errors"]))

    def test_audit_requires_mnn_manifest_asset(self):
        report = self.make_apk(mnn_embedded=False)
        self.assertFalse(report["passed"])
        self.assertTrue(any("Missing or invalid MNN manifest" in x for x in report["errors"]))

    def test_audit_rejects_missing_mnn_dependency(self):
        report = self.make_apk(mnn_dependency="libmissing.so")
        self.assertFalse(report["passed"])
        self.assertTrue(any("unresolved MNN dependencies" in x for x in report["errors"]))


if __name__ == "__main__":
    unittest.main()
