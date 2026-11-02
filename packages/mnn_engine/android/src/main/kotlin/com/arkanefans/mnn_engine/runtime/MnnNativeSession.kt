package com.arkanefans.mnn_engine.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser

class MnnNativeSession private constructor(
    private var nativeHandle: Long,
) : AutoCloseable {
    fun interface TokenCallback {
        fun onToken(token: String): Boolean
    }

    data class GenerationMetrics(
        val promptTokens: Int,
        val completionTokens: Int,
        val prefillUs: Long,
        val decodeUs: Long,
        val sampleUs: Long,
        val finishReason: String,
    )

    fun generate(
        messagesJson: String,
        requestConfigJson: String,
        maxTokens: Int,
        callback: TokenCallback,
    ): GenerationMetrics {
        val handle = requireHandle()
        val json = JsonParser.parseString(
            nativeGenerate(handle, messagesJson, requestConfigJson, maxTokens, callback),
        ).asJsonObject
        return GenerationMetrics(
            promptTokens = json.int("prompt_tokens"),
            completionTokens = json.int("completion_tokens"),
            prefillUs = json.long("prefill_us"),
            decodeUs = json.long("decode_us"),
            sampleUs = json.long("sample_us"),
            finishReason = json.string("finish_reason") ?: "stop",
        )
    }

    fun cancel() {
        nativeHandle.takeIf { it != 0L }?.let(::nativeCancel)
    }

    fun reset() {
        nativeHandle.takeIf { it != 0L }?.let(::nativeReset)
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeRelease(handle)
        }
    }

    private fun requireHandle(): Long {
        return nativeHandle.takeIf { it != 0L }
            ?: throw IllegalStateException("MNN native session has been released.")
    }

    private external fun nativeGenerate(
        handle: Long,
        messagesJson: String,
        requestConfigJson: String,
        maxTokens: Int,
        callback: TokenCallback,
    ): String

    private external fun nativeCancel(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        fun load(configPath: String, configJson: String): MnnNativeSession {
            check(MnnNativeBridge.loaded) { "MNN native libraries are unavailable." }
            val handle = nativeCreate(configPath, configJson)
            check(handle != 0L) { "MNN native session creation returned null." }
            return MnnNativeSession(handle)
        }

        @JvmStatic
        private external fun nativeCreate(configPath: String, configJson: String): Long
    }
}

private fun JsonObject.int(key: String): Int = get(key)?.asInt ?: 0
private fun JsonObject.long(key: String): Long = get(key)?.asLong ?: 0L
private fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
