package com.arkanefans.mnn_engine.logging

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MnnNativeDiagnosticsTest {
    @Test
    fun retainsNativeFailureDetailsAndUnicode() {
        val result = MnnNativeDiagnostics.parseNative(
            """{"dropped":0,"records":[
                {"timestamp":1000,"priority":6,"tag":"MNNJNI",
                 "message":"Resize error for Attention, code=3\n形状错误 \uD83D\uDC41\n"}
            ]}""",
            1000,
        )
        assertEquals(6, result.records.single().priority)
        assertEquals("Resize error for Attention, code=3\n形状错误 👁", result.records.single().message)
        assertEquals(0, result.dropped)
    }

    @Test
    fun excludesEarlierOperationsUnrelatedTagsAndEmptyLines() {
        val result = MnnNativeDiagnostics.parseNative(nativeLogs(JsonArray().apply {
            add(record(timestamp = 999, message = "old error"))
            add(record(tag = "request", message = "unrelated"))
            add(record(message = "\n"))
            add(record(message = "current error"))
        }), 1000)
        assertEquals(listOf("current error"), result.records.map { it.message })
    }

    @Test
    fun mapsInternalInfoToDebugAndPreservesWarningsAndErrors() {
        Mockito.mockStatic(Log::class.java).use {
            val store = MnnLogStore()
            MnnNativeDiagnostics.capture(store, 1000) {
                nativeLogs(JsonArray().apply { for (priority in 2..7) add(record(priority = priority)) })
            }
            assertEquals(listOf("debug", "debug", "debug", "warning", "error", "error"),
                store.snapshot().map { it.level })
        }
    }

    @Test
    fun emptyNativeContextDoesNotProduceNoise() {
        Mockito.mockStatic(Log::class.java).use {
            val store = MnnLogStore()
            MnnNativeDiagnostics.capture(store, 0) { nativeLogs() }
            assertTrue(store.snapshot().isEmpty())
        }
    }

    @Test
    fun collectionFailureDoesNotMaskTheInferenceError() {
        Mockito.mockStatic(Log::class.java).use {
            for (read in listOf<() -> String>({ "{}" }, { throw UnsatisfiedLinkError("missing bridge") })) {
                val store = MnnLogStore()
                MnnNativeDiagnostics.capture(store, 0, read)
                assertEquals("debug", store.snapshot().single().level)
                assertTrue(store.snapshot().single().message.startsWith("Could not read MNN error details:"))
            }
        }
    }

    @Test
    fun reportsTruncationOnlyAtDebugLevel() {
        Mockito.mockStatic(Log::class.java).use {
            val store = MnnLogStore()
            MnnNativeDiagnostics.capture(store, 0) { nativeLogs(dropped = 12) }
            assertEquals("debug", store.snapshot().single().level)
            assertTrue(store.snapshot().single().message.contains("12"))
        }
    }

    private fun record(
        timestamp: Long = 1000,
        priority: Int = 6,
        tag: String = "MNNJNI",
        message: String = "native detail",
    ) = JsonObject().apply {
        addProperty("timestamp", timestamp)
        addProperty("priority", priority)
        addProperty("tag", tag)
        addProperty("message", message)
    }

    private fun nativeLogs(records: JsonArray = JsonArray(), dropped: Long = 0) = JsonObject().apply {
        add("records", records)
        addProperty("dropped", dropped)
    }.toString()
}
