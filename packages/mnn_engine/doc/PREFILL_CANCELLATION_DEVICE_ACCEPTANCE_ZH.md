# 停服准入与 prefill 取消：SM8475 真机验收

日期：2026-09-13。

本次两个修复——停服后拒绝新生成请求、prefill 分批取消并复用模型——
在 CPU、OpenCL、Vulkan 的真实应用服务上均通过验收。
额外的 OpenCL 低精度图片重复性检查未通过，不能据此宣称所有视觉场景通过。
本轮修改限于原生回归测试程序和验收说明，没有再修改生产 Kotlin、JNI 或 MNN 实现。

## 设备与构建

| 项目 | 实测值 |
| --- | --- |
| 设备 | Xiaomi / Redmi，22081212C（diting） |
| SoC | SM8475，骁龙 8+ Gen 1 |
| GPU | Adreno 730 |
| 系统 | Android 12，API 31，MIUI V13.0.5.0.SLFCNXM |
| ABI / 页大小 | arm64-v8a / 4096 字节 |
| 应用 | ServLlama 1.1.2+11，本地重新编译的 Debug APK |
| native | Release，MNN 3.6.1，`d407447ed56c4121a11ccbd266dc184ca1ead0c2` |
| adapter ABI | 8 |
| 构建指纹 | `c3821cd66a849e5ac60a9ed2` |
| 可用后端 | CPU、OpenCL、Vulkan；Hexagon 未构建 |

测试包通过覆盖安装保留了原应用数据。已检查 APK 内两份 `.so` 的 SHA-256
与 `native/android-arm64-v8a.json` 一致：

- `libMNN.so`：`68521c54ec460bc460834738f0a347fc5dba48783d517497c676c408357a5aaa`
- `libmnn_engine_jni.so`：`c3378b393d6985e7e5e6513a45a5988d05d46b190c61e14f4f9e6e2476e508b4`

## 原生真实模型回归

使用完整 Qwen3-0.6B-MNN，在 WSL 中用 NDK r27d 将
`test/native/mnn_prefill_integration_test.cpp` 交叉编译为 Android CLI，
链接插件实际打包的 `libMNN.so`，通过 ADB 在真机运行。

固定 greedy 采样，短提示 14 token，长提示 446 token，每次正常生成 4 token。
CPU 使用 2 线程；GPU 关闭调优，以检查正确性。各后端分别比较自身的分段与全量结果，
不要求不同后端之间逐 token 相同。

| 检查 | CPU | OpenCL | Vulkan |
| --- | --- | --- | --- |
| 短提示：`chunk=0` 与 `chunk=128` 输出一致 | 通过 | 通过 | 通过 |
| 长提示：分段与全量 greedy 输出一致 | 通过 | 通过 | 通过 |
| 分段后仍报告完整的 446 个 prompt token | 通过 | 通过 | 通过 |
| 第一个批次前取消，处理 token 为 0 | 通过 | 通过 | 通过 |
| 第一个批次后取消，恰好处理 128 token | 通过 | 通过 | 通过 |
| 最后一个批次后取消，未进入解码 | 通过 | 通过 | 通过 |
| 取消状态为 `USER_CANCEL`，生成 token 为 0 | 通过 | 通过 | 通过 |
| 每种取消后再次生成，输出与干净请求一致 | 通过 | 通过 | 通过 |

最后一轮记录的首个 128-token 批次耗时约为 CPU 708 ms、OpenCL 629 ms、
Vulkan 608 ms。这是取消边界测试的观测值，不是性能基准或取消响应时间保证。
GPU 首次编译/调优、单个批次计算，以及视觉编码器内部调用仍不能被强制打断。

## 应用链路

使用手机原有完整 Qwen3.5-0.8B-MNN 的独立副本，包含视觉模型、mRoPE 和
deep-stack 元数据。在加载前设置 `sampler_type=greedy` 并关闭思考输出，
保留默认 `precision=low`、`thread_num=4`，运行时由插件选择后端。
MNN 3.6.1 在模型加载时构造采样器，仅在 HTTP 请求中传 `temperature=0`
不足以作为逐 token 一致性断言的前提。原模型配置值已和测试前记录核对，一致。

最终使用临时 Android instrumentation 测试包，绑定已安装应用实际的
`MnnEngineService`，走实际 JNI、HTTP/SSE 和模型，不替换 runtime 为 mock。
测试端口为 `127.0.0.1:18081`。这覆盖服务调用链，不等同于逐个点击 Flutter 页面。

短提示为 “What is 2 + 2? Answer briefly.”，23 token；长提示为 2327 token。
每种后端执行以下检查，全部通过：

1. 正常生成，再以相同配置保存输出基准。
2. 长提示开始 prefill 后，第二个请求返回 HTTP 429 / `request_queue_full`。
3. 取消长提示，SSE 正常结束，没有内容或推理 token；日志为
   `completion=0, finish=cancelled`。
4. 模型保持 `loaded`、生成回到 `idle`，无 `lastError`；再次生成与基准一致。
5. 再次开始长提示，同时建立一个只上传部分正文的请求，然后停止服务。
   在停服后补齐正文，该连接被关闭，没有进入推理。
6. 停服完成后新请求被拒绝；重新启动服务，原模型仍可生成，输出与基准一致。
7. 日志恰好有 5 次生成开始、2 次零输出取消、0 条 error，证明忙碌请求和
   停服中的慢上传请求没有进入生成流程。

| 观测项 | CPU | OpenCL | Vulkan |
| --- | ---: | ---: | ---: |
| 首次长提示：发出取消至 SSE 结束 | 606 ms | 21507 ms | 967 ms |
| 后续长提示：停止服务至 stopped / idle | 535 ms | 575 ms | 202 ms |
| 慢上传连接在停服后关闭 | 28 ms | 28 ms | 28 ms |
| 验收 runner | passed | passed | passed |

这些是单轮观测，不是性能保证。OpenCL 首次遇到新 prefill 长度时，会执行
内核编译/调优；本轮即使已提出取消，仍等待约 21.5 秒才到达第一个可取消边界。
后续相同长度已完成初始化，停服取消约 0.6 秒。分批检查不会中断正在执行的
GPU 调优、单个批次或视觉编码器调用，也不保证取消总能在固定毫秒数内结束。
Vulkan 的第二次取消可能在首批计算前生效，因此不能据 202 ms 推导单批性能。

关于 HTTP 503：如果请求仍处于可响应的停服窗口，准入门返回
`503 / server_stopping`，已有 `MnnOpenAiServerStopTest` 覆盖这一条件。
这次真机上 CIO 更早关闭了未上传完的连接，因此实际观测是连接关闭，并没有
观测到这条慢上传请求的 503 响应。两者都拒绝生成，报告没有把断连算作收到 503。

三次测试均输出 `passed: true` 和 `INSTRUMENTATION_CODE: -1`。
结果解析同时检查报告内容，不仅检查 ADB 命令退出码。

## 图片输入与精度对照

使用 Qwen3.5-0.8B-MNN 和 ServLlama 自带的 `assets/mnn_test/apple.jpg`：
一枚红黄条纹苹果。短提示 194 token，长提示 626 token，最多生成 32 token。
视觉编码器沿用 CPU 配置；下表后端指 LLM 后端。

| 配置 | 同配置重复生成 | 0 / 128 / 最后批次取消及恢复 | 分段与全量逐 token 对照 |
| --- | --- | --- | --- |
| CPU，high | 通过 | 通过 | 通过 |
| CPU，low，固定 chunk=128 | 通过 | 通过 | 不相同，见下文 |
| Vulkan，low，固定 chunk=128 | 通过 | 通过 | 未作为本项通过条件 |
| OpenCL，high，固定 chunk=128 | 通过 | 通过 | 文本不同，但均识别为红苹果 |
| OpenCL，low | **未通过** | 未取得可重复基准，不能判为通过 | 不能只凭文本差异判断根因 |

默认的跨 chunk 断言会发现低精度差异；新增 `--chunked-only` 用相同配置做
重复、取消、恢复对照，仍严格比较 token，不忽略重复性失败。

CPU 的 low 精度全量输出为 “The image shows a red apple with a green stem.”，
分段输出为 “Apple”。进一步比较首次 prefill 的全部 248320 个 logits：

- low：全部有限，RMSE 0.30446，余弦相似度 0.99755；原本首选的 The / Apple
  分数仅相差 0.015625，改变计算形状后首 token 发生变化。
- high：全量和分段的全部 logits 完全一致，最大绝对差为 0。

这支持 mRoPE/deep-stack 分段路径在 CPU 高精度对照中的正确性，不能用
低精度输出文字不同直接断言位置索引或取消清理有错，也不能将单张图片的结果
推广为所有模型、图片和精度的正确性保证。WSL CPU 文本和图片回归也通过。

### OpenCL 低精度的未解决现象

在 **没有进行任何取消** 时，同一会话、同一图片、greedy 连续生成仍会变化：

- `chunk=128`、关闭 tuning：首轮出现 “Peach”，下一轮变为 “Apple”。
- `chunk=0`、关闭 tuning：连续四轮在 “The fruit is an apple, and it is red.”
  与 “The fruit is an apple, and its color is red.” 之间交替，重复性同样未通过。
- `chunk=128`、应用默认 tuning 值 4：四轮输出并不完全相同，均出现将图片识别
  为 Peach 的文本。首轮 prefill 包含调优约 42.7 秒，后续约 0.84 秒。
- 改用 high、固定 `chunk=128` 后，同配置重复、三种取消和取消后复用均通过。

因此，**重复性漂移在不分段且从未取消时也存在**；但苹果误识别为桃子的样例
是在低精度分段配置下观察到的，不能声称不分段也复现了同样的误识别。
现有证据偏向 OpenCL 低精度执行或运行时状态相关问题，尚不足以定位到具体
MNN 算子、驱动或量化计算，也没有使用完全未打兼容补丁的上游库做 A/B。
本轮不将它定性为已经证实的上游缺陷，也不擅自改变所有用户模型的精度或后端。

本模型在这台设备上的视觉使用可优先选择已通过检查的 CPU / Vulkan；
OpenCL high 在本次图片 fixture 中通过。OpenCL low 的视觉重复性和精度应另行排查。

## 测试程序的修正

这次真机执行同时修正了原来仅面向 CPU 的测试程序：

1. 增加可选后端参数及批次计数输出。
2. GPU 的 `thread_num` 是调优位掩码。原 CPU 测试值 `2` 会启用 GPU 重度调优，
   在这台设备上导致长提示长时间初始化；正确性测试改用 `MNN_GPU_TUNING_NONE`。
3. OpenCL、Vulkan 缓存分目录。直接复用同一工作目录下的 MNN 默认缓存时，
   Vulkan 读取 OpenCL 缓存会在 `RuntimeManager::setCache` 调用链崩溃。
   测试程序现使用 `.mnn-prefill-test-<backend>`；应用已有按 MNN 版本、后端
   分开的 `tmp_path`，继续使用该隔离方式。

辅助设备控制脚本及原始结果保存在本地 `.native/device-tests/20260913-sm8475/`。
最初通过 Flutter 调试连接调用 MethodChannel；最终完整服务验收由临时
instrumentation runner 完成，避免调试连接因锁屏断开而影响结果。

| 原始记录 | 内容 |
| --- | --- |
| `instrumentation-{cpu,opencl,vulkan}.log/.json` | 完整服务验收结果、HTTP 响应和应用日志 |
| `service-test-summary.json` | 三后端服务验收摘要 |
| `native-{cpu,opencl,vulkan}.log` | Qwen3-0.6B 文本分段对照与取消复用 |
| `native-vision-cpu-{high,low}.log`、`native-vision-vulkan-low.log` | 图片通过记录 |
| `native-vision-opencl-high-chunked.log` | OpenCL high 同配置图片、取消及复用通过 |
| `native-vision-opencl-low.log`、`opencl-image-repeat-*.log` | OpenCL low 图片未通过记录及无取消对照 |
| `logit-comparison.json`、`logits-*.log/.bin` | CPU 跨 chunk 的数值对照 |
| `host-regression.log`、`host-vision.log` | WSL CPU 实际模型回归 |

这些辅助脚本、instrumentation 和数值探针只保留在本地忽略目录，不进入插件发布包。
维护中的回归入口仍是 Kotlin 测试与 `test/native/mnn_prefill_integration_test.cpp`。

## 复现原生回归

在 WSL 中编译测试程序，使用工作区内实际打包的 native 库：

```bash
plugin_root='/mnt/d/flutter projects/MNN-runtime/mnn_engine'
ndk_root="$HOME/android-ndk-r27d"
mkdir -p "$plugin_root/.native/tests"
"$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang++" \
  -std=c++17 -O2 -static-libstdc++ -Wall -Wextra \
  -I"$plugin_root/android/src/main/cpp" \
  -isystem "$plugin_root/MNN/include" \
  -isystem "$plugin_root/MNN/transformers/llm/engine/include" \
  "$plugin_root/test/native/mnn_prefill_integration_test.cpp" \
  -L"$plugin_root/android/src/main/jniLibs/arm64-v8a" -lMNN \
  -o "$plugin_root/.native/tests/mnn_prefill_integration_test"
```

从插件根目录用 Windows ADB 部署到独立测试目录，模型路径换成现有完整模型目录：

```powershell
$mnnAdb = 'D:\Environments\Android\Sdk\platform-tools\adb.exe'
$mnnSerial = 'd9138a54'
& $mnnAdb -s $mnnSerial shell mkdir -p /data/local/tmp/mnn-prefill-test
& $mnnAdb -s $mnnSerial push .native/tests/mnn_prefill_integration_test /data/local/tmp/mnn-prefill-test/
& $mnnAdb -s $mnnSerial push android/src/main/jniLibs/arm64-v8a/libMNN.so /data/local/tmp/mnn-prefill-test/
& $mnnAdb -s $mnnSerial push .native/diagnostics/models/Qwen3-0.6B-MNN /data/local/tmp/mnn-prefill-test/
& $mnnAdb -s $mnnSerial shell chmod 755 /data/local/tmp/mnn-prefill-test/mnn_prefill_integration_test
foreach ($mnnBackend in @('cpu', 'opencl', 'vulkan')) {
    $mnnCommand = 'cd /data/local/tmp/mnn-prefill-test && LD_LIBRARY_PATH=. ./mnn_prefill_integration_test Qwen3-0.6B-MNN/config.json ' + $mnnBackend
    & $mnnAdb -s $mnnSerial shell $mnnCommand
    if ($LASTEXITCODE -ne 0) { throw "Regression failed: $mnnBackend" }
}
```

每种后端应退出为 0，输出 `Real MNN prefill regression passed`。
该程序只验证原生 LLM 批次控制；停服时的 HTTP 准入及应用生命周期要通过真实服务另测。

图片输入可在同一设备目录执行：

```bash
LD_LIBRARY_PATH=. ./mnn_prefill_integration_test \
  Qwen3.5-0.8B-MNN/config.json vulkan /data/local/tmp/mnn-prefill-test/apple.jpg --chunked-only
```

模型和图片需先部署到对应目录；CPU high 对照使用修改精度的独立模型配置，
并去掉 `--chunked-only`。OpenCL low 的同配置重复性检查可能退出为 1，应保留失败结果。

本机服务验收的临时 runner 可通过以下方式复现，测试源码及 Gradle init script
保存在本轮 `.native/device-tests/20260913-sm8475/instrumentation/` 及其父目录。
需先准备名为 `PrefillAcceptance-Qwen3.5-0.8B-MNN-20260913` 的独立 greedy 模型副本：

```text
adb install -r -t app-debug-androidTest.apk
adb shell am instrument -w -r -e backend cpu \
  com.arkanefans.servllama.test/com.arkanefans.servllama.test.MnnDeviceAcceptanceRunner
```

将 backend 依次替换为 opencl、vulkan，按前述报告字段判断结果。

## 测试结束后的状态

- 已移除手机上的临时模型副本及对应 runtime 缓存、独立 CLI 测试目录和
  `com.arkanefans.servllama.test` 测试包；原模型和配置保留。
- 测试服务已停止，临时端口转发已无残留；本地原始日志、测试源码和数值结果保留。
- 保留覆盖安装的 ServLlama Debug APK，应用数据保留。其 native 库为前述 Release
  构建；本轮没有再构建应用 Release APK，也没有执行提交、推送或发布。
