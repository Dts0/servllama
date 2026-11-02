> ServLlama 本地集成：Flutter 包 API 版本仍为 **0.2.0**，原生 MNN 更新为 **3.6.2**；并非发布了新的官方 Flutter 包。固定来源、构建步骤与未验证项见 [升级说明](doc/SERVLLAMA_MNN_362.md)。

# mnn_engine

[English](README.md) | [简体中文](README_ZH.md)

[![pub package](https://img.shields.io/pub/v/mnn_engine.svg)](https://pub.dev/packages/mnn_engine)
[![构建 Android Native](https://github.com/ArkaneFans/mnn_engine/actions/workflows/build-native-android.yml/badge.svg)](https://github.com/ArkaneFans/mnn_engine/actions/workflows/build-native-android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

在 Flutter Android 应用中运行 [Alibaba MNN](https://github.com/alibaba/MNN)
大语言模型，并把当前模型暴露为设备内 OpenAI 兼容 HTTP API。

pub.dev 包已包含经过校验的 `arm64-v8a` Native 库。接入后无需准备 MNN 源码、
CMake、Android NDK、Linux 或 WSL。

> [!IMPORTANT]
> 本项目是独立维护的社区插件，不是 Alibaba 官方 Flutter 插件。当前仍处于
> `0.x` 阶段，公共 API 后续可能继续演进。

## 功能特性

- 导入、列出、加载、卸载、重命名和删除完整 MNN 模型目录；目录名同时作为
  运行时和 API 模型 ID。
- 加载前校验 `config.json` 以及模型、权重、embedding、tokenizer 等引用文件。
- 使用 Android 前台 Service 管理模型与 API Server，并提供运行快照、状态事件
  和日志流。
- 提供 OpenAI 兼容的 `/v1/models` 和 `/v1/chat/completions`，可监听 loopback
  或全部 IPv4 接口，支持可选 Bearer 认证、SSE 流式响应、视觉输入、
  function tools、推理内容和取消生成。
- 加载模型时可配置推理后端、mmap、计算精度和生成线程数，并查询或清理
  mmap/GPU 缓存。

## 支持范围

| 项目 | 当前支持 |
| --- | --- |
| Flutter 平台 | 仅 Android |
| Android ABI | 仅 `arm64-v8a` |
| Android 最低版本 | API 28 |
| 插件 Compile SDK | 35 |
| Flutter | 3.35.0 或更高版本 |
| MNN | 3.6.2，commit `7e3c6fb5225d3ccf22807ee641332669df34650d` |
| 推理后端 | CPU（默认）、OpenCL、Vulkan buffer；可选 Hexagon |
| 活跃模型 | 同一时间一个 |
| 并发生成 | 同一时间一个 |

当前不支持 iOS、桌面平台、`armeabi-v7a`、`x86` 和 `x86_64`。

## 安装

在 Flutter 应用的 `pubspec.yaml` 中添加：

```yaml
dependencies:
  mnn_engine: ^0.1.1
```

然后执行：

```shell
flutter pub get
```

宿主 Android 应用需要使用 API 28，并将发布 ABI 限制为 ARM64。Kotlin DSL
（`android/app/build.gradle.kts`）配置如下：

```kotlin
android {
    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}
```

Groovy（`android/app/build.gradle`）配置如下：

```groovy
android {
    defaultConfig {
        minSdk 28

        ndk {
            abiFilters "arm64-v8a"
        }
    }
}
```

当前 0.2.0 开发分支尚未发布，[HTTP 取消与停服契约](doc/HTTP_CANCELLATION_ZH.md)说明请求取消和原生资源释放的边界。

开发版插件 Manifest 会合并 Internet、前台 Service、specialUse 前台 Service 和通知权限。
Android 13 及以上的通知运行时授权，应由宿主应用按自己的交互流程申请。

## 快速开始

```dart
import 'package:mnn_engine/mnn_engine.dart';

final engine = MnnEngine.instance;

final eventSubscription = engine.events.listen((event) {
  final snapshot = event.snapshot;
  print(
    'model=${snapshot.modelState}, '
    'server=${snapshot.serverState}, '
    'generation=${snapshot.generationState}',
  );
});

final logSubscription = engine.logs.listen((entry) {
  print('[${entry.level}] ${entry.tag}: ${entry.message}');
});

final engineInfo = await engine.initialize();
if (!engineInfo.nativeLibraryLoaded) {
  throw StateError('MNN Native Runtime 不可用。');
}

// 打开 Android 系统目录选择器，并把完整模型目录导入应用私有存储。
final importedModel = await engine.importModelDirectory();
await engine.loadModel(importedModel.modelId);

late final MnnServerInfo server;
try {
  server = await engine.startServer(
    port: 8081,
    apiKey: 'replace-with-a-runtime-secret',
  );
} on MnnEngineException catch (error) {
  if (error.code == 'port_in_use') {
    throw StateError('端口 8081 当前不可用。');
  }
  rethrow;
}
print('OpenAI 兼容地址：${server.baseUrl}/v1');

// 宿主决定完整停止服务时：
await engine.stopServer();
await engine.unloadModel();
await eventSubscription.cancel();
await logSubscription.cancel();
```

`importModelDirectory()` 会拉起 Android Activity，因此必须在插件已附着
Flutter Activity 时调用。如果宿主应用已经把模型下载到私有目录，可以直接使用：

```dart
final imported = await engine.importModelFromPath(modelDirectory.path);
```

## 加载配置

`loadModel()` 接受 `MnnLoadOptions`。未传入的字段使用下表默认值。

| 字段 | 默认值 | 可选值 |
| --- | --- | --- |
| `backend` | `cpu` | `cpu`、`opencl`、`vulkan`；`hexagon` 为实验性 |
| `useMmap` | `false` | `true`、`false` |
| `precision` | `low` | `low`、`high` |
| `threadNum` | `4` | `1` 到 `8` |

提供 GPU 后端前先调用 `getBackendCapabilities()`。`compiled` 表示当前构建
包含该后端，`available` 表示本机初始化成功。加载或切换模型前必须先停止
API Server。后端不可用时返回 `backend_unavailable`，不会回退到 CPU。
已加载模型的 `backend`、`useMmap`、`precision`、`threadNum` 会记录本次选择。

```dart
final capabilities = await engine.getBackendCapabilities();
final opencl = capabilities.firstWhere(
  (item) => item.backend == MnnBackend.opencl,
);
if (!opencl.available) {
  throw StateError('当前设备无法使用 OpenCL。');
}

await engine.loadModel(
  importedModel.modelId,
  options: const MnnLoadOptions(
    backend: MnnBackend.opencl,
    useMmap: true,
    precision: MnnPrecision.high,
    threadNum: 6,
  ),
);
```

`getMmapCache()` 和 `clearMmapCache()` 用于查看或删除已生成的 mmap 与 GPU
运行时缓存。清理前必须卸载模型。传入 `modelId` 只处理单个已导入模型，
省略则统计或清理全部模型。下次加载会重新生成缓存。

```dart
final cache = await engine.getMmapCache();
if (cache.sizeBytes > 0) {
  await engine.clearMmapCache();
}
```

`hexagon` 为实验性后端，需要匹配的 Native 构建，详见
[Native 编译指南](doc/BUILDING_NATIVE_ZH.md)。

## 模型目录

插件导入的是完整 MNN 模型目录，而不是单个 `.mnn` 文件。典型文本模型结构如下：

```text
Qwen3-0.6B-MNN/
├── config.json
├── llm.mnn
├── llm.mnn.weight
├── tokenizer.txt
├── llm_config.json
└── market_config.json          # 可选显示元数据
```

根目录中的 `config.json` 必须包含非空 `llm_model`。模型、权重、embedding 和
tokenizer 等所有引用路径必须是目录内相对路径，并且对应文件必须存在。模型导入位置为：

```text
<application filesDir>/mnn/models/<model-key>/
```

本插件不分发模型文件，模型许可证由对应模型发布方单独提供。

## OpenAI 兼容 API

默认 Server 地址为 `http://127.0.0.1:8081`。

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/` | 内置 API 测试页 |
| `GET` | `/health` | Engine、模型和 Server 状态 |
| `GET` | `/v1/models` | 当前模型的 OpenAI 兼容信息 |
| `POST` | `/v1/chat/completions` | 流式或非流式 Chat Completions |

请求示例：

```shell
curl http://127.0.0.1:8081/v1/chat/completions \
  -H "Authorization: Bearer replace-with-a-runtime-secret" \
  -H "Content-Type: application/json" \
  --data-binary '{
    "model": "qwen3-0-6b-mnn",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": true,
    "max_tokens": 128
  }'
```

`max_tokens` 可选。省略或设为 `-1` 时，模型会持续生成，直到输出结束标记或
运行时达到上下文容量上限。也支持 `max_completion_tokens` 和 `n_predict` 别名。

使用 `MnnServerBindMode.allInterfaces` 时 Server 会监听 `0.0.0.0`，能够通过
Wi-Fi、热点、VPN 等 IPv4 接口访问。除非设备处于可信网络，否则应始终配置 API Key。

## 运行约束

- 启动 Server 前必须先加载模型。
- 卸载、删除或替换活跃模型前，必须先停止 Server。
- 第二个并发生成请求会收到 HTTP 429，不排队。
- `stopServer()` 会拒绝新请求（HTTP 503，`server_stopping`），并取消当前生成。
- `cancelGeneration()` 只取消当前生成，不会停止 Server。
- 生成异常会卸载当前模型，并清空运行快照中的 `activeModel`。依次调用
  `stopServer()`、`loadModel()`、`startServer()` 即可恢复。正常完成、达到
  长度上限以及取消生成仍会保留模型，后续请求可继续使用。
- 完整停止顺序为先 `stopServer()`，再 `unloadModel()`。

## Native 产物

pub 包内包含：

```text
android/src/main/jniLibs/arm64-v8a/
├── libMNN.so
└── libmnn_engine_jni.so
```

源码版本、工具链、构建参数和 SHA-256 记录在
[`native/android-arm64-v8a.json`](native/android-arm64-v8a.json)。
普通应用构建不会编译 Native 代码。维护者可通过 GitHub Actions 或本地环境
重新生成，步骤见 [Native 编译指南](doc/BUILDING_NATIVE_ZH.md)。

## 其他资源

- [Native 编译指南](doc/BUILDING_NATIVE_ZH.md)
- [Native build guide](doc/BUILDING_NATIVE.md)
- [示例应用](example/)

## License

`mnn_engine` 使用 [Apache License 2.0](LICENSE)。Native 依赖和第三方版权信息见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。模型文件使用各自发布方的独立许可证。
