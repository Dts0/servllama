import pathlib
import unittest
from unittest.mock import patch

import verify_hexagon_link as link


class HexagonLinkTest(unittest.TestCase):
    def test_resolves_cpp_symbols_and_retains_required_firmware_apis(self):
        self.assertEqual(link.unresolved_symbols(
            {"_ZSt9terminatev", "qurt_thread_create", "HAP_power_set", "dspqueue_import", "__wrap_malloc"},
            {"_ZSt9terminatev"},
        ), ["HAP_power_set", "__wrap_malloc", "dspqueue_import", "qurt_thread_create"])

    def test_does_not_hide_unresolved_application_or_cpp_symbols(self):
        for symbol in ("htp_ops_missing", "_ZN3MNN7missingEv", "__wrap_unrelated"):
            with self.subTest(symbol=symbol), self.assertRaisesRegex(ValueError, "Unresolved DSP"):
                link.unresolved_symbols({symbol}, set())

    @patch("verify_hexagon_link.subprocess.check_output")
    def test_reads_dynamic_symbols_without_treating_weak_apis_as_required(self, run):
        run.return_value = """
  0x00000001 (NEEDED) Shared library: [libc.so]
  0x0000000e (SONAME) Library soname: [test.so]
Symbol table '.dynsym' contains 4 entries:
  Num: Value Size Type Bind Vis Ndx Name
    1: 00000000 0 FUNC GLOBAL DEFAULT UND missing
    2: 00000000 0 FUNC WEAK DEFAULT UND optional
    3: 00000010 4 FUNC GLOBAL DEFAULT 10 exported
"""
        self.assertEqual(link.inspect(pathlib.Path("test.so"), "readelf"),
                         ({"libc.so"}, {"exported"}, {"missing"}, {"optional"}))

    @patch("verify_hexagon_link.subprocess.check_output")
    def test_rejects_build_machine_runtime_paths(self, run):
        run.return_value = """
  0x0000000e (SONAME) Library soname: [test.so]
  0x0000001d (RUNPATH) Library runpath: [/opt/sdk/lib]
"""
        with self.assertRaisesRegex(ValueError, "Non-portable"):
            link.inspect(pathlib.Path("test.so"), "readelf")

    @patch("verify_hexagon_link.inspect")
    def test_rejects_missing_android_driver_and_unbundled_dsp_libraries(self, inspect):
        inspect.return_value = ({"libc.so"}, set(), set(), set())
        with self.assertRaisesRegex(ValueError, "stub dependencies"):
            link.verify(pathlib.Path("artifacts"), pathlib.Path("sdk"), "readelf")
        inspect.side_effect = [
            ({"libcdsprpc.so"}, {"open_dsp_session", "close_dsp_session", "init_htp_backend", "mnn_engine_query_hexagon_arch"}, set(), set()),
            ({"libunpackaged.so"}, set(), set(), set()),
            (set(), set(), set(), set()),
            (set(), set(), set(), set()),
        ]
        with self.assertRaisesRegex(ValueError, "Unpackaged DSP"):
            link.verify(pathlib.Path("artifacts"), pathlib.Path("sdk"), "readelf")


if __name__ == "__main__":
    unittest.main()
