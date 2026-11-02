import hashlib
import io
import pathlib
import tarfile
import tempfile
import unittest

from extract_hexagon_sdk import extract_sdk


class ExtractHexagonSdkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.archive = self.root / "sdk.tar.gz"
        self.destination = self.root / "extracted"

    def make_archive(self, prefixes=("sdk/",), extra=None):
        with tarfile.open(self.archive, "w:gz") as bundle:
            for prefix in prefixes:
                for name in ("setup_sdk_env.source", "build/cmake/hexagon_fun.cmake", "tools/compiler"):
                    item = tarfile.TarInfo(prefix + name)
                    item.size = 4
                    item.mode = 0o755
                    bundle.addfile(item, io.BytesIO(b"test"))
            if extra:
                bundle.addfile(extra)
        return hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def test_accepts_sdk_at_archive_root_or_in_one_directory(self):
        for index, prefix in enumerate(("", "hexagon-sdk/")):
            with self.subTest(prefix=prefix):
                checksum = self.make_archive((prefix,))
                destination = self.root / str(index)
                sdk = extract_sdk(self.archive, destination, checksum)
                self.assertEqual(sdk, (destination / prefix).resolve())
                self.assertTrue((sdk / "tools/compiler").stat().st_mode & 0o100)

    def test_preserves_relative_sdk_symlinks(self):
        link = tarfile.TarInfo("sdk/tools/compiler-link")
        link.type = tarfile.SYMTYPE
        link.linkname = "compiler"
        checksum = self.make_archive(extra=link)
        sdk = extract_sdk(self.archive, self.destination, checksum)
        self.assertEqual((sdk / "tools/compiler-link").read_bytes(), b"test")
        self.assertTrue((sdk / "tools/compiler-link").is_symlink())

    def test_rejects_mismatched_checksum_before_extraction(self):
        self.make_archive()
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            extract_sdk(self.archive, self.destination, "0" * 64)
        self.assertFalse(self.destination.exists())

    def test_rejects_paths_and_symlinks_outside_the_sdk_directory(self):
        for index, link_type in enumerate((tarfile.REGTYPE, tarfile.SYMTYPE)):
            with self.subTest(link_type=link_type):
                item = tarfile.TarInfo("../outside" if link_type == tarfile.REGTYPE else "sdk/escape")
                item.type = link_type
                item.linkname = "../../outside" if link_type == tarfile.SYMTYPE else ""
                checksum = self.make_archive(extra=item)
                with self.assertRaises(tarfile.FilterError):
                    extract_sdk(self.archive, self.root / str(index), checksum)
                self.assertFalse((self.root / "outside").exists())

    def test_rejects_ambiguous_sdk_roots(self):
        checksum = self.make_archive(("sdk-5/", "sdk-6/"))
        with self.assertRaisesRegex(ValueError, "one installed SDK"):
            extract_sdk(self.archive, self.destination, checksum)


if __name__ == "__main__":
    unittest.main()
