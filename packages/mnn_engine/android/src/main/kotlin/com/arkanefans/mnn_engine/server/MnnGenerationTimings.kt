package com.arkanefans.mnn_engine.server

import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.google.gson.JsonObject

/** Per-request native measurements, using llama-server's response field names. */
internal object MnnGenerationTimings {
    fun from(metrics: MnnRuntimeManager.GenerationResult): JsonObject? {
        // In particular, a cancelled prefill retains the full prompt length but
        // only completed chunks' time. Dividing those would invent a speed.
        if (metrics.finishReason != "stop" && metrics.finishReason != "length") return null
        val hasPrefill = metrics.promptTokens > 0 && metrics.prefillUs > 0
        val hasDecode = metrics.completionTokens > 0 && metrics.decodeUs > 0
        if (!hasPrefill && !hasDecode) return null
        return JsonObject().apply {
            addProperty("backend", "mnn")
            // There is no independently measured cache-hit/processed counter
            // in this bridge. Do not manufacture cache_n or call this uncached.
            addProperty("prompt_tokens_scope", "total_input")
            addProperty("predicted_tokens_scope", "native_generated")
            addProperty("decode_time_scope", "native_decode")
            if (hasPrefill) {
                addProperty("prompt_n", metrics.promptTokens)
                addProperty("prompt_ms", metrics.prefillUs / 1000.0)
                addProperty("prompt_per_second", metrics.promptTokens.toDouble() * 1_000_000 / metrics.prefillUs)
            }
            if (hasDecode) {
                addProperty("predicted_n", metrics.completionTokens)
                addProperty("predicted_ms", metrics.decodeUs / 1000.0)
                addProperty("predicted_per_second", metrics.completionTokens.toDouble() * 1_000_000 / metrics.decodeUs)
            }
        }
    }
}
