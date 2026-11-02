package com.arkanefans.mnn_engine.server

import android.content.Context
import android.os.SystemClock
import com.arkanefans.mnn_engine.MnnEngineOperationException
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.arkanefans.mnn_engine.runtime.MnnNativeBridge
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.slf4j.event.Level
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class MnnOpenAiServer(
    private val context: Context,
    private val runtimeManager: MnnRuntimeManager,
    private val snapshotProvider: () -> Map<String, Any?>,
    private val logStore: MnnLogStore,
) {
    private val mediaStager = MnnMediaStager(context, logStore)
    private var requestGate: MnnRequestGate? = null
    @Volatile
    private var engine: EmbeddedServer<*, *>? = null
    @Volatile
    private var serverInfo: MnnServerInfo? = null
    @Volatile
    private var bindMode: MnnBindMode = MnnBindMode.LOOPBACK

    @Synchronized
    fun start(mode: MnnBindMode, port: Int, configuredApiKey: String?): MnnServerInfo {
        check(engine == null) { "MNN API Server is already running." }
        val gate = MnnRequestGate()
        mediaStager.cleanupStale()
        val startedAt = System.currentTimeMillis() / 1000
        val startMonotonic = SystemClock.elapsedRealtime()
        val created = embeddedServer(
            factory = CIO,
            configure = {
                MnnServerSocketPolicy.configure(this, mode.host, port)
            },
        ) {
            install(CallLogging) { level = Level.DEBUG }
            routing {
                get("/") {
                    logStore.debug(TAG, "GET /")
                    val html = context.assets.open(TEST_PAGE_ASSET).bufferedReader().use { it.readText() }
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/health") {
                    if (!authorize(configuredApiKey)) return@get
                    logStore.debug(TAG, "GET /health")
                    respondJson(HttpStatusCode.OK, healthPayload())
                }
                get("/v1/models") {
                    if (!authorize(configuredApiKey)) return@get
                    logStore.debug(TAG, "GET /v1/models")
                    val model = runtimeManager.activeModel()
                    val data = JsonArray()
                    if (model != null) data.add(modelPayload(model))
                    respondJson(HttpStatusCode.OK, JsonObject().apply {
                        addProperty("object", "list")
                        add("data", data)
                    })
                }
                post("/v1/chat/completions") {
                    if (!authorize(configuredApiKey)) return@post
                    if (!gate.isOpen) {
                        respondServerStopping()
                        return@post
                    }
                    val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (contentLength != null && contentLength > MnnChatRequestParser.MAX_REQUEST_BYTES) {
                        respondOpenAiError(HttpStatusCode.PayloadTooLarge, "request_too_large", "Request body exceeds 16 MiB.")
                        return@post
                    }
                    val request = try {
                        MnnChatRequestParser.parse(call.receiveText())
                    } catch (error: IllegalArgumentException) {
                        logStore.warn(TAG, "Rejected invalid chat request: ${error.message}")
                        respondOpenAiError(HttpStatusCode.BadRequest, "invalid_request", error.message ?: "Invalid request.")
                        return@post
                    }
                    val activeModel = runtimeManager.activeModel()
                    if (activeModel == null) {
                        respondOpenAiError(HttpStatusCode.ServiceUnavailable, "model_not_loaded", "No MNN model is loaded.")
                        return@post
                    }
                    if (request.model != null && request.model != activeModel.modelId) {
                        respondOpenAiError(HttpStatusCode.NotFound, "model_not_found", "Only ${activeModel.modelId} is loaded.")
                        return@post
                    }
                    if (request.hasTools && !activeModel.supportsToolCalling) {
                        respondOpenAiError(HttpStatusCode.BadRequest, "model_tool_calling_not_supported", "The loaded model does not provide a tool-capable chat template.")
                        return@post
                    }
                    if (request.hasImageContent() && !activeModel.supportsVision) {
                        respondOpenAiError(HttpStatusCode.BadRequest, "model_vision_not_supported", "The loaded model does not provide a visual model.")
                        return@post
                    }
                    // Internal ownership for cancellation, logs and staged media.
                    // HTTP clients need no engine-specific headers or control API.
                    val requestId = UUID.randomUUID().toString()
                    when (gate.acquire(requestId)) {
                        MnnRequestGate.Admission.STOPPING -> {
                            respondServerStopping()
                            return@post
                        }
                        MnnRequestGate.Admission.BUSY -> {
                            respondOpenAiError(HttpStatusCode.TooManyRequests, "request_queue_full", "Another generation request is active.")
                            return@post
                        }
                        MnnRequestGate.Admission.ACCEPTED -> Unit
                    }
                    val requestStartedAt = System.nanoTime()
                    logStore.debug(TAG, "$requestId POST /v1/chat/completions started")
                    try {
                        val staged = try {
                            mediaStager.stage(requestId, request.messages)
                        } catch (error: IllegalArgumentException) {
                            logStore.warn(TAG, "Rejected media request: ${error.message}")
                            respondOpenAiError(HttpStatusCode.BadRequest, "invalid_image", error.message ?: "Invalid image content.")
                            return@post
                        }
                        staged.use { stagedMessages ->
                            if (!gate.isOpen) {
                                respondServerStopping()
                                return@post
                            }
                            val messages = request.withToolPolicy(stagedMessages.messages)
                            if (request.stream) streamCompletion(request, activeModel, messages, gate, requestId)
                            else nonStreamCompletion(request, activeModel, messages, gate, requestId)
                        }
                    } catch (error: Throwable) {
                        // A committed response can no longer carry an HTTP
                        // error; generate() has already joined native work.
                        if (error is IOException && call.response.isCommitted) return@post
                        val (status, code) = generationError(error)
                        if (!call.response.isCommitted) {
                            respondOpenAiError(status, code, error.message ?: "MNN generation failed.")
                        }
                    } finally {
                        gate.release(requestId)
                        logStore.debug(TAG, "$requestId POST /v1/chat/completions completed in ${(System.nanoTime() - requestStartedAt) / 1_000_000}ms")
                    }
                }
            }
        }
        return try {
            created.start(wait = false)
            engine = created
            requestGate = gate
            bindMode = mode
            MnnServerInfo(
                running = true,
                bindMode = mode.wireName,
                bindAddress = mode.host,
                port = port,
                localBaseUrl = "http://127.0.0.1:$port",
                advertisedUrls = if (mode == MnnBindMode.ALL_INTERFACES) networkUrls(port) else emptyList(),
                requiresApiKey = !configuredApiKey.isNullOrEmpty(),
                startedAt = startedAt,
                startDurationMs = SystemClock.elapsedRealtime() - startMonotonic,
            ).also { info ->
                serverInfo = info
                logStore.info(TAG, "Server started at ${info.bindAddress}:$port mode=${info.bindMode}")
            }
        } catch (error: Throwable) {
            gate.close()
            runCatching { created.stop(0, 0) }
            throw error
        }
    }

    @Synchronized
    fun stop() {
        requestGate?.close()
        serverInfo = serverInfo?.copy(running = false)
        runtimeManager.cancelGeneration()
        engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        if (requestGate?.awaitIdle(timeoutMillis = 5000) == false) {
            // Retain the closed gate/server ownership so neither a restart nor
            // model unloading can race native work left behind by CIO's timeout.
            throw MnnEngineOperationException(
                "server_stop_timeout",
                "Native generation is still stopping. Retry stopServer before unloading the model.",
            )
        }
        engine = null
        serverInfo = null
        requestGate = null
        mediaStager.cleanupStale()
        logStore.info(TAG, "Server stopped")
    }

    fun info(): MnnServerInfo? = serverInfo

    private suspend fun io.ktor.server.routing.RoutingContext.authorize(required: String?): Boolean {
        if (required.isNullOrEmpty()) return true
        val actual = call.request.headers[HttpHeaders.Authorization]
        if (actual != null && actual.startsWith("Bearer ") && constantTimeEquals(actual.removePrefix("Bearer "), required)) return true
        respondOpenAiError(HttpStatusCode.Unauthorized, "invalid_api_key", "A valid Bearer API key is required.")
        return false
    }

    private fun healthPayload(): JsonObject {
        val snapshot = snapshotProvider()
        return JsonObject().apply {
            addProperty("status", when {
                snapshot["lastError"] != null -> "error"
                snapshot["modelState"] == "loading" -> "loading"
                snapshot["serverState"] == "starting" -> "starting"
                snapshot["generationState"] == "generating" -> "generating"
                else -> "ready"
            })
            addProperty("engine", "mnn")
            addProperty("model", runtimeManager.activeModel()?.modelId)
            addProperty("mnn_version", runCatching { MnnNativeBridge.version().substringBefore(" (") }.getOrDefault("unavailable"))
            addProperty("server_started_at", serverInfo?.startedAt)
            addProperty("bind_mode", serverInfo?.bindMode)
        }
    }

    private fun modelPayload(model: MnnModelInfo) = JsonObject().apply {
        addProperty("id", model.modelId)
        addProperty("object", "model")
        addProperty("created", model.importedAt / 1000)
        addProperty("owned_by", "mnn")
    }

    private suspend fun io.ktor.server.routing.RoutingContext.nonStreamCompletion(
        request: MnnChatRequest,
        model: MnnModelInfo,
        messages: List<JsonObject>,
        gate: MnnRequestGate,
        requestId: String,
    ) {
        val output = StringBuilder()
        val metrics = generate(request, messages, gate, requestId) { token -> output.append(token) }
        val parsed = parseCompletion(request, model, output.toString())
        val finish = completionFinishReason(metrics.finishReason, parsed.toolCalls.isNotEmpty())
        respondJson(HttpStatusCode.OK, JsonObject().apply {
            addProperty("id", "chatcmpl-${UUID.randomUUID()}")
            addProperty("object", "chat.completion")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", model.modelId)
            add("choices", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("index", 0)
                    add("message", assistantMessage(parsed))
                    addProperty("finish_reason", finish)
                })
            })
            add("usage", usage(metrics))
            MnnGenerationTimings.from(metrics)?.let { add("timings", it) }
        })
    }

    private suspend fun io.ktor.server.routing.RoutingContext.streamCompletion(
        request: MnnChatRequest,
        model: MnnModelInfo,
        messages: List<JsonObject>,
        gate: MnnRequestGate,
        requestId: String,
    ) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.respondBytesWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            val completionId = "chatcmpl-${UUID.randomUUID()}"
            val created = System.currentTimeMillis() / 1000
            suspend fun send(data: String) {
                writeStringUtf8("data: $data\n\n")
                flush()
            }
            suspend fun sendDelta(delta: MnnReasoningDelta) {
                if (delta.isEmpty) return
                send(chunk(
                    completionId,
                    created,
                    model.modelId,
                    content = delta.content.ifEmpty { null },
                    reasoningContent = delta.reasoningContent.ifEmpty { null },
                ).toString())
            }
            send(initialChunk(completionId, created, model.modelId).toString())
            val output = StringBuilder()
            val reasoningParser = if (request.hasTools) {
                null
            } else {
                MnnReasoningOutputParser(MnnReasoningProfileDetector.detect(model))
            }
            try {
                val metrics = generate(
                    request, messages, gate, requestId,
                    onHeartbeat = { writeStringUtf8(": keep-alive\n\n"); flush() },
                ) { token ->
                    if (request.hasTools) output.append(token)
                    else for (delta in reasoningParser!!.accept(token)) sendDelta(delta)
                }
                for (delta in reasoningParser?.finish().orEmpty()) sendDelta(delta)
                val parsed = if (request.hasTools) {
                    parseCompletion(request, model, output.toString())
                } else {
                    null
                }
                parsed?.reasoningContent?.let {
                    send(chunk(
                        completionId,
                        created,
                        model.modelId,
                        reasoningContent = it,
                    ).toString())
                }
                parsed?.content?.let {
                    send(chunk(completionId, created, model.modelId, content = it).toString())
                }
                if (parsed != null && parsed.toolCalls.isNotEmpty()) {
                    send(toolCallsChunk(
                        completionId,
                        created,
                        model.modelId,
                        parsed.toolCalls,
                    ).toString())
                }
                send(chunk(
                    completionId,
                    created,
                    model.modelId,
                    finishReason = completionFinishReason(
                        metrics.finishReason,
                        parsed?.toolCalls?.isNotEmpty() == true,
                    ),
                ).apply {
                    add("usage", usage(metrics))
                    MnnGenerationTimings.from(metrics)?.let { add("timings", it) }
                }.toString())
                send("[DONE]")
            } catch (_: IOException) {
                // This includes heartbeat failures before the first token.
            } catch (error: Throwable) {
                val (_, code) = generationError(error)
                runCatching {
                    send(JsonObject().apply { add("error", JsonObject().apply {
                        addProperty("message", error.message ?: "MNN generation failed.")
                        addProperty("code", code)
                    }) }.toString())
                    send("[DONE]")
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun generate(
        request: MnnChatRequest,
        messages: List<JsonObject>,
        gate: MnnRequestGate,
        requestId: String,
        onHeartbeat: (suspend () -> Unit)? = null,
        onToken: suspend (String) -> Unit,
    ): MnnRuntimeManager.GenerationResult = supervisorScope {
        // Only this coroutine writes the response. The bounded channel keeps
        // backpressure without writing to a socket from a synchronous JNI callback.
        val tokens = Channel<String>(16)
        val native = async(Dispatchers.IO) {
            try {
                runtimeManager.generate(
                    messages, request.effectiveTools(), request.temperature, request.topP, request.maxTokens,
                    canGenerate = { gate.allows(requestId) },
                    onToken = { token ->
                        if (!gate.allows(requestId)) true else try {
                            runBlocking { tokens.send(token) }
                            false
                        } catch (_: CancellationException) { true }
                    },
                )
            } finally {
                tokens.close()
            }
        }
        try {
            var nextHeartbeat = System.nanoTime() + 1_000_000_000
            while (select {
                tokens.onReceiveCatching { next ->
                    if (next.isClosed) false else {
                        onToken(next.getOrThrow())
                        true
                    }
                }
                if (onHeartbeat != null) {
                    onTimeout(((nextHeartbeat - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) { true }
                }
            }) {
                // Use elapsed time, not a token-idle timeout: buffered tool
                // output may keep producing tokens without any HTTP writes.
                if (onHeartbeat != null && System.nanoTime() >= nextHeartbeat) {
                    onHeartbeat()
                    nextHeartbeat = System.nanoTime() + 1_000_000_000
                }
            }
            native.await()
        } finally {
            // Coroutine cancellation alone cannot interrupt JNI, especially
            // prefill. Signal the owned generation, unblock its callback, then
            // join without cancellation before releasing admission or media.
            try {
                if (!native.isCompleted) gate.cancel(requestId) { runtimeManager.cancelGeneration() }
            } finally {
                tokens.cancel()
                withContext(NonCancellable) { native.join() }
            }
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondServerStopping() {
        respondOpenAiError(HttpStatusCode.ServiceUnavailable, "server_stopping", "MNN API server is stopping.")
    }

    private fun generationError(error: Throwable): Pair<HttpStatusCode, String> = when (error) {
        is CancellationException -> throw error
        is MnnRuntimeManager.ServerStoppingException -> HttpStatusCode.ServiceUnavailable to "server_stopping"
        is MnnRuntimeManager.GenerationBusyException -> HttpStatusCode.TooManyRequests to "request_queue_full"
        else -> {
            logStore.error(TAG, "Generation failed", error)
            HttpStatusCode.InternalServerError to "generation_failed"
        }
    }

    private fun parseTools(request: MnnChatRequest, output: String): MnnParsedCompletion {
        val names = request.tools.map { it.asJsonObject.getAsJsonObject("function").get("name").asString }.toSet()
        return MnnToolCallParser.parse(output, names, request.parallelToolCalls).also { parsed ->
            parsed.diagnostic?.let { logStore.warn(TAG, it) }
        }
    }

    private fun parseCompletion(
        request: MnnChatRequest,
        model: MnnModelInfo,
        output: String,
    ): MnnParsedCompletion {
        val reasoning = MnnReasoningOutputParser.parse(
            output,
            MnnReasoningProfileDetector.detect(model),
        )
        val parsed = if (request.hasTools) {
            parseTools(request, reasoning.content.orEmpty())
        } else {
            MnnParsedCompletion(reasoning.content, emptyList())
        }
        return parsed.copy(reasoningContent = reasoning.reasoningContent)
    }

    private fun assistantMessage(parsed: MnnParsedCompletion) = JsonObject().apply {
        addProperty("role", "assistant")
        addProperty("content", parsed.content.orEmpty())
        parsed.reasoningContent?.let { addProperty("reasoning_content", it) }
        if (parsed.toolCalls.isNotEmpty()) add("tool_calls", toolCalls(parsed.toolCalls))
    }

    private fun toolCalls(calls: List<MnnToolCall>) = JsonArray().also { array -> calls.forEach { call ->
        array.add(JsonObject().apply {
            addProperty("id", call.id); addProperty("type", "function")
            add("function", JsonObject().apply { addProperty("name", call.name); addProperty("arguments", call.arguments) })
        })
    } }

    private fun toolCallsChunk(id: String, created: Long, model: String, calls: List<MnnToolCall>) = chunk(id, created, model).apply {
        getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("delta").add("tool_calls", toolCalls(calls).also { calls.forEachIndexed { index, call -> it.get(index).asJsonObject.addProperty("index", index) } })
    }

    private fun initialChunk(id: String, created: Long, model: String) =
        chunk(id, created, model, role = "assistant").apply {
            getAsJsonArray("choices").get(0).asJsonObject
                .getAsJsonObject("delta")
                .add("content", com.google.gson.JsonNull.INSTANCE)
        }

    private fun chunk(
        id: String,
        created: Long,
        model: String,
        role: String? = null,
        content: String? = null,
        reasoningContent: String? = null,
        finishReason: String? = null,
    ) = JsonObject().apply {
        addProperty("id", id); addProperty("object", "chat.completion.chunk"); addProperty("created", created); addProperty("model", model)
        add("choices", JsonArray().apply { add(JsonObject().apply {
            addProperty("index", 0); add("delta", JsonObject().apply {
                role?.let { addProperty("role", it) }
                content?.let { addProperty("content", it) }
                reasoningContent?.let { addProperty("reasoning_content", it) }
            })
            if (finishReason == null) add("finish_reason", null) else addProperty("finish_reason", finishReason)
        }) })
    }

    private fun usage(metrics: MnnRuntimeManager.GenerationResult) = JsonObject().apply {
        addProperty("prompt_tokens", metrics.promptTokens); addProperty("completion_tokens", metrics.completionTokens); addProperty("total_tokens", metrics.promptTokens + metrics.completionTokens)
        add("prompt_tokens_details", JsonObject().apply {
            // The native bridge exposes no independently measured cache-hit
            // count. Unknown must remain unknown, never a fabricated zero.
            add("cached_tokens", com.google.gson.JsonNull.INSTANCE)
            addProperty("cache_status", "unavailable")
        })
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondJson(status: HttpStatusCode, body: JsonObject) = call.respondText(body.toString(), ContentType.Application.Json, status)

    private suspend fun io.ktor.server.routing.RoutingContext.respondOpenAiError(status: HttpStatusCode, code: String, message: String) = respondJson(status, JsonObject().apply { add("error", JsonObject().apply { addProperty("message", message); addProperty("type", "invalid_request_error"); addProperty("code", code) }) })

    private fun normalizeFinishReason(reason: String): String = when (reason) { "length" -> "length"; else -> "stop" }

    private fun completionFinishReason(reason: String, hasToolCalls: Boolean): String = when {
        reason == "length" -> "length"
        hasToolCalls -> "tool_calls"
        else -> normalizeFinishReason(reason)
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean {
        val a = actual.toByteArray(Charsets.UTF_8); val b = expected.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(a, b)
    }

    private fun networkUrls(port: Int): List<String> = runCatching<List<String>> {
        NetworkInterface.getNetworkInterfaces().asSequence().flatMap { network ->
            network.inetAddresses.asSequence().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.map { "http://${it.hostAddress}:$port" }
        }.distinct().sorted().toList()
    }.getOrElse { emptyList() }

    private fun MnnChatRequest.hasImageContent(): Boolean = messages.any { message ->
        message.get("content")?.isJsonArray == true && message.getAsJsonArray("content").iterator().asSequence().any { it.asJsonObject.get("type")?.asString == "image_url" }
    }

    private companion object {
        const val TAG = "server"
        const val TEST_PAGE_ASSET = "mnn_test_page.html"
    }
}
