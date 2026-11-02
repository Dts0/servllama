> **ServLlama native 3.6.2 override:** follow [SERVLLAMA_MNN_362.md](SERVLLAMA_MNN_362.md) and `scripts/build_servllama_mnn.sh`. The upstream 0.2.0 instructions below are retained for reference and describe the original 3.6.1/r27d setup.

# Android Native 编译指南

本文档面向需要重新编译 `mnn_engine` Native 库的贡献者。普通用户从
pub.dev 安装插件后会直接使用已经打包好的库，不需要安装这套工具链。

## 编译要求

- 支持子模块的 Git
- Flutter 和 Android SDK
- Linux，或 Windows + WSL2（推荐 Ubuntu 22.04）
- Android NDK `27.3.13750724`（r27d）
- CMake `3.22.1`、Ninja、Python 3 和 C/C++ 编译工具
- ARM64 Android 目标（`arm64-v8a`）

插件基于 MNN 3.6.1，固定 commit 为
`d407447ed56c4121a11ccbd266dc184ca1ead0c2`。

默认构建 CPU、`MNN_OPENCL`、`MNN_VULKAN`，设置 `MNN_VULKAN_IMAGE=OFF`，
使用适合 LLM 的 Vulkan buffer 算子；`MNN_HEXAGON=OFF`。
Hexagon 保留开发实现，但 ServLlama 不开放入口。需显式设置 `MNN_HEXAGON=ON`
编译主机后端，并打包下面的 SDK 产物，才能进行 DSP 推理测试。

当前 native adapter ABI 为 **8**，通用构建同时要求
`MNN_USE_LOGCAT=ON` 和 `MNN_ENGINE_LOG_BRIDGE=ON`。插件自有的
`scripts/cmake/mnn_log_bridge.cmake` 通过 `CMAKE_PROJECT_MNN_INCLUDE` 和
`--wrap=__android_log_print` 向 libMNN 加入直接日志桥，不改上游子模块。
升级时一起重编并打包 MNN 与 JNI。桥源文件和 CMake 扩展纳入构建指纹，
验证脚本检查日志 sink 导出。加载或生成失败时从有界缓冲区读取 MNN 错误上下文；
已移除全局 Android logger hook、运行时自检和 logcat 子进程回退，保留正常系统输出。
INFO 记录模型/服务生命周期和生成完成；请求时序、MNN 内部 INFO 细节使用 DEBUG，
底层 warning/error 保留对应级别。成功请求不输出整段 native 状态。

通用构建还要求 `MNN_ENGINE_TOKENIZER_ADDED_TOKENS=ON`。
`scripts/cmake/mnn_tokenizer_compat.cmake` 在构建目录的源码副本中修复
MNN 3.6.1 单 token MTOK 解码，保留 `</think>`、`<tool_call>` 等 added tokens。
子模块仍然干净，但正式 libMNN 包含该兼容补丁；补丁脚本纳入构建指纹，
上游函数不再匹配时会明确停止配置。MNN/JNI 一起重编并打包后运行
`bash scripts/test_mnn_tokenizer.sh "$PWD"`；WSL 可用 `MNN_BUILD_JOBS=4` 控制内存。

通用构建还要求 `MNN_ENGINE_PREFILL_CANCELLATION=ON`。
`scripts/cmake/mnn_prefill_compat.cmake` 在构建目录的源码副本中为 MNN 原有分段
循环增加取消检查，保留完整 prompt 计数，并使多模态位置编码、deep-stack 张量
与 embedding 的分段一致。图像/音频先完整编码，编码器的单次调用不能中断。
线程局部的 C 回调只读取 adapter 的原子取消标记，MNN 状态只由生成线程修改。
请求准备和取消在 runtime 的同一把锁下协调，native 入口不会清掉刚收到的停止信号。
正常取消保留模型，下一请求重置未完成的 KV 状态；兼容源码和回调导出均纳入构建
指纹与产物验证。回归命令及可选的真实模型 CPU 检查见
[native 测试说明](../test/native/README.md)。
默认分段大小由 adapter 在读取 MNN 合并后的模型元数据后决定：浮点因果 mask
使用 128 token，旧式 GLM/整数 mask 保留原有整段 prefill；显式 `chunk` 和
`chunk_limits` 配置保持有效。

8 Elite 上已跑通官方 CLI 和应用的 W4/C4 Qwen3-0.6B 短对话，非 C4 Attention
会在模型加载前明确拒绝。长输入答案质量尚未通过，其他官方后端/配置也可复现
错误回答，不能直接认定 Hexagon 算子有错。详见[真机排查报告](HEXAGON_8_ELITE_INVESTIGATION_ZH.md)。
这些是历史调查证据；维护中的回归测试见 [test/native/README.md](../test/native/README.md)。

## 推荐：使用 GitHub Actions

工作流文件为 [build-native-android.yml](../.github/workflows/build-native-android.yml)。
它使用 GitHub 托管的 Ubuntu 22.04 runner，自动安装固定版本的 NDK/CMake，完成
MNN、JNI、可选 Hexagon DSP 编译、strip、16 KB 对齐/JNI/清单校验及产物上传。
本机无需运行 native 编译。

### 运行方式

1. 将插件代码、构建脚本和 MNN 子模块指针一起提交并推送到自己的仓库或 Fork。
2. 进入 **Actions → Build Android native libraries → Run workflow**。
3. 选择要构建的分支，例如 `feat/mnn-android-backends`，设置选项后运行。
4. 等待 **Build and package ARM64 libraries** 成功，在该次运行的 **Artifacts** 下载产物。

手动运行入口要求工作流存在于仓库默认分支；如果新 Fork 尚未启用 Actions，先在
Actions 页面启用。工作流沿用已有名称，避免出现两个用途相同的构建入口。

| 选项 | 默认值 | 作用 |
| --- | --- | --- |
| `include_hexagon` | `false` | 默认只构建 CPU/GPU；显式开启才构建实验性 Hexagon 主机后端与 DSP 运行库 |
| `hexagon_dsp_arch` | `all` | 默认在同一包集成 v73/v75/v79/v81，设备自动匹配；也可选择单架构精简包 |
| `hexagon_toolchain` | `docker` | 默认使用固定 Snapdragon 工具链镜像；`sdk_archive` 使用自行提供的 SDK 压缩包 |
| `verify_example_apk` | `false` | 在独立 job 中运行 Flutter/Kotlin 测试，并构建、校验 ARM64 example APK |

也可使用 GitHub CLI 发起构建：

```bash
gh workflow run build-native-android.yml --ref feat/mnn-android-backends
# 直接使用 Docker 构建含 Hexagon 的包，并验证 example APK：
gh workflow run build-native-android.yml --ref feat/mnn-android-backends \
  -f include_hexagon=true -f hexagon_dsp_arch=all -f verify_example_apk=true
```

推送 `native-v*` 标签构建 CPU/GPU 包，并验证 example APK。只有手动运行并设置
`include_hexagon=true` 才构建 Hexagon。每次 native 构建都会执行 adapter、日志桥与 tokenizer 回归测试。
工作流只上传 Actions artifacts，不自动创建 Release 或发布到 pub.dev。

### 下载内容与应用方式

通用包名为 `mnn-engine-android-arm64-common-<MNN提交前12位>`；Hexagon 包名为
`mnn-engine-android-arm64-hexagon-all-<MNN提交前12位>`（单架构时 `all` 替换为所选架构）。Actions
下载的 ZIP 已按插件目录组织，直接解压到匹配版本的插件根目录，无需再解开一层 tar。

```text
android/src/main/jniLibs/arm64-v8a/
  libMNN.so
  libmnn_engine_jni.so
  libMNN_htpops.so                     # 仅 Hexagon 包
android/src/main/assets/mnn/hexagon/   # 仅 Hexagon 包：schema 2 清单
  manifest.json
  v73/ v75/ v79/ v81/                 # 每个目录含 skeleton 和两份 DSP C++ 库
native/android-arm64-v8a.json          # 版本、flags、hash、实际打包能力
native/github-actions.json            # 插件提交、构建链接、SDK/编译器/镜像来源
.native/generated/arm64-v8a/           # strip 前的库、JNI 调试信息及校验输入
SHA256SUMS
LICENSE
THIRD_PARTY_NOTICES.md
third_party_licenses/
```

先切到该次运行对应的插件提交并初始化 MNN 子模块，再覆盖产物。`.native/generated`
用于复核产物和诊断，不会随插件 Gradle 构建放进 APK。WSL/Linux 中可校验：

```bash
sha256sum --check SHA256SUMS
bash scripts/verify_mnn_artifacts.sh "$PWD"
```

覆盖另一份 artifact 前，先移除旧的可选生成文件（包括从四架构改为单架构的情况）
`android/src/main/jniLibs/arm64-v8a/libMNN_htpops.so` 和
`android/src/main/assets/mnn/hexagon/`，再覆盖通用包，避免残留的 DSP 文件与新清单冲突。

构建摘要会列出 MNN 版本、JNI ABI、编译后端及 DSP 运行库是否真正打包。原生库上传
完成后，可选的 example job 才开始；example 检查失败时，已完成的 native artifact
仍可下载。日志以 `mnn-native-logs-*` / `mnn-example-logs-*` 单独上传，失败时也保留
已生成的编译日志。库保留 30 天，APK 和日志保留 14 天。

### Actions 中构建 Hexagon

推荐保留 `hexagon_toolchain=docker`。工作流使用 llama.cpp Snapdragon 文档推荐的
`ghcr.io/snapdragon-toolchain/arm64-android:v0.7`，实际引用固定 digest：

```text
ghcr.io/snapdragon-toolchain/arm64-android@sha256:91714433626f0d94a926538a1e46ec43756c5b8e3262b91b95df1e812940aed1
```

镜像包含 **Hexagon SDK 6.6.0.0、Hexagon Clang 19.0.07、CMake 3.31.6**。Actions
把已安装的 NDK r27d 只读挂载到容器，用于 ARM64 stub；DSP 使用镜像的 Hexagon
编译器。构建容器禁用网络，宿主挂载中仅输出目录可写，不上传 SDK 安装目录。镜像展开约 10 GB，首次
拉取需要相应网络与磁盘空间；镜像、编译器版本及链接依赖写入产物清单。

本地已经用该镜像实际编译并校验 v73/v75/v79/v81 DSP 库，集成全部四架构的 ServLlama Debug
与 example Release APK 均通过内容校验。通用和 Hexagon artifact 的 ZIP 传递及消费者
校验也已演练通过。默认镜像引用存放在
`scripts/hexagon/toolchain-image.txt`；自定义镜像可通过 `MNN_HEXAGON_DOCKER_IMAGE`
指定，实际镜像 ID 会被记录。无需设置下文的 SDK secret。

### 可选：自行提供 SDK 压缩包

只有选择 `hexagon_toolchain=sdk_archive` 时才需要此配置。先准备一个**安装完成、
可在 Linux 使用的 Hexagon SDK 目录**，包括配套编译工具链；当前适配使用 SDK
公开头文件和 CMake toolchain，本次实际验证的版本是 6.6.0.0。将目录压缩为
tar.gz / tar.xz，保留执行权限和 SDK 内部相对符号链接：

```bash
tar -C /path/to/sdk-parent -czf hexagon-sdk-linux.tar.gz hexagon-sdk
sha256sum hexagon-sdk-linux.tar.gz
```

压缩包的根目录或唯一的一级目录必须包含 `setup_sdk_env.source` 与
`build/cmake/hexagon_fun.cmake`。需要可迁移的完整安装目录，不能使用下载网页、登录
跳转地址或尚未执行的 SDK 安装器。压缩包可以放在你管理的下载存储中。

在仓库 **Settings → Secrets and variables → Actions** 配置：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Repository secret | `HEXAGON_SDK_ARCHIVE_URL` | GitHub runner 可直接下载的 HTTPS 地址，可使用有效期覆盖构建时间的签名 URL |
| Repository variable | `HEXAGON_SDK_ARCHIVE_SHA256` | 压缩包的 64 位 SHA256，必须与地址对应的文件一致 |

然后勾选 `include_hexagon` 并选择 `sdk_archive`。工作流先校验配置、下载 hash 和
压缩包目录边界，再直接调用 CMake 构建。SDK 安装目录和下载地址不进入 artifact；native 编译缓存只
保存 MNN/JNI 输入，不缓存 SDK。可分发运行库按 SDK 许可处理。

SDK 缺失、hash 不匹配、SDK 编译失败会使 Hexagon 构建明确失败，不生成声称包含 NPU
运行库的占位包。需要先获取通用库时，保持 `include_hexagon=false` 即可。

Actions 无法验证 Android 驱动、FastRPC 权限或实际 NPU 推理。含 DSP 的包仍须在
合适设备上验收；本地已完成 Docker 中的真实 SDK/DSP 编译，以及工作流语法、
解包/打包和链接校验测试。尚未在远端 runner 上执行此版本工作流；设备结果见
ServLlama 的实现报告和验收指南。

example 使用 `integration_test` 开发依赖。在 Flutter 3.35.2 下请保留工作流中的正常
`flutter build apk --release` 命令，让 Flutter 重新生成 Release 插件注册文件；
直接添加 `--no-pub` 可能沿用调试注册信息并报测试插件的 Android 类不存在。

## Windows + WSL2 本地编译

在插件根目录使用 PowerShell 7 依次执行：

```powershell
git submodule update --init --recursive
pwsh -File .\scripts\prepare_mnn_build_env.ps1
pwsh -File .\scripts\build_mnn_android.ps1
pwsh -File .\scripts\verify_mnn_artifacts.ps1
pwsh -File .\scripts\package_mnn_artifacts.ps1
```

如果 WSL 发行版名称不是 `Ubuntu-22.04`，给每个 PowerShell 脚本加上
`-Distro <名称>`。

编译中间产物位于 `.native/generated/`。打包完成后，正式库会写入：

```text
android/src/main/jniLibs/arm64-v8a/
├── libMNN.so
└── libmnn_engine_jni.so
```

同时会更新 `native/android-arm64-v8a.json`，其中记录版本、哈希和构建信息。

## Linux 本地编译

安装 Android SDK API 35、NDK `27.3.13750724` 和 CMake `3.22.1`，然后设置路径：

```bash
export ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/27.3.13750724"
export MNN_CMAKE="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
```

在插件根目录执行：

```bash
git submodule update --init --recursive
bash scripts/build_mnn_android.sh "$PWD"
bash scripts/verify_mnn_artifacts.sh "$PWD"
bash scripts/package_mnn_artifacts.sh "$PWD"
```

## 编译后校验

先运行 Dart 检查，再构建 ARM64 example APK：

```powershell
flutter analyze
flutter test

Push-Location example
flutter pub get
flutter build apk --release --target-platform android-arm64
Pop-Location

pwsh -File .\scripts\verify_mnn_artifacts.ps1 `
  -ApkPath <app-release.apk 的绝对路径>
```

至少应在一台 ARM64 真机上确认插件能够初始化并加载模型。

## 可选 Hexagon 运行库（Linux / WSL）

推荐在有 Docker 的 WSL/Linux 中进入插件目录，复用上面的固定镜像。默认一次构建
v73/v75/v79/v81，共用一份 ARM64 stub，在同一安装包中按设备架构选择运行库：

```bash
export ANDROID_NDK="$HOME/android-ndk-r27d"
MNN_HEXAGON=ON bash scripts/build_mnn_android.sh "$PWD"
bash scripts/build_hexagon_docker.sh "$PWD" all
MNN_HEXAGON_ARTIFACTS="$PWD/.native/hexagon" \
  bash scripts/package_mnn_artifacts.sh "$PWD"
```

首次使用会拉取缺失镜像；本机已有对应镜像时无需下载。脚本支持仓库路径含空格，
按仓库目录属主运行容器，默认并行数为 4（可用 `MNN_BUILD_JOBS` 调整）。未指定
`ANDROID_NDK` 时使用镜像内的 NDK r29，来源同样记录在清单中；独立镜像的 v73 构建
也已实际通过。Actions 总是使用 r27d。

本机 WSL 普通账户没有 Docker socket 权限时，可从 PowerShell 调用 WSL root 启动
Docker，无需更改用户组；容器内仍按仓库属主运行：

```powershell
wsl.exe -d Ubuntu-22.04 -u root -- env ANDROID_NDK=/home/arkanefans/android-ndk-r27d `
  bash '/mnt/d/flutter projects/MNN-runtime/mnn_engine/scripts/build_hexagon_docker.sh' `
  '/mnt/d/flutter projects/MNN-runtime/mnn_engine' all
```

也可直接使用已安装的 Linux [Qualcomm Hexagon SDK](https://www.qualcomm.com/developer/software/hexagon-npu-sdk)：

```bash
export HEXAGON_SDK_ROOT=/absolute/path/to/hexagon-sdk
# 可选指定 HEXAGON_TOOLS_ROOT；默认从 SDK 的 hexagon_sdk.json 读取。
bash scripts/build_hexagon_android.sh "$PWD" all
```

构建层复制所需公共源码，使用 SDK 的 CMake toolchain 与 QAIC；不执行
`setup_sdk_env.source` / `build_cmake`。Community SDK 不含旧 `utils/examples`
辅助文件，插件通过 SDK 的 `domain_default.h` 实现域查询，并以 `remote.h` 实现 cDSP
架构查询。构建前用真实 SDK 头文件和模拟 FastRPC 验证 BCD 架构值、失败状态和缺失
接口；镜像没有 `cc` 命令时使用 NDK Clang 的 Linux host 模式运行这些测试。适配位于
`scripts/hexagon/`，不修改 MNN 子模块或 SDK，不改变 DSP 算子。

构建后验证 FastRPC 入口、动态依赖、SDK C++ 符号解析；剩余的 QuRT/FastRPC、POSIX
和 unwinder 接口由设备固件提供，写入 `link-report.json` 供真机排查。打包前还会
验证 MNN 提交、hash、ELF 类型、实际 DSP 架构标记及 Android stub 的 16 KB LOAD 对齐：

- `jniLibs/arm64-v8a/libMNN_htpops.so`：Android stub。
- `assets/mnn/hexagon/manifest.json`：schema 2，记录每个架构的资源与构建来源。
- `assets/mnn/hexagon/<架构>/`：DSP skeleton、`libc++.so.1`、`libc++abi.so.1`。
- `native/android-arm64-v8a.json`：JNI ABI 8；编译后端来自实际构建参数，`runtime.hexagon.dspArchitectures` 列出所打包架构。

DSP ELF 必须放 assets，不能伪装成 ARM64 JNI 库。首次查询后端能力时，插件通过
FastRPC 查询真实架构，仅将匹配的一套校验并解包到 `noBackupFilesDir/mnn/hexagon/<清单hash>/<架构>/`，
再设置 `ADSP_LIBRARY_PATH`。识别失败或没有精确匹配时显示不可用，不猜测 ISA。
宿主必须保留 `useLegacyPackaging=true`，
使 Android stub 能从 `nativeLibraryDir` 加载。`libcdsprpc.so` 由 OEM 提供，不打包
设备驱动；SDK 运行库的分发遵循 SDK 许可。

定向测试需要精简包时，可把构建参数改成 `v79`，并设置
`MNN_HEXAGON_ARTIFACTS="$PWD/.native/hexagon/v79"` 再打包。该包只在匹配 v79 的设备上
开放 Hexagon。传父目录 `.native/hexagon` 时要求四套库齐全，缺少任何一套会报错；
其他实验目录（例如 `v73-ndk-r29`）不会被自动纳入。

不传 `MNN_HEXAGON_ARTIFACTS` 时移除此前的可选 DSP 产物。生成默认 CPU/GPU 包时，
先以 `MNN_HEXAGON=OFF`（默认值）重新构建，再不带 DSP 资源打包。主机后端未编译时
传入 DSP 资源会报错。PowerShell 构建脚本通过 `-IncludeHexagon` 显式开启主机后端。
缺 SDK 不阻塞默认构建；含 DSP 的包编译成功后，仍需验证设备加载、推理
正确性和性能。

## 什么时候需要重新编译

修改 MNN/JNI Native 源码、MNN commit、NDK/CMake 版本或 Native 编译参数后需要重新
编译。只修改 Dart 代码或文档时，可以继续使用现有 Native 库。

## 常见问题

- **找不到 WSL 发行版：** 安装 Ubuntu 22.04，或使用 `-Distro` 指定名称。
- **找不到 NDK/CMake：** 检查 `ANDROID_NDK` 和 `MNN_CMAKE` 是否指向固定版本。
- **ABI 不受支持：** 宿主应用只配置 `arm64-v8a`。
- **MNN 子模块为空：** 执行 `git submodule update --init --recursive`。
