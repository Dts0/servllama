package com.arkanefans.mnn_engine.runtime

import android.os.SystemClock
import com.arkanefans.mnn_engine.MnnEngineOperationException
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.logging.MnnNativeDiagnostics
import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.arkanefans.mnn_engine.model.MnnTestDirectories
import com.arkanefans.mnn_engine.model.MnnTestModelRepository
import com.google.gson.JsonArray
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MnnRuntimeManager(
    private val directories: MnnTestDirectories,
    private val repository: MnnTestModelRepository,
    private val logStore: MnnLogStore,
    private val onStateChanged: (
        modelState: String,
        generationState: String,
        activeModel: MnnModelInfo?,
        lastError: String?,
    ) -> Unit,
) {
    data class GenerationResult(
        val promptTokens: Int,
        val completionTokens: Int,
        val prefillUs: Long,
        val decodeUs: Long,
        val sampleUs: Long,
        val finishReason: String,
    )

    private val lock = Any()
    private val generating = AtomicBoolean(false)
    @Volatile
    private var nativeSession: MnnNativeSession? = null
    @Volatile
    private var activeModel: MnnModelInfo? = null
    private var loadedOptions: MnnLoadOptions? = null
    private var baseConfigJson: String = "{}"
    private var backendCapabilitiesCache: List<Map<String, Any?>> = emptyList()

    fun activeModel(): MnnModelInfo? = activeModel

    fun backendCapabilities(): List<Map<String, Any?>> = synchronized(lock) {
        // The DSP probe opens/closes the shared FastRPC session. Never probe
        // over a resident model, including the gaps between generation calls.
        if (nativeSession == null && !generating.get()) {
            backendCapabilitiesCache = MnnNativeBridge.backendCapabilities()
        }
        backendCapabilitiesCache
    }

    fun load(modelId: String, options: MnnLoadOptions = MnnLoadOptions()): MnnModelInfo {
        synchronized(lock) {
            if (generating.get()) throw GenerationBusyException()
            val model = repository.find(modelId, activeModel?.modelId)
                ?: throw IllegalArgumentException("Model not found: $modelId")
            if (nativeSession != null && activeModel?.modelId == model.modelId && loadedOptions == options) {
                logStore.debug("runtime", "Reusing loaded model ${model.modelId}")
                return activeModel!!
            }
            onStateChanged("loading", "idle", activeModel, null)
            var nativeStartedAt: Long? = null
            try {
                nativeSession?.close()
                nativeSession = null
                activeModel = null
                loadedOptions = null
                val capability = backendCapabilities().first { it["backend"] == options.backend.wireName }
                if (capability["available"] != true) {
                    throw MnnEngineOperationException(
                        "backend_unavailable",
                        "${options.backend.wireName}: ${capability["reason"]}. ${(capability["detail"] as? String).orEmpty()}",
                        capability,
                    )
                }
                val runtimeConfig = createRuntimeConfig(model, options)
                val dspInfo = (capability["dspArchitecture"] as? String)?.let { ", dsp=$it" }.orEmpty()
                logStore.debug("runtime", "Loading ${model.modelId}, backend=${options.backend.wireName}$dspInfo")
                val loadStartedAt = SystemClock.elapsedRealtime()
                nativeStartedAt = System.currentTimeMillis()
                val session = MnnNativeSession.load(model.configPath, runtimeConfig.toString())
                val loadDurationMs = SystemClock.elapsedRealtime() - loadStartedAt
                baseConfigJson = runtimeConfig.toString()
                nativeSession = session
                loadedOptions = options
                activeModel = model.copy(
                    isActive = true,
                    loadDurationMs = loadDurationMs,
                    backend = options.backend.wireName,
                    useMmap = options.useMmap,
                    precision = options.precision.wireName,
                    threadNum = options.threadNum,
                )
                logStore.info(
                    "mnn",
                    "Loaded model ${model.modelId}, backend=${options.backend.wireName}, mmap=${options.useMmap}, precision=${options.precision.wireName}, threads=${options.threadNum}, in ${loadDurationMs}ms",
                )
                onStateChanged("loaded", "idle", activeModel, null)
                return activeModel!!
            } catch (error: Throwable) {
                nativeStartedAt?.let { MnnNativeDiagnostics.capture(logStore, it) }
                nativeSession?.close()
                nativeSession = null
                activeModel = null
                loadedOptions = null
                onStateChanged("error", "idle", null, error.message)
                throw error
            }
        }
    }

    fun unload() {
        synchronized(lock) {
            if (generating.get()) throw GenerationBusyException()
            if (nativeSession == null && activeModel == null) return
            onStateChanged("unloading", "idle", activeModel, null)
            nativeSession?.close()
            nativeSession = null
            activeModel = null
            loadedOptions = null
            baseConfigJson = "{}"
            logStore.info("runtime", "Model unloaded")
            onStateChanged("unloaded", "idle", null, null)
        }
    }

    fun generate(
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        canGenerate: () -> Boolean = { true },
        onToken: (String) -> Boolean,
    ): GenerationResult {
        val (session, generationModel) = synchronized(lock) {
            if (!generating.compareAndSet(false, true)) {
                if (!canGenerate()) throw ServerStoppingException()
                throw GenerationBusyException()
            }
            try {
                // Announce generation before checking admission: a concurrent
                // stop either closes this gate or waits to cancel after reset().
                if (!canGenerate()) throw ServerStoppingException()
                val currentSession = nativeSession
                    ?: throw IllegalStateException("No MNN model is loaded.")
                val currentModel = activeModel
                    ?: throw IllegalStateException("No active MNN model is available.")
                // Prepare before cancellation can acquire this lock. Native generate()
                // must not clear a stop received while the request is starting.
                currentSession.reset()
                currentSession to currentModel
            } catch (error: Throwable) {
                generating.set(false)
                throw error
            }
        }
        onStateChanged("loaded", "generating", generationModel, null)
        val generationStartedAt = System.currentTimeMillis()
        logStore.debug("request", "Generation started for ${generationModel.modelId}, backend=${generationModel.backend}")
        var failureMessage: String? = null
        return try {
            val config = JsonParser.parseString(baseConfigJson).asJsonObject
            temperature?.let { config.addProperty("temperature", it) }
            topP?.let { config.addProperty("topP", it) }
            if (maxTokens >= 0) config.addProperty("max_new_tokens", maxTokens)
            val jinja = config.getAsJsonObject("jinja") ?: JsonObject().also { config.add("jinja", it) }
            val context = jinja.getAsJsonObject("context") ?: JsonObject().also { jinja.add("context", it) }
            context.add("tools", tools.deepCopy())
            val metrics = session.generate(
                Gson().toJson(messages),
                config.toString(),
                maxTokens,
                MnnNativeSession.TokenCallback(onToken),
            )
            GenerationResult(
                promptTokens = metrics.promptTokens,
                completionTokens = metrics.completionTokens,
                prefillUs = metrics.prefillUs,
                decodeUs = metrics.decodeUs,
                sampleUs = metrics.sampleUs,
                finishReason = metrics.finishReason,
            ).also {
                logStore.info(
                    "request",
                    "Generation completed: prompt=${it.promptTokens}, completion=${it.completionTokens}, finish=${it.finishReason}",
                )
            }
        } catch (error: Throwable) {
            failureMessage = error.message ?: "MNN generation failed."
            MnnNativeDiagnostics.capture(logStore, generationStartedAt)
            synchronized(lock) {
                // A failed native session cannot recover through reset(). Clear
                // the resident model too so host caches request a real reload.
                nativeSession = null
                activeModel = null
                loadedOptions = null
                baseConfigJson = "{}"
                runCatching { session.close() }.onFailure { error.addSuppressed(it) }
            }
            // The HTTP boundary reports the failure once for both response modes.
            throw error
        } finally {
            synchronized(lock) {
                generating.set(false)
                onStateChanged(
                    if (activeModel == null) "unloaded" else "loaded",
                    "idle",
                    activeModel,
                    failureMessage,
                )
            }
        }
    }

    fun cancelGeneration() {
        // Model loading may hold lock for seconds. An idle cancel is a no-op
        // and must not block the platform/UI thread behind that work.
        if (!generating.get()) return
        synchronized(lock) {
            if (generating.get()) {
                nativeSession?.cancel()
                logStore.debug("request", "Generation cancellation requested")
            }
        }
    }

    fun release() {
        cancelGeneration()
        // Lifecycle teardown must obey the same native-drain guard as unload.
        // A cancellation signal alone does not make a JNI handle safe to close.
        unload()
    }

    private fun createRuntimeConfig(model: MnnModelInfo, options: MnnLoadOptions): JsonObject {
        val root = JsonParser.parseString(File(model.configPath).readText(Charsets.UTF_8)).asJsonObject
        return MnnRuntimeConfig.create(
            source = root,
            options = options,
            runtimeDir = directories.modelRuntimeDir(model.modelKey),
            mnnVersion = MnnNativeBridge.version().substringBefore(" ("),
        )
    }

    class GenerationBusyException : IllegalStateException("A generation request is already active.")
    class ServerStoppingException : IllegalStateException("MNN API server is stopping.")

}
