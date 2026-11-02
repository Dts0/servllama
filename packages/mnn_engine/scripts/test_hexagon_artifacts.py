import json
import pathlib
import struct
import tempfile
import unittest
from unittest.mock import patch

import hexagon_artifacts as artifacts


def elf_header(machine, alignment=16384, architecture="v73"):
    data = bytearray(256)
    data[:7] = b"\x7fELF" + bytes([2 if machine == 183 else 1, 1, 1])
    struct.pack_into("<H", data, 16, 3)
    struct.pack_into("<H", data, 18, machine)
    if machine == 164:
        struct.pack_into("<I", data, 36, int(architecture[1:], 16))
    if machine == 183:
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<I", data, 64, 1)
        struct.pack_into("<Q", data, 64 + 48, alignment)
    return data


class HexagonArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        (self.root / artifacts.STUB).write_bytes(elf_header(183))
        for name in artifacts.DSP_FILES:
            (self.root / name).write_bytes(elf_header(164))
        artifacts.describe(self.root, "test-commit", "v73")

    def all_sources(self):
        source = self.root / "all"
        for arch in artifacts.ARCHITECTURES:
            directory = source / arch
            directory.mkdir(parents=True)
            (directory / artifacts.STUB).write_bytes(elf_header(183))
            for name in artifacts.DSP_FILES:
                (directory / name).write_bytes(elf_header(164, architecture=arch))
            artifacts.describe(directory, "test-commit", arch)
        return source

    def plugin(self, hexagon=True):
        plugin = self.root / "plugin"
        (plugin / "native").mkdir(parents=True)
        artifacts.write_json(plugin / "native/android-arm64-v8a.json", {
            "source": {"mnnCommit": "test-commit"}, "libraries": {},
            "build": {"cmakeFlags": ["MNN_HEXAGON=ON" if hexagon else "MNN_HEXAGON=OFF"]},
        })
        return plugin

    def test_rejects_dsp_binaries_in_the_android_stub_slot(self):
        (self.root / artifacts.STUB).write_bytes(elf_header(164))
        with self.assertRaisesRegex(ValueError, "architecture"):
            artifacts.validate(self.root, "test-commit")

    def test_rejects_four_kilobyte_android_stub_alignment(self):
        (self.root / artifacts.STUB).write_bytes(elf_header(183, 4096))
        with self.assertRaisesRegex(ValueError, "16 KB"):
            artifacts.validate(self.root, "test-commit")

    def test_rejects_mismatched_source_revisions_and_dsp_checksums(self):
        with self.assertRaisesRegex(ValueError, "commit"):
            artifacts.validate(self.root, "another-commit")
        with (self.root / artifacts.DSP_FILES[0]).open("ab") as output:
            output.write(b"modified")
        with self.assertRaisesRegex(ValueError, "checksum"):
            artifacts.validate(self.root, "test-commit")

    def test_requires_the_complete_dsp_dependency_set(self):
        manifest = json.loads((self.root / "manifest.json").read_text())
        manifest["files"].pop("libc++abi.so.1")
        artifacts.write_json(self.root / "manifest.json", manifest)
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            artifacts.validate(self.root, "test-commit")

    def test_rejects_mislabeled_dsp_architecture(self):
        data = elf_header(164)
        struct.pack_into("<I", data, 36, 0x79)
        (self.root / artifacts.DSP_FILES[0]).write_bytes(data)
        with self.assertRaisesRegex(ValueError, "architecture flags"):
            artifacts.describe(self.root, "test-commit", "v73")

    def test_rejects_executables_disguised_as_libraries(self):
        data = elf_header(183)
        struct.pack_into("<H", data, 16, 2)
        (self.root / artifacts.STUB).write_bytes(data)
        with self.assertRaisesRegex(ValueError, "ET_DYN"):
            artifacts.describe(self.root, "test-commit", "v73")

    @patch("hexagon_artifacts.subprocess.check_output")
    def test_packages_all_architectures_with_one_stub_and_complete_hashes(self, run):
        run.return_value = "Build ID: abcd\n  1: 00000010 4 FUNC GLOBAL DEFAULT 10 mnn_engine_query_hexagon_arch\n"
        source = self.all_sources()
        (source / "v73-ndk-r29").mkdir()
        plugin = self.plugin()
        artifacts.package(plugin, source, "readelf")
        native = json.loads((plugin / "native/android-arm64-v8a.json").read_text())
        assets = plugin / "android/src/main/assets/mnn/hexagon"
        info = json.loads((assets / "manifest.json").read_text())
        files = artifacts.validate_packaged_manifest(native, info)
        self.assertEqual(info["schemaVersion"], 2)
        self.assertEqual(native["runtime"]["hexagon"]["dspArchitectures"], list(artifacts.ARCHITECTURES))
        self.assertEqual(native["runtime"]["compiledBackends"], ["cpu", "opencl", "vulkan", "hexagon"])
        self.assertEqual(len(files), 12)
        self.assertEqual({path.name for path in assets.iterdir()}, {"manifest.json", *artifacts.ARCHITECTURES})
        for name, item in files.items():
            self.assertEqual(artifacts.digest(assets / name), item["sha256"])
        self.assertEqual([p.name for p in (plugin / "android/src/main/jniLibs/arm64-v8a").iterdir()], [artifacts.STUB])

    @patch("hexagon_artifacts.subprocess.check_output")
    def test_single_architecture_uses_the_same_selection_manifest(self, run):
        run.return_value = "Build ID: abcd\n  1: 00000010 4 FUNC GLOBAL DEFAULT 10 mnn_engine_query_hexagon_arch\n"
        plugin = self.plugin()
        artifacts.package(plugin, self.root, "readelf")
        assets = plugin / "android/src/main/assets/mnn/hexagon"
        info = json.loads((assets / "manifest.json").read_text())
        self.assertEqual(set(info["architectures"]), {"v73"})
        self.assertTrue((assets / "v73/libMNN_htpops_skel.so").is_file())
        self.assertFalse((assets / "libMNN_htpops_skel.so").exists())

    def test_all_architectures_are_required_before_replacing_an_existing_bundle(self):
        source = self.all_sources()
        (source / "v81/manifest.json").unlink()
        plugin = self.plugin()
        original = (plugin / "native/android-arm64-v8a.json").read_bytes()
        old_assets = plugin / "android/src/main/assets/mnn/hexagon"
        old_assets.mkdir(parents=True)
        (old_assets / "previous").write_bytes(b"keep on error")
        with self.assertRaises(FileNotFoundError):
            artifacts.package(plugin, source, "unused-readelf")
        self.assertEqual(original, (plugin / "native/android-arm64-v8a.json").read_bytes())
        self.assertEqual((old_assets / "previous").read_bytes(), b"keep on error")

    def test_refuses_mixed_mnn_revisions_across_architectures(self):
        source = self.all_sources()
        artifacts.describe(source / "v81", "other-commit", "v81")
        with self.assertRaisesRegex(ValueError, "commit"):
            artifacts.package(self.plugin(), source, "unused-readelf")

    def test_refuses_different_host_stubs(self):
        source = self.all_sources()
        with (source / "v79" / artifacts.STUB).open("ab") as stream:
            stream.write(b"a different host build")
        artifacts.describe(source / "v79", "test-commit", "v79")
        with self.assertRaisesRegex(ValueError, "identical Android stub"):
            artifacts.package(self.plugin(), source, "unused-readelf")

    def test_requires_the_architecture_claimed_by_each_input_directory(self):
        source = self.all_sources()
        manifest = source / "v79/manifest.json"
        value = json.loads(manifest.read_text())
        value["dspArchitecture"] = "v81"
        artifacts.write_json(manifest, value)
        with self.assertRaisesRegex(ValueError, "Expected v79"):
            artifacts.package(self.plugin(), source, "unused-readelf")

    @patch("hexagon_artifacts.subprocess.check_output")
    def test_requires_the_actual_query_export_not_only_a_manifest_claim(self, run):
        run.return_value = "Build ID: abcd\n  1: 0 0 FUNC GLOBAL DEFAULT UND mnn_engine_query_hexagon_arch\n"
        with self.assertRaisesRegex(ValueError, "architecture-query export"):
            artifacts.package(self.plugin(), self.root, "readelf")

    def test_rejects_legacy_stub_abi_and_incorrect_asset_sizes(self):
        manifest = self.root / "manifest.json"
        value = json.loads(manifest.read_text())
        value.pop("stubAbiVersion")
        artifacts.write_json(manifest, value)
        with self.assertRaisesRegex(ValueError, "stub ABI"):
            artifacts.validate(self.root, "test-commit")
        value["stubAbiVersion"] = artifacts.STUB_ABI_VERSION
        value["files"][artifacts.DSP_FILES[0]]["sizeBytes"] += 1
        artifacts.write_json(manifest, value)
        with self.assertRaisesRegex(ValueError, "size mismatch"):
            artifacts.validate(self.root, "test-commit")

    def test_common_packaging_removes_a_stale_optional_runtime(self):
        plugin = self.root / "plugin"
        native = plugin / "native"
        native.mkdir(parents=True)
        artifacts.write_json(native / "android-arm64-v8a.json", {
            "source": {"mnnCommit": "test-commit"}, "libraries": {artifacts.STUB: {}},
            "build": {"cmakeFlags": ["MNN_HEXAGON=OFF"]},
        })
        stub = plugin / "android/src/main/jniLibs/arm64-v8a" / artifacts.STUB
        stub.parent.mkdir(parents=True)
        stub.write_bytes(b"old stub")
        assets = plugin / "android/src/main/assets/mnn/hexagon"
        assets.mkdir(parents=True)
        (assets / "manifest.json").write_text("{}")
        artifacts.package(plugin, None, "unused-readelf")
        result = json.loads((native / "android-arm64-v8a.json").read_text())
        self.assertFalse(result["runtime"]["hexagon"]["runtimePackaged"])
        self.assertEqual(result["runtime"]["compiledBackends"], ["cpu", "opencl", "vulkan"])
        self.assertNotIn(artifacts.STUB, result["libraries"])
        self.assertFalse(stub.exists())
        self.assertFalse(assets.exists())

    def test_rejects_dsp_packaging_when_host_backend_was_not_built(self):
        plugin = self.plugin(hexagon=False)
        manifest_path = plugin / "native/android-arm64-v8a.json"
        before = manifest_path.read_bytes()
        with self.assertRaisesRegex(ValueError, "MNN_HEXAGON=ON"):
            artifacts.package(plugin, self.root, "unused-readelf")
        self.assertEqual(before, manifest_path.read_bytes())

    def test_host_only_manifest_reports_compiled_hexagon_without_dsp(self):
        plugin = self.plugin()
        artifacts.package(plugin, None, "unused-readelf")
        result = json.loads((plugin / "native/android-arm64-v8a.json").read_text())
        self.assertIn("hexagon", result["runtime"]["compiledBackends"])
        self.assertFalse(result["runtime"]["hexagon"]["runtimePackaged"])


if __name__ == "__main__":
    unittest.main()
