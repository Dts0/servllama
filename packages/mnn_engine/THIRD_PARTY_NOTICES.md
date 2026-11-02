# Third-party notices

The Android native libraries distributed with `mnn_engine` are built from the
projects and toolchains listed below. The public build manifest in
[`native/android-arm64-v8a.json`](native/android-arm64-v8a.json) records the
exact MNN commit, toolchain versions, build flags, Build IDs, sizes, and
SHA-256 digests for the bundled artifacts.

## Alibaba MNN

- Project: [Alibaba MNN](https://github.com/alibaba/MNN)
- Version: 3.6.2
- Commit: `7e3c6fb5225d3ccf22807ee641332669df34650d`
- Copyright: Copyright 2018 Alibaba Group
- License: Apache License 2.0

The Apache License 2.0 text is included in this package as [`LICENSE`](LICENSE).
`mnn_engine` is an independent community plugin and is not an official Alibaba
Flutter package.

## Optional Hexagon runtime

The common package includes CPU, OpenCL and Vulkan, with MNN's Hexagon host
backend disabled. It does not include the Qualcomm SDK or SDK-built runtime
libraries. Optional Hexagon packages explicitly enable the host backend and add
one SDK-built ARM64 stub and v73/v75/v79/v81 DSP skeletons with their matching
DSP C++ runtimes; their provenance and
hashes are recorded in the native and asset manifests. Distributors of those
optional artifacts must follow the Qualcomm SDK runtime redistribution terms.
The device's OEM FastRPC driver is not redistributed by this package.

The default optional build uses the Snapdragon toolchain v0.7 container
(`ghcr.io/snapdragon-toolchain/arm64-android`), pinned to digest
`sha256:91714433626f0d94a926538a1e46ec43756c5b8e3262b91b95df1e812940aed1`.
It includes Hexagon SDK 6.6.0.0 and Hexagon Clang 19.0.07. Native bundles contain
the runtime artifacts listed above, without the SDK installation, compiler
binaries or container image.
The asset manifest records the actual SDK, compiler, Android NDK, image ID,
adapter hashes and expected firmware dependencies for each architecture.
Only the matching DSP runtime is extracted and loaded on a device.

## FlatBuffers

- Project: [Google FlatBuffers](https://github.com/google/flatbuffers)
- License: Apache License 2.0

MNN uses FlatBuffers data structures and generated code. The Apache License
2.0 text is included as [`LICENSE`](LICENSE).

## half

- Project component: MNN `3rd_party/half`
- Copyright: Copyright 2012-2017 Christian Rau
- License: MIT License

The full license text is included as
[`third_party_licenses/HALF.txt`](third_party_licenses/HALF.txt).

## LLVM libc++ / Android NDK

The native libraries are built with Android NDK r27 (27.0.12077973) and
`ANDROID_STL=c++_static`. LLVM libc++ and libc++abi are licensed under the
Apache License 2.0 with the LLVM exception. The exception is included as
[`third_party_licenses/LLVM-exception.txt`](third_party_licenses/LLVM-exception.txt).

Downstream application distributors remain responsible for reviewing the
licenses of their models and any additional native or Dart dependencies they
combine with this package.
