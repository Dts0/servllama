#!/usr/bin/env bash
set -euo pipefail

archive_path="${1:?CMake archive path is required}"
expected_sha256="${2:?Expected SHA-256 is required}"
version="3.22.1"
install_root="${HOME}/.local/share/mnn_engine/toolchains/cmake-${version}"
cmake_bin="${install_root}/bin/cmake"

if [[ -x "${cmake_bin}" ]] && [[ "$("${cmake_bin}" --version | head -n 1)" == "cmake version ${version}" ]]; then
    printf 'MNN_CMAKE=%s\n' "${cmake_bin}"
    exit 0
fi

if [[ -e "${install_root}" ]]; then
    printf 'Existing isolated CMake directory is invalid: %s\n' "${install_root}" >&2
    exit 2
fi

actual_sha256="$(sha256sum "${archive_path}" | awk '{print $1}')"
if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
    printf 'CMake archive SHA-256 mismatch.\n' >&2
    exit 3
fi

install_parent="$(dirname "${install_root}")"
mkdir -p "${install_parent}"
staging_dir="$(mktemp -d "${install_parent}/.cmake-${version}.XXXXXX")"
cleanup() {
    if [[ -n "${staging_dir:-}" ]] && [[ -d "${staging_dir}" ]]; then
        rm -rf -- "${staging_dir}"
    fi
}
trap cleanup EXIT

tar -xzf "${archive_path}" --strip-components=1 -C "${staging_dir}"
if [[ "$("${staging_dir}/bin/cmake" --version | head -n 1)" != "cmake version ${version}" ]]; then
    printf 'Extracted CMake version is not %s.\n' "${version}" >&2
    exit 4
fi

mv "${staging_dir}" "${install_root}"
staging_dir=""
printf 'MNN_CMAKE=%s\n' "${cmake_bin}"
