package com.arkanefans.mnn_engine.server

import android.content.Context
import android.os.SystemClock
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.mockito.Mockito
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Verifies the actual HTTP/SSE boundary, with only native generation mocked. */
class MnnOpenAiServerTimingsTest {
    @Test
    fun nonStreamReturnsNativeTimingsAlongsideExistingUsage() {
        Fixture().use { f ->
            val (status, body) = f.post()
            assertEquals(200, status)
            val json = JsonParser.parseString(body).asJsonObject
            assertEquals("ok", json.getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")["content"].asString)
            assertEquals(120, json.getAsJsonObject("usage")["total_tokens"].asInt)
            assertUnknownCache(json)
            assertEquals(400.0, json.getAsJsonObject("timings")["prompt_per_second"].asDouble)
            assertEquals(10.0, json.getAsJsonObject("timings")["predicted_per_second"].asDouble)
        }
    }

    @Test
    fun streamReportsTimingsOnlyOnFinalChunkWithSameCompletionId() {
        Fixture().use { f ->
            val (status, body) = f.post(stream = true)
            assertEquals(200, status)
            assertTrue(body.endsWith("data: [DONE]\n\n"))
            val events = events(body)
            assertTrue(events.size >= 3)
            assertEquals(1, events.map { it["id"].asString }.distinct().size)
            assertTrue(events.dropLast(1).none { it.has("timings") || it.has("usage") })
            val last = events.last()
            assertEquals("stop", last.getAsJsonArray("choices")[0].asJsonObject["finish_reason"].asString)
            assertEquals(100, last.getAsJsonObject("usage")["prompt_tokens"].asInt)
            assertUnknownCache(last)
            assertEquals(400.0, last.getAsJsonObject("timings")["prompt_per_second"].asDouble)
            assertEquals(10.0, last.getAsJsonObject("timings")["predicted_per_second"].asDouble)
        }
    }

    @Test
    fun successiveResponsesCannotReusePreviousRequestTimings() {
        Fixture().use { f ->
            val first = JsonParser.parseString(f.post().second).asJsonObject
            f.metrics = f.metrics.copy(promptTokens = 12, completionTokens = 4, prefillUs = 2_000_000, decodeUs = 1_000_000)
            val second = events(f.post(stream = true).second).last()
            assertNotEquals(first["id"].asString, second["id"].asString)
            assertEquals(6.0, second.getAsJsonObject("timings")["prompt_per_second"].asDouble)
            assertEquals(4.0, second.getAsJsonObject("timings")["predicted_per_second"].asDouble)
            assertEquals(400.0, first.getAsJsonObject("timings")["prompt_per_second"].asDouble)
        }
    }

    @Test
    fun cancelledAndZeroDurationRequestsDoNotPublishMisleadingSpeeds() {
        Fixture().use { f ->
            val complete = f.metrics
            assertTrue(JsonParser.parseString(f.post().second).asJsonObject.has("timings"))
            for (stream in listOf(false, true)) {
                for (metrics in listOf(
                    complete.copy(finishReason = "cancelled"),
                    complete.copy(prefillUs = 0, decodeUs = 0),
                )) {
                    f.metrics = metrics
                    val (status, body) = f.post(stream)
                    assertEquals(200, status)
                    assertFalse(body.contains("\"timings\""))
                }
            }
        }
    }

    @Test
    fun tokenLimitKeepsItsFinishReasonAndTimings() {
        Fixture().use { f ->
            f.metrics = f.metrics.copy(finishReason = "length")
            for (stream in listOf(false, true)) {
                val (_, body) = f.post(stream)
                val result = if (stream) events(body).last() else JsonParser.parseString(body).asJsonObject
                assertEquals("length", result.getAsJsonArray("choices")[0].asJsonObject["finish_reason"].asString)
                assertTrue(result.has("timings"))
            }
        }
    }

    @Test
    fun failedRequestsHaveNoTimingMetadataInEitherResponseMode() {
        Fixture().use { f ->
            f.failure = IllegalStateException("native test failure")
            val nonStream = f.post()
            assertEquals(500, nonStream.first)
            assertTrue(nonStream.second.contains("generation_failed"))
            assertFalse(nonStream.second.contains("\"timings\""))
            val stream = f.post(stream = true)
            assertEquals(200, stream.first)
            assertTrue(stream.second.contains("generation_failed"))
            assertFalse(stream.second.contains("\"timings\""))
        }
    }

    private fun events(body: String): List<JsonObject> = body.lineSequence()
        .filter { it.startsWith("data: {") }
        .map { JsonParser.parseString(it.removePrefix("data: ")).asJsonObject }.toList()

    private fun assertUnknownCache(response: JsonObject) {
        val details = response.getAsJsonObject("usage").getAsJsonObject("prompt_tokens_details")
        assertTrue(details.has("cached_tokens"))
        assertTrue(details["cached_tokens"].isJsonNull)
        assertEquals("unavailable", details["cache_status"].asString)
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("mnn-http-timings").toFile()
        @Volatile var metrics = MnnRuntimeManager.GenerationResult(100, 20, 250_000, 2_000_000, 750_000, "stop")
        @Volatile var failure: Throwable? = null
        private val model = MnnModelInfo(
            modelId = "qwen", modelKey = "qwen", displayName = "Qwen", vendor = null,
            modelDirPath = "unused", configPath = "unused/config.json", sizeBytes = 0,
            importedAt = 0, isActive = true, backend = "cpu", supportsToolCalling = true,
        )
        private val runtime = Mockito.mock(MnnRuntimeManager::class.java) { invocation ->
            when (invocation.method.name) {
                "activeModel" -> model
                "generate" -> {
                    failure?.let { throw it }
                    val onToken = invocation.getArgument<(String) -> Boolean>(6)
                    assertFalse(onToken("ok"))
                    metrics
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        private val port = ServerSocket(0).use { it.localPort }
        private val server = MnnOpenAiServer(
            Mockito.mock(Context::class.java).also { Mockito.`when`(it.filesDir).thenReturn(directory) },
            runtime, { emptyMap() }, Mockito.mock(MnnLogStore::class.java),
        )

        init {
            Mockito.mockStatic(SystemClock::class.java).use { server.start(MnnBindMode.LOOPBACK, port, null) }
        }

        fun post(stream: Boolean = false): Pair<Int, String> {
            val connection = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write("""{"messages":[{"role":"user","content":"hello"}],"stream":$stream}""".toByteArray())
                }
                val status = connection.responseCode
                return status to (if (status >= 400) connection.errorStream else connection.inputStream)
                    .bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        override fun close() {
            server.stop()
            directory.deleteRecursively()
        }
    }
}
