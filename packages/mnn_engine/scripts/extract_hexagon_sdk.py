#!/usr/bin/env python3
"""Verify and extract a prepared Linux Hexagon SDK archive for CI."""

import hashlib
import pathlib
import re
import sys
import tarfile


def extract_sdk(archive, destination, expected_sha256):
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256):
        raise ValueError("HEXAGON_SDK_ARCHIVE_SHA256 must contain a SHA-256 digest")
    digest = hashlib.sha256()
    with archive.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != expected_sha256.lower():
        raise ValueError("Hexagon SDK archive checksum mismatch")
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("SDK extraction directory must be empty")
    destination.mkdir(parents=True, exist_ok=True)
    # Keep SDK executable modes and internal symlinks, but reject links and
    # archive members that would escape the extraction directory.
    with tarfile.open(archive, "r:*") as bundle:
        bundle.extractall(destination, filter="data")
    candidates = [destination / "setup_sdk_env.source"]
    candidates.extend(destination.glob("*/setup_sdk_env.source"))
    roots = [path.parent.resolve() for path in candidates if path.is_file()]
    if len(roots) != 1:
        raise ValueError("Archive must contain one installed SDK at its root or one top-level directory")
    sdk_root = roots[0]
    if not (sdk_root / "build/cmake/hexagon_fun.cmake").is_file():
        raise ValueError("Archive is missing the Hexagon SDK CMake build tools")
    if "\n" in str(sdk_root) or "\r" in str(sdk_root):
        raise ValueError("SDK directory name must not contain newlines")
    return sdk_root


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: extract_hexagon_sdk.py ARCHIVE DESTINATION SHA256")
    try:
        print(extract_sdk(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]))
    except (OSError, ValueError, tarfile.TarError) as error:
        raise SystemExit(f"Hexagon SDK extraction failed: {error}") from error
