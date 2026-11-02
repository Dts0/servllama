# ServLlama / mnn_engine：MNN 3.6.1 与 Android 推理后端设计

初稿：2026-09-07；更新：2026-09-13。当前向用户开放 CPU/OpenCL/Vulkan，Hexagon 保留开发实现但默认不构建、界面不显示。历史真机证据见[8 Elite 排查报告](HEXAGON_8_ELITE_INVESTIGATION_ZH.md)。

## 1. 目标与版本基线

ServLlama 是应用，mnn_engine 负责模型加载、MNN 推理和 OpenAI 兼容 HTTP 服务。升级不改变聊天、图片、工具调用、SSE 或服务生命周期协议。

- 调研时 GitHub `releases/latest` 返回稳定版 **3.6.1**（2026-07-23 发布）。
- 升级前插件为 MNN 3.6.0，子模块提交 `cc20f672af9e177e2fa338c332dc097de2fc9264`。
- 目标固定为 3.6.1 的 `d407447ed56c4121a11ccbd266dc184ca1ead0c2`，不跟随 master。
- 两仓库分别创建 `feat/mnn-android-backends`，基于当前 main；保留应用已有的本地 Gradle 配置。
- Android arm64-v8a、API 28、WSL Ubuntu 22.04、NDK 27.3.13750724、CMake 3.22.1、16 KB ELF LOAD 对齐沿用现有方案。

## 2. 调研结论

| 后端 | 3.6.1 源码中的 LLM 支持 | 额外条件 | 本次处理 |
| --- | --- | --- | --- |
| CPU | 现有正式路径；ARM82、低内存、Transformer fuse | ARM64 Android | 保留为默认值与用户可主动切换的兼容选项 |
| OpenCL | Llm 接受 `opencl`；buffer Attention、RoPE、低比特 GEMM 与 C4；发布说明含真实 LLM 基准 | OEM 提供并允许应用访问 OpenCL 驱动；创建运行时成功 | 编译并接入，设备探测成功后可选 |
| Vulkan | Llm 接受 `vulkan`；buffer 后端有 Attention、KV cache、RoPE、LinearAttention 和 C4 处理 | Vulkan 驱动满足 MNN 要求；必须构建 buffer 版本 | `MNN_VULKAN=ON`、`MNN_VULKAN_IMAGE=OFF`，接入并进行设备探测 |
| Hexagon NPU | Llm 接受 `hexagon`，ForwardType=10；独立直接编程后端，有 LLM Attention 和 W4A16 HMX 路径 | 支持 FP16 HMX 的 Hexagon v73+；FastRPC/cDSP 可访问；匹配 SDK 编译的 stub、skeleton 及 DSP C++ 运行库 | 保留实验实现；需要显式开发构建，ServLlama 暂时隐藏 |

这三个新增后端均不存在已证实的理论性阻断，因此不删除其中任何一个。Hexagon **不是** `backend_type=npu`（后者映射到 NN/QNN），不引入 QNN 图导出、context binary 或 QAIRT 作为替代实现。

### 性能与模型限制

官方基准的 Hexagon 使用 Qwen3-0.6B、W4 对称量化、block64、C4、Snapdragon 8 Elite/v79：pp512=2667 tok/s，tg128=68.5 tok/s；同组 CPU 的 tg128=94.3 tok/s。不能据此宣称 NPU 全程更快，亦不能承诺任意 MNN 模型在任意 GPU/NPU 上兼容。

官方 HTP 源码说明要求 Snapdragon 8 Gen 2 同等级及以上、FP16 HMX；部分中低端 SoC 即使 DSP 代际较新也不满足条件。按芯片品牌字符串开放选择不可靠，以运行库与 DSP 会话探测为依据。SDK 5.x/6.x 可构建的表述来自发布说明；后续实际验证采用 Docker 中的 Community SDK 6.6.0.0，并通过 CMake 直接构建。

保留 MNN 的正常算子调度行为：选中后端不代表每个算子均在该设备执行。界面表述为“推理后端”，报告不得把配置名称当作全部算子 offload 的测量结果。主语言模型使用所选后端，多模态编码器沿用模型原有的独立 `mllm` 配置；若没有独立配置，上游 Omni 会共享主后端。不能将新增后端支持误解为所有图像/音频子图均已验证。

实现前进一步核对发现，3.6.1 的 `mllm_config_` 在模型构造时读取，之后 `set_config()` 不更新这份副本。因此不通过临时 JSON 假装把编码器强制切到 CPU，也不为本次接入修改上游工厂或算子；保留上游行为，并把加速后端的图片模型正确性列为必验项。

## 3. 能力探测与错误闭环

插件提供强类型 `MnnBackend`（cpu/opencl/vulkan/hexagon）、`MnnLoadOptions` 及 `getBackendCapabilities()`。每个能力项包含编译支持、当前环境是否可尝试加载、原因码和诊断文本。

1. 编译能力来自 JNI 构建定义，与 native 构建清单对应，不能仅凭设备厂商或 Dart 枚举推断。
2. OpenCL/Vulkan 通过 MNN 创建运行时，并检查返回的 runtime map **确实包含所请求的类型**。MNN 回退得到 CPU 不算探测成功。
3. Hexagon 在首次请求后端能力时，通过 OEM FastRPC 的 `remote_handle_control(DSPRPC_GET_DSP_INFO)` 查询 cDSP `ARCH_VER`；只解包与设备精确匹配的一套 DSP 资源，再打开/初始化 unsigned cDSP 会话。查询失败、未打包对应架构、资源损坏或会话初始化失败均返回明确原因。
4. 探测在插件 I/O 执行器进行，活动模型存在时使用已缓存结果，避免探测关闭正在推理的 DSP 会话。
5. 模型真正加载前再次校验所选后端。不可用返回 `backend_unavailable`，不静默切换 CPU；用户可以在设置中主动切换 CPU。
6. 模型加载失败继续走现有卸载、停止、错误显示流程，保持单会话和单并发约束。

Hexagon 在 `Llm::load()` 前还检查实际 graph 与 subgraphs，仅拒绝标准单输出
`Attention.output_c4=false`，返回 `model_backend_incompatible`；UI 提示 CPU/GPU 或重新导出。
路径合并与 LlmConfig 保持一致，图使用 mmap 和 FlatBuffers verifier 验证。
不把非对称 W4 一律判为不兼容；已移除只用于排查的量化统计。

可用性只表示运行时初始化通过，不替代特定模型的加载、生成、正确性与性能验收。日志、活动模型和快照记录本次加载选择的后端，供用户和验收定位。

## 4. 加载配置与生命周期

- `loadModel(modelId, options: MnnLoadOptions(...))` 默认 CPU，保持既有调用行为。
- Kotlin 校验后端参数，只接受明确枚举值；错误输入不能落入 MNN 的 AUTO。
- RuntimeManager 不再硬编码主模型 CPU。只在内存中合成运行配置，不修改导入/下载目录中的 `config.json`。
- 保持 `use_mmap=false` 的插件策略；线程数、采样、模板等沿用模型/现有默认配置。
- GPU shader/cache 目录按模型、MNN 版本和后端区分，避免不同驱动路径共用 `mnn_cachefile.bin`。
- 同模型的缓存复用条件加入后端，防止 CPU 已驻留时修改选项却继续复用 CPU。
- 模型配置更改不热切换正在运行的会话。下一次启动或按既有 stop → unload → load → start 流程切换模型时生效。
- 无模型、加载失败、停止和卸载均不保留假的“当前后端”。

## 5. Android 构建与分发

默认包把 CPU、OpenCL、Vulkan buffer 编入同一个 `libMNN.so`，`MNN_HEXAGON=OFF`、`MNN_SEP_BUILD=OFF`、`MNN_QNN=OFF`，同时构建 `libmnn_engine_jni.so`。Hexagon 主机后端需要显式 `MNN_HEXAGON=ON`；清单按真实参数生成，禁止将 DSP 资源打包到未编译主机后端的库上。不使用官方通用 Android zip 直接替代：插件需要与 LLM/Omni 选项和 JNI ABI 一致的自编译产物。

### Hexagon 可选产物

- 新增 WSL/Docker 构建脚本，默认固定 Snapdragon v0.7 镜像；也支持 `HEXAGON_SDK_ROOT` 指向已有 SDK。默认 `all` 构建 v73/v75/v79/v81，也可选择其中一种生成精简包；源码来自固定的 MNN 子模块。
- 生成 Android AArch64 `libMNN_htpops.so` 与 Hexagon `libMNN_htpops_skel.so`，包含 skeleton 需要的 DSP `libc++.so.1` / `libc++abi.so.1`。
- stub 放进 `jniLibs/arm64-v8a`；**DSP ELF 放 assets**，不能把 Hexagon ELF 当作 ARM64 库交给 AGP strip/打包。
- 四种架构共用一份 ARM64 stub；DSP skeleton 和对应 C++ 库按 `assets/mnn/hexagon/<v73|v75|v79|v81>/` 存放。打包时拒绝混合 MNN 提交、不同 stub 或错误 ISA，不把其他实验产物目录自动纳入包中。
- 资源清单使用 schema 2，以 `architectures` 映射保存每套资源的 hash、大小和构建来源。运行时仅解包所选架构到 `noBackupFilesDir/mnn/hexagon/<manifestSha256>/<architecture>/`，校验并原子写入文件，再设置 `ADSP_LIBRARY_PATH` 为该目录和 OEM 标准搜索路径。其他三套库不解包、不加载。
- OEM FastRPC 库由设备提供，manifest 使用可选 `uses-native-library` 声明 OpenCL/cDSP 依赖，不让无 GPU/NPU 的手机无法安装。
- 缺少 SDK 不阻塞 CPU/GPU 与 Hexagon host 构建；不生成占位 `.so`，不将未打包的 DSP 支持标为可用。SDK 构建/网络阻塞和未验证项进入报告。
- 保留 ServLlama 的 `useLegacyPackaging=true`。发布产物、JNI ABI 版本、hash、Build ID、构建 flags 同步更新。

### Docker 与 Community SDK 适配

复用 llama.cpp Snapdragon 文档中的 `ghcr.io/snapdragon-toolchain/arm64-android:v0.7`，
镜像 digest 固定在 `scripts/hexagon/toolchain-image.txt`。WSL 中已存在该镜像，包含
SDK 6.6.0.0、Hexagon Clang 19.0.07 和 CMake 3.31.6。Android stub 优先只读挂载
现有 NDK r27d；独立 Docker 使用者也可使用镜像自带 NDK，记录实际版本。

SDK 6.6 缺少旧的 `utils/examples/dsp_capabilities_utils.c`，而 MNN 只使用其中的
`get_domain()`。插件的 CMake 包装层替换这一辅助源文件，以 SDK 的
`domain_default.h` 提供域查询，并保留可选 FastRPC 接口的 weak 声明。SDK 初始化
脚本会在已有 `HEXAGON_SDK_ROOT` 时提前退出，因此不再依赖该脚本和 `build_cmake`。
公共源码复制到临时构建目录，MNN 子模块、SDK 和 DSP kernels 均保持原样。

容器按仓库目录属主运行，仓库只读挂载，只有产物目录允许写回，编译阶段禁用网络。
SDK 提供的只读运行库安装为可覆盖的副本，避免重复构建失败。清单记录 SDK、编译器、
NDK、镜像 digest/ID、构建适配文件 hash、动态依赖与固件导入符号。校验会拒绝错 DSP
架构、缺少 C++ 库或自定义未解析符号；设备固件提供的 QuRT/FastRPC/POSIX/unwinder
接口保留到真机验证。

Actions 默认 `include_hexagon=false`，手动默认和 `native-v*` 标签均只构建 CPU/GPU。
手动显式开启后编译 Hexagon 主机后端，并使用 Docker / `hexagon_dsp_arch=all` 生成 DSP 资源；也可选择单架构。
`hexagon_toolchain=sdk_archive` 保留已有自备 SDK 入口。

### 多架构检测与兼容边界

SDK 包装层在共用 stub 中导出 `mnn_engine_query_hexagon_arch`，使用真正的 SDK 头文件进行无会话架构查询。JNI 通过 `dlsym` 调用，不复制 Qualcomm ABI 定义，也不要求 CPU/GPU 构建安装 SDK。当前 native adapter ABI 为 7，保留 libMNN 直接日志桥并简化日志接口。FastRPC 返回值低字节按 BCD 解码（例如 `0x79` 对应 v79），并拒绝无效编码。

不根据手机型号猜测架构，不在查询失败时默认 v73，也不把未知架构强行映射到更低或更高 ISA。已支持的四种架构精确选择；后续硬件需在 SDK/MNN 验证后明确加入支持列表。FP16 HMX 和 OEM 权限仍由实际会话初始化验证，识别为 v73+ 本身不代表模型可运行。

架构查询不加载 DSP skeleton。资源准备失败后，开发者再次查询能力可以重试；成功后复用校验过的本进程路径。整个 DSP 会话探测仍受 RuntimeManager 的模型生命周期锁保护，模型驻留时只返回缓存能力。默认包不包含 DSP 资源，不触发该初始化路径。

## 6. ServLlama 集成与交互

服务设置在选择 MNN 引擎时显示“推理后端”区域，llama.cpp 参数区不混入 MNN 专属字段。

- 单选 CPU / OpenCL GPU / Vulkan GPU。开放列表集中在 `ServerLaunchSettings.supportedMnnBackends`，界面和 provider 共用；插件报告 Hexagon 可用也不会开放选择。
- 默认 CPU，设置使用独立持久化键，与 llama.cpp 的参数互不覆盖。
- 加载旧设置时将 Hexagon 或未知值迁移并持久化为 CPU，保证页面隐藏后不会自动启动 NPU；保存时同样限制开放列表。已开放但暂时不可用的 GPU 选择仍保留并提示。
- 后端行显示用途/限制与可用性；不可用项不可选，并解释是缺运行库、驱动不可用或构建未包含。
- 能力读取有加载、失败、重试状态；读取失败时仍可恢复 CPU 默认选择。
- 页面说明“下次启动生效”；当前活动模型的后端与下次选择有区别，运行日志也记录加载后端。
- 中英文 ARB 同步；布局适应窄屏、长错误提示及大字体。
- 应用 adapter 每次启动传递已保存的 MNN 选项，不在页面中拼 native JSON。

### 日志与维护边界

INFO 记录实际模型加载/卸载、服务启停与生成完成；DEBUG 记录请求开始/耗时、复用、媒体准备和底层内部细节；WARNING 表示可恢复的输入/能力限制，ERROR 表示真实失败。方法通道和 HTTP 各自只记录一次失败摘要。

直接 MNN 日志桥在加载/生成开始时清空有界上下文，失败时读取；最多 128 条 / 64 KiB，保留开头 8 条和最新尾部。移除全局 Android logger hook、logcat 子进程回退、通路自检、设备/配置整段输出、成功阶段逐步记录，以及默认 `enable_debug` 注入。错误保留阶段、后端、状态和必要 token 计数。

prefill/decode 错误检查、错误会话禁止复用、模型格式预检和 MTOK added-token 修复继续保留。一次性设备调查脚本归档，正式回归独立位于 `test/native/`，Actions 构建时运行；模型资源和历史证据不随插件分发。

## 7. 验证计划

1. Dart：默认向后兼容、通道参数传递/枚举解析、配置持久化、adapter 传参与复用、不可用/加载/错误 UI。
2. Kotlin：参数校验、运行配置覆盖且原配置不变、cache 隔离、后端复用条件、资产与能力信息解析。
3. Native：WSL 构建；AArch64 ELF、16 KB 对齐、JNI exports、选项和提交一致；Hexagon 可选产物有单独验证。
4. Flutter analyze 与相关测试，必要时完整 Flutter suite；Android 编译/单元测试。
5. 真机：CPU、OpenCL、Vulkan 分别测试导入/下载模型、短长 prompt、中文流式、取消、重启、切换、图片、工具调用、后台服务与并发 429。NPU 属于后续开发验收，不是当前用户流程。
6. 记录 cold load、prefill、decode、内存和温度，使用同一模型/量化/提示词，不引用官方数字作为本地结果。

Docker SDK 已完成 v73/v75/v79/v81 编译与产物检查。8 Elite 真机的官方 CLI 已复现原 Qwen3-0.6B 非 C4 Attention resize 失败；独立 FastRPC probe 在约 3.9 GiB 累计映射后失败。用户 Qwen3.5-4B 原包也有非 C4 Attention，非对称 W4 展开 FP16 的静态估算约 7.83 GiB，且原日志已有 map 错误。本轮未重新导出或运行完整 4B 权重。

历史调查中，同一提交重新导出的对称 W4/C4 Qwen3-0.6B 已通过官方与应用 NPU 短对话；应用也通过原包切回 CPU 回归。CMake 在构建副本中修复 MNN MTOK added-token 解码，避免吞掉思考和工具标记。正式 libMNN 包含该补丁，子模块与 DSP kernels 保持不变。

历史长输入答案质量未通过：Hexagon、OpenCL、CPU 高精度/高内存均答错，Vulkan 也有多余错误数字。第一层独立权重方程对照没有支持 Hexagon 算子错误，CPU 低内存不是数值 golden。当前暂不开放 NPU，继续按模型验收 CPU/GPU，不以 benchmark 或 HTTP 成功替代正确性。详见[实机报告](HEXAGON_8_ELITE_INVESTIGATION_ZH.md)。

## 8. 一手资料

- [官方 3.6.1 release](https://github.com/alibaba/MNN/releases/tag/3.6.1)
- [LLM 后端映射及初始化](https://github.com/alibaba/MNN/blob/3.6.1/transformers/llm/engine/src/llm.cpp)
- [Vulkan buffer 构建开关](https://github.com/alibaba/MNN/blob/3.6.1/source/backend/vulkan/CMakeLists.txt)
- [Vulkan buffer Attention](https://github.com/alibaba/MNN/blob/3.6.1/source/backend/vulkan/buffer/execution/VulkanAttention.cpp)
- [Hexagon 编译和部署](https://github.com/alibaba/MNN/blob/3.6.1/source/backend/hexagon/README.md)
- [HTP 硬件、SDK 与 HMX 说明](https://github.com/alibaba/MNN/blob/3.6.1/source/backend/hexagon/htp-ops-lib/README.md)
- [Hexagon runtime 与动态依赖](https://github.com/alibaba/MNN/blob/3.6.1/source/backend/hexagon/backend/HexagonRuntime.cpp)
- [DSP 会话初始化](https://github.com/alibaba/MNN/blob/3.6.1/source/backend/hexagon/htp-ops-lib/src/host/session.c)
- [Qualcomm Hexagon NPU SDK](https://www.qualcomm.com/developer/software/hexagon-npu-sdk)
- [llama.cpp Snapdragon Docker 工具链说明](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md)

本次未修改 DSP kernels，tokenizer 兼容补丁仅存在于构建目录副本。后续若需要改动 kernels，须单独验证正确性和真机性能。
