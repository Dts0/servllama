# Snapdragon 8 Elite / MNN 3.6.1 实机排查报告

日期：2026-09-12。设备：HONOR AAK-AN00，SM8750，Android 16 / API 36，4 KiB 页，Hexagon v79。

> 历史调查记录：下文描述当时的 diagnostics 4 / ABI 6 测试版本，不是当前发布配置。
> 2026-09-13 起，ServLlama 暂时隐藏 NPU，默认构建关闭 Hexagon 主机后端和 DSP 打包；
> 当前 ABI 7 保留格式检查、tokenizer 修复与直接错误日志桥，移除临时诊断。
> 当前构建和验收以[构建指南](BUILDING_NATIVE_ZH.md)及[后端设计](ANDROID_BACKENDS_DESIGN_ZH.md)为准。

## 1. 结论与交付范围

原始“加载成功、首次 prefill 失败”首先是**模型格式与 Hexagon 支持条件不匹配**。原 Qwen3-0.6B 的相同失败已经在未经插件修改的官方 `llm_demo` 中复现；不是 HTTP、Flutter 或 v79 选择造成的。Qwen3.5-4B 原始日志还包含非对称 W4 展开 FP16 后的 FastRPC 映射失败。

使用现有 Docker SDK 重新导出的对称 W4/C4 Qwen3-0.6B，官方 CLI 和 ServLlama 均已完成真实 NPU 短对话。调试过程中另确认并修复了 **MNN MTOK tokenizer 丢失 added tokens** 的上游代码缺陷；否则思考结束标记被吞掉，应用会把答案全部归入 `reasoning_content`。

这不代表所有模型或长输入质量已经通过。新导出小模型在一条 830-token 长输入上仍答错，**OpenCL 和 CPU 高精度/高内存模式也能复现错误回答**。第一层投影的独立数学对照反而显示 Hexagon 更接近参考值，不能把 CPU 低内存模式的正确回答当成数值 golden，也不能据此宣布 Hexagon kernel 有 bug。目前保留实验性标记，没有加入未经验证的 DSP 修改或 chunk workaround。

| 项目 | 结果 |
| --- | --- |
| SDK 6.6 / v79 / OEM FastRPC / DSP 基础执行 | 真机通过 |
| 原 Qwen3-0.6B + 官方 CPU | 正常生成 |
| 原 Qwen3-0.6B + 官方 Hexagon | 首个 Attention resize 返回不支持 |
| 原 Qwen3-0.6B + 修复应用 Hexagon | 加载前明确拒绝；提示切 CPU/GPU 或重新导出 |
| W4/C4 Qwen3-0.6B + 官方 Hexagon | 短文本与 benchmark 执行通过 |
| W4/C4 Qwen3-0.6B + 修复应用 Hexagon | 短中英文、多轮、SSE、长度结束与后续请求、思考/答案分离通过 |
| 原 Qwen3-0.6B + 修复应用 CPU | HTTP 200，`2 + 3 = 5.`，正常 stop |
| 新导出小模型的长输入答案质量 | 未通过；多个官方后端/配置均可复现，不足以归因 Hexagon |
| Qwen3.5-4B 新导出版本 | 本轮未重新导出或运行，不能声称已修复 |
| v73/v75/v81 | 编译、打包校验通过；本轮没有这些架构的真机 |

## 2. 版本、工具链和证据

- 两仓库均为 `feat/mnn-android-backends`，本轮修改尚未提交、推送或发布。
- MNN 3.6.1：`d407447ed56c4121a11ccbd266dc184ca1ead0c2`，子模块保持干净。
- Windows + WSL Ubuntu 22.04；NDK r27d、CMake 3.22.1，Android arm64/API 28。
- DSP 使用现有 Snapdragon v0.7 镜像：SDK 6.6.0.0、Hexagon Clang 19.0.07；digest 见 `scripts/hexagon/toolchain-image.txt`。
- 官方 CLI 独立构建：`MNN_BUILD_FOR_ANDROID_COMMAND=ON`、`MNN_USE_LOGCAT=OFF`，没有插件日志桥、tokenizer 补丁或 JNI。
- 应用构建：diagnostics **4**，native adapter ABI **6**，直接日志桥、模型预检和 tokenizer 构建补丁。

本机证据目录为插件的 `.native/diagnostics/official-baseline/`，下文日志名均相对于其 `logs/`。这些运行产物不进入 Git；一次性复现工具已归档到本机 `.native/diagnostics/tools-archive-20260913/`，不再属于维护中的脚本。tokenizer 和 native 回归入口见 [test/native/README.md](../test/native/README.md)。

官方要求见 [Android Hexagon LLM 文档](../MNN/docs/transformers/llm.md)。旧部署计划和诊断版本说明保存在上述本机归档目录的 `docs/` 中。

## 3. 原始失败：模型布局与映射资源

### 3.1 Qwen3-0.6B：相同模型、相同官方执行器复现

手机原目录 `/sdcard/Download/大模型/Qwen3-0.6B-MNN` 的两个核心文件与 ModelScope `MNN/Qwen3-0.6B-MNN` 下载文件 SHA256 一致：

| 文件 | SHA256 |
| --- | --- |
| `llm.mnn` | `d426c65a5159c938ccc237cdfbd982137f276804f27b414ca0ecf3fc0a660f8c` |
| `llm.mnn.weight` | `953afb7e0165818add34a7a6caf0af5d0ed9428da102eb22c9b98ee9da292e9f` |

图中 28 个 Attention 全部 `output_c4=false`；196 个非对称 W4 卷积，lm_head 为非对称 W8。CPU 正常，官方 Hexagon 第一次执行报：

```text
Resize error for type = Attention, name = Attention/Reshape_8_output_0
code=2 in onForward, 666
[Error]: LLM in error state. Status: 4
```

原应用也在同一 Attention 上返回 prefill `INTERNAL_ERROR(4)` / HTTP 500。见 `qwen3-original-{cpu,hexagon}.txt` 和 `app-original-native.txt`。

MNN 标准 Hexagon Attention 要求 Transformer C4，特别是 V 的布局。执行器创建成功后在 resize 返回 `NOT_SUPPORT`，不会再进入“创建失败时尝试 CPU backup”的分支。修改 `backend_type` 或手改图 JSON 的 `output_c4` 无法完成必要的模型重导出。

### 3.2 Qwen3.5-4B：原始日志与静态证据

仓库：[taobao-mnn/Qwen3.5-4B-MNN](https://huggingface.co/taobao-mnn/Qwen3.5-4B-MNN)，核对 revision `aa966261175a532d9906fa165c9d506d617320a9`。公开配置和图保存在 `.native/diagnostics/Qwen3.5-4B-MNN-aa966261/`；本轮未下载/重跑完整 4B 权重，也没有核对其手机权重 hash。

- 8 个 Attention 全部非 C4，首个为 `/layers.3/self_attn/FusedAttention`。
- 249 个非对称 W4 卷积；不满足 Hexagon W4A16 路径条件，会在 Hexagon 后端展开 FP16 卷积，并非回退 CPU。
- 按卷积维度估算，全部展开的 FP16 权重约 **7.83 GiB**，不含临时转换、KV 和工作区。这是静态估计，不是测得的整机内存占用。
- 用户完整原始日志实际包含大量 `asymmetric int4 ... fallback to fp16` 和 `err=1 in map`。
- 24 个 `gated_delta_rule` LinearAttention 可以由 CPU backup 执行；缺少专用 NPU 算子不能证明整架构不可用。图中没有 RoPE，不能归因于 RoPE 布局。

因此该原包同时存在确定的 Attention 格式限制与已发生的映射资源失败。即使只解决其中一项，也不能承诺原包可用。

### 3.3 FastRPC 隔离验证

`fastrpc_probe` 不加载模型，通过同一 stub 和 OEM `libcdsprpc.so` 分配、映射缓冲区，再由 DSP 实际访问。结果：

| 场景 | 实测 |
| --- | --- |
| 初始化 | `vectorSize=64, vtcmSize=8388608, maxThreads=6` |
| 单独映射 4 KiB、4/16/20/40/64 MiB | 全通过 |
| 同时持有 64 MiB 块 | 成功持有 4,160,749,568 bytes 后，下一块 map 失败 |
| 同时持有 16 MiB 块 | 成功持有 4,211,081,216 bytes 后失败；此时 `MemAvailable=2019192 kB` |
| 失败值 | `ret=1, errno=0`；释放一块后重试未恢复 |

证据支持**单 DSP 会话映射资源/约 4 GiB 地址空间限制**，不支持把正数返回值 `1` 直接解释为 errno `EPERM`，也不支持直接认定整机 OOM 或“释放一块即可恢复”。见 `probe-individual.txt`、`probe-aggregate-64m.txt`、`probe-aggregate-16m.txt`。

## 4. 符合 Hexagon 条件的模型与执行基线

从已下载的原始 `Qwen/Qwen3-0.6B` 用同一 MNN 提交的 exporter 和 host converter 导出：

```bash
python llmexport.py \
  --path /home/arkanefans/.cache/mnn_engine/official-baseline/models/Qwen3-0.6B \
  --export mnn --quant_bit 4 --quant_block 64 --sym \
  --mnnconvert /home/arkanefans/.cache/mnn_engine/official-baseline/host/MNNConvert \
  --dst_path /home/arkanefans/.cache/mnn_engine/official-baseline/models/Qwen3-0.6B-W4-SYM-C4
```

保留默认 C4。实际导出 28 个 C4 Attention、197 个对称 W4 卷积，scale 为 FP16；graph 301,232 bytes，weight 317,087,986 bytes，tokenizer 为 MTOK。

- 本机模型：`.native/diagnostics/models/Qwen3-0.6B-W4-SYM-C4/`。
- 手机模型：`/sdcard/Download/ServLlama-Hexagon-Test/Qwen3-0.6B-W4-SYM-C4`，已通过应用真实目录导入。
- 标准 `config.json` 默认 CPU；应用选择 Hexagon 时在内存中覆盖。交付 ZIP 不包含 chunk/数值诊断实验配置。
- 官方 CPU/Hexagon 短答均为 `2 + 3 = 5.`，见 `qwen3-sym-c4-{cpu,hexagon}.txt`。

官方 `llm_bench` 执行了 **`-kv true -load false -rep 3`**：

| prompt / decode | prefill tok/s | decode tok/s |
| --- | ---: | ---: |
| 128 / 32 | 2517.02 ± 87.47 | 70.28 ± 0.89 |
| 512 / 128 | 2788.18 ± 34.74 | 59.48 ± 0.03 |

配置为 Hexagon、4 threads、low precision；保留 `enable_debug=true`。这是同次 response 带历史 KV 的测法，不是默认独立 pp/tg；本轮没有执行 `-kv false`，也没有采集足够温度/功耗数据进行性能承诺。bench 的重复 token 输入只能验证执行与吞吐，不能证明回答质量。

## 5. 已确认的上游缺陷：MTOK added tokens 丢失

位置：`transformers/llm/engine/src/tokenizer/tokenizer.cpp`，`PipelineTokenizer::decode(int)`。原函数只查基础词表，没有还原 `added_tokens_` 中的文字。

真实 Qwen tokenizer 的基础 vocab 为 151643：

| 标记 | token ID | 原版单 token decode | 修复后 |
| --- | ---: | --- | --- |
| `<think>` | 151667 | 空 | 原文 |
| `</think>` | 151668 | 空 | 原文 |
| `<tool_call>` | 151657 | 空 | 原文 |
| `</tool_call>` | 151658 | 空 | 原文 |

这四项的 `special` 实际为 false，是额外 token/控制标记，问题不在 vector decode 的特殊 token 过滤。encode 可以正确找到 ID，普通英文/中文 decode 也正常，因此仅测试普通文本不能发现问题。

旧应用的答案全落在 `reasoning_content`，`content` 为空。修复单 token decode 后，短答进入 `content`，思考请求能同时得到非空 reasoning 与正确最终答案。普通生成路径和所有使用该 MTOK tokenizer 的后端均受益。

实现为插件拥有的 [mnn_tokenizer_compat.cmake](../scripts/cmake/mnn_tokenizer_compat.cmake)：在构建目录生成 tokenizer.cpp 副本，先按 ID 返回 added token 原文，再执行原基础词表解码；不对原文套用 ByteLevel/Metaspace 解码。固定源码匹配失败时终止构建，避免升级后静默错补丁。`MNN_ENGINE_TOKENIZER_ADDED_TOKENS=ON` 是正式产物必需 flag，补丁脚本纳入构建指纹。

复现/验证：合成 fixture 8 项原版失败 6 项，补丁后 8 项通过；实际 tokenizer 6 项补丁后全部通过。日志 `tokenizer-qwen3-{upstream,patched}.txt`；入口 `scripts/test_mnn_tokenizer.sh`。这部分是可以单独反馈上游的确定代码缺陷，尚未向上游发送 issue 或 PR。

## 6. 长输入：质量未通过，但不能指认 Hexagon kernel

固定单行 `prompt-long.txt`：36 条无关 notebook 记录，最后询问 `2 plus 3`。greedy、无思考模板时 830 tokens；应用默认模板为 826 tokens。最终问题要求数字 5；下表按回答事实记录，不把 HTTP 200 或没有 NaN 当成质量通过。

| 官方运行配置 | 回答 |
| --- | --- |
| CPU，precision=low，memory=low | 正确表达 5 |
| CPU，precision=high，memory=low，dynamic_option=0，attention_mode=0 | `2 + 3 = 5` |
| CPU，precision=high，memory=high，同样关闭上述选项 | `9.0` |
| Hexagon，precision=low | `9.5`，重复两次一致 |
| OpenCL，precision=low | `9.5` |
| Vulkan buffer，precision=low | `9. The number is 5.`，包含多余错误数字 |
| 修复应用 Hexagon | HTTP 200，`content="\n\n9.5"`，正常 stop |

Hexagon chunk 64/128/256 均未解决，因此没有加入应用默认配置。没有发生 `INTERNAL_ERROR`；被检查的全部 151936 个 logits 均有限。

独立 `llm_logits_probe` 表明差异已在 prefill logits 出现，不依赖应用分词后处理。早期 CPU 低内存对照 top token 是 `The` 或 `2`，Hexagon 为 `9`；改为 CPU 高精度/高内存后，top token 同样为 `9`。

为避免误把 CPU 当 golden，进一步从实际模型文件读取第一层 q_proj 的 W4 权重和 FP16 scale，按每个后端各自输入执行 float64 反量化矩阵乘法：

| 第一层 q_proj 对独立数学参考 | relative L2 | max abs |
| --- | ---: | ---: |
| CPU high precision / low memory | 0.01260881 | 0.14179226 |
| CPU high precision / high memory | 0.000001086 | 0.00003480 |
| Hexagon | 0.00020389 | 0.00379051 |

该对照支持首层 Hexagon W4 路径基本符合权重方程，而 CPU 的低内存路径并不是全浮点参考。它只覆盖一个投影，不能证明全图没有其他问题。早期 RoPE dump 的 host shape 分别为 `[B,D,S,H]` 和 `[B,S,H,D]`，必须转置后比较；对齐后的 Q/K cosine 约 0.999881/0.999990，未经布局对齐的低 cosine 不能用来断言 RoPE bug。

现有证据更适合归为**该小模型/量化导出在长输入下的质量与后端数值差异**，尚不能精确区分模型能力、量化损失、全图导出或其他算子误差。本轮未完成原始 PyTorch / 全精度全图对照，不将它写成已定位的 MNN Hexagon 算法缺陷。继续研究时应先补该对照，再逐层定位；不应为获得预期答案盲目改 DSP。

证据：`demo-long-{opencl,vulkan,cpu-float}.txt`、`qwen3-sym-c4-long-*.txt`、`app-v4-long-response.json`、`logits-long-*.{txt,bin}`、`qproj-numpy-reference.json`、`layer0-comparison.json`。数值工具和使用边界见 diagnostics README。

## 7. 应用与插件修复

1. Hexagon 加载前通过 mmap + FlatBuffers verifier 检查实际 graph（包含 subgraphs），沿用 LlmConfig 路径合并语义；仅拒绝标准单输出非 C4 Attention，不误拒流式多输出 Attention。非对称 W4 统计用于提示，不一律禁止可能可运行的小模型 FP16 回退。
2. 返回稳定错误码 `model_backend_incompatible`，Kotlin、Dart typed error 与中英文 UI 一致。用户看到“此模型的格式不适用于 Hexagon NPU。请选择 CPU/GPU，或导入为 Hexagon 重新导出的模型。”不启动不兼容模型的 HTTP 服务，也不自动用 CPU 重试。
3. diagnostics 4 保留直接 MNN 日志桥与实际自检。缓冲区上限 128 条 / 64 KiB，超限保留开头 8 条与尾部，避免量化警告淹没初始化错误。预检已终止且桥自检成功时，`backend_records=0` 不误报采集失败。
4. tokenizer 构建副本修复 added token 解码。MNN 子模块和四套 DSP kernels/资源保持不变；正式 `libMNN.so` 包含这一兼容补丁，不能称为完全未经修改的官方二进制。
5. 仍按设备真实架构精确匹配 v73/v75/v79/v81，仅解包和加载匹配的一套。

## 8. 构建、验证与最终设备状态

- Native adapter/checker/logger/bridge：47 项通过。
- Tokenizer fixture：8 项通过；实际 tokenizer：6 项通过。
- ServLlama 本次定向 Flutter：13 项通过；两仓库 analyze 通过。
- Kotlin suite：75 项通过；日志调整后的定向 17 项通过。
- WSL MNN/JNI 编译、四架构打包、native/16 KB ELF/hash/Build ID 与 Release 签名验证通过。
- 最终应用真机：原包前置拒绝、兼容模型短中英文、多轮、SSE `[DONE]`、max_tokens=1 返回 length、随后新请求、思考/答案分离、真实聊天 UI 显示答案、原包切回 CPU 回复，均已验证。

新 APK 为 ServLlama **1.1.2+11**，`build/app/outputs/flutter-apk/app-release.apk`：

```text
size:   35990990 bytes
SHA256: 4756d65669243efe45d2f701d60bf48eb1b1b22f4739b37d7bdb0bd02f1143ea
signer: 54ef1c7ed8b773acfe70718cc0e80669cc253511df7a258118b355f60b9ba190
```

已同签名覆盖安装，未卸载/清数据。最终保留原模型 Qwen3-0.6B-MNN、选择 CPU、服务停止；保留新导入的 W4/C4 测试模型。USB 常亮设置恢复为 0，18080 ADB 转发不存在。

最终日志 `app-v4-native.txt` 直接从应用日志视图取得，覆盖新进程的预检失败、成功 NPU 加载/请求和 CPU 回归。系统 logcat 在此 OEM 环境仍可能只返回过滤后的空内容，应优先应用导出日志。`app-v4-chat-ui.xml` 记录真实聊天答案；同名早期 PNG 捕获的是系统设置页面，**不作为验收证据**。

本机还遇到 Flutter AOT 缓存未更新：首次 APK native 已更新但 libapp.so 仍缺少新错误码。使用新的 `--dart-define=SERVLLAMA_BUILD_DIAGNOSTICS=4` 构建配置后生成新 AOT，最终真机提示正确。实际命令见 ServLlama 验收指南；无需删除被保护的旧构建缓存。

## 9. 后续验收门槛

- Qwen3.5-4B 要从原始模型重新导出对称 W4/C4，先过官方 CLI，再过应用，单独检查 LinearAttention、视觉和映射资源；本轮没有完成此项。
- Hexagon 继续实验性开放。短对话与 benchmark 的通过不能覆盖长上下文质量、工具调用实际执行、图片、后台长时稳定性或其他 SoC。
- 如反馈上游，分别提交确定的 MTOK decode 最小复现，以及含精度/内存模式和数学参考的长输入调查记录；不要把两类问题混成一个 NPU bug。
- GitHub Actions 工作流可显式开启四架构开发构建，调查期间没有触发远端执行。当前默认策略与依赖见[构建指南](BUILDING_NATIVE_ZH.md)，一次性工具见上述本机归档目录。
