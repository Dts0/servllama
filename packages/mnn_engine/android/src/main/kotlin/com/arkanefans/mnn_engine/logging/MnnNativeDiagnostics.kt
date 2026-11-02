package com.arkanefans.mnn_engine.logging

import com.arkanefans.mnn_engine.runtime.MnnNativeBridge
import com.google.gson.JsonParser

/** Adds MNN's bounded native error context when a load or generation fails. */
internal object MnnNativeDiagnostics {
    data class Record(val priority: Int, val message: String)
    data class Capture(val records: List<Record>, val dropped: Long)

    fun capture(
        logStore: MnnLogStore,
        sinceMillis: Long,
        readNative: () -> String = { MnnNativeBridge.takeDiagnosticLogs(sinceMillis) },
    ) {
        // Diagnostics must never replace the original inference exception.
        runCatching { parseNative(readNative(), sinceMillis) }
            .onSuccess { result ->
                for (record in result.records) {
                    when (record.priority) {
                        6, 7 -> logStore.error("native", record.message)
                        5 -> logStore.warn("native", record.message)
                        // MNN_PRINT emits internal runtime details at INFO.
                        // They belong to DEBUG in the application's log.
                        else -> logStore.debug("native", record.message)
                    }
                }
                if (result.dropped > 0) {
                    logStore.debug("native", "Omitted ${result.dropped} native log records; buffer limit reached")
                }
            }
            .onFailure { error ->
                logStore.debug("native", "Could not read MNN error details: ${error.message}")
            }
    }

    internal fun parseNative(text: String, sinceMillis: Long): Capture {
        val root = JsonParser.parseString(text).asJsonObject
        val records = root.getAsJsonArray("records").mapNotNull { element ->
            val item = element.asJsonObject
            if (item.get("timestamp").asLong < sinceMillis || item.get("tag").asString != "MNNJNI") {
                return@mapNotNull null
            }
            val message = item.get("message").asString.trimEnd()
            if (message.isEmpty()) return@mapNotNull null
            Record(item.get("priority").asInt, message)
        }
        return Capture(records, root.get("dropped").asLong)
    }
}
