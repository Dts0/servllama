# HTTP 请求取消与停服契约

适用：0.2.0。MNN 原生 ABI 和固定的 3.6.1 源码版本不变。

## 客户端用法

HTTP 客户端通过取消自己的请求或关闭响应流停止接收。无需专用请求头、MethodChannel 取消接口或状态轮询；请求 ID 仅在服务器内部使用。

插件仍只同时执行一个请求，其他请求返回 HTTP 429 / request_queue_full。此错误码沿用已有兼容行为，不代表存在 FIFO 队列。

cancelGeneration 保持原语义：向当前原生生成发出取消信号，服务继续可用；返回不代表 JNI 已退出。宿主不要在某个聊天请求关闭后额外调用这个无请求归属的接口，否则可能取消之后接入的外部请求。

## 服务内部保证

- 每个接纳请求分配内部 UUID，用于门控、日志和媒体所有权。门控核对归属，重复/迟到回调不会取消其他请求。
- JNI 同步调用与 HTTP 输出分开；最多缓存 16 个 token 片段，发送只发生在 HTTP 协程。SSE 每约 1 秒发送注释心跳，覆盖 prefill 和持续产生 token 的工具缓冲阶段。
- 发现 HTTP 协程取消或写失败后，先停止该请求的 native，再解除回调背压，最后等待 native 返回。原生调用尚未退出时，门控不接受新请求，也不删除其媒体。
- stopServer 关闭准入、取消生成、停止 CIO 并等待请求返回。CIO 停止最多 5 秒，之后原生门控最多再等 5 秒；超时抛 server_stop_timeout，保留关闭中的服务所有权。原生 reset/cancel 调用本身不承诺固定时长。
- 超时后不允许 loadModel、unloadModel 或另起服务。原生退出后，再调用 stopServer 完成收尾，再 unloadModel。服务清理失败不能被宿主当作资源已经释放。
- Service 销毁时也遵守生成中的卸载保护，不能仅因“发出了取消”就销毁 JNI 句柄。

## 可观测性的边界

TCP FIN 也可以表示合法半关闭，发送完成的客户端可能仍在读取。服务不把 FIN 本身当作取消凭据。TCP reset、HTTP 协程取消或 SSE 写失败才提供可处理的终止信号；网络栈与代理可能推迟这些信号。

非流式请求仍返回完整 JSON，不写心跳、不提前提交 200。活动读连接的 reset 可打断等待；仅半关闭或静默失联可能直到最终写入才被发现。应用需要可靠地换模型时，应使用 stopServer → unloadModel 的确认流程，不根据关闭客户端 HTTP 推断 native 释放。

原生取消是协作式的。MNN 批次、图编译、GPU 调优和视觉编码可能延迟退出；已有[真机记录](PREFILL_CANCELLATION_DEVICE_ACCEPTANCE_ZH.md)不能替代新链路的设备验收。

## 验证

MnnOpenAiServerCancellationTest 使用真实 CIO 和 TCP socket，仅将同步 JNI 替换成阻塞夹具。覆盖 Connection: close 后靠 SSE 写入发现 reset、普通非流式 reset、持续工具 token 下的心跳、合法半关闭、门控等待、停服超时与重试。MnnRequestGateTest 覆盖所有权与迟到回调；MnnRuntimeManagerTest 覆盖原生入口/reset 和 teardown 保护。

Android 验收需使用真实 Dio/浏览器客户端，在首 token 前、普通解码、工具缓冲时分别取消，记录收到停止信号至原生返回的耗时；紧接新请求确认 429/恢复，交错外部请求确认没有误取消。首次 GPU 调优超过停服等待窗口时，应得到保留所有权的错误，待退出后可重试停止与卸载。
