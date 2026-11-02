package com.arkanefans.mnn_engine.server

import android.content.Context
import android.os.SystemClock
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import org.mockito.Mockito
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MnnOpenAiServerStopTest {
    @Test
    fun stopRejectsNewRequestsAndRequestsThatHaveOnlyFinishedParsing() {
        val directory = Files.createTempDirectory("mnn-server-stop").toFile()
        val parsed = CountDownLatch(1)
        val finishParsing = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val finishStopping = CountDownLatch(1)
        val generationCalls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val model = MnnModelInfo(
            modelId = "qwen", modelKey = "qwen", displayName = "Qwen", vendor = null,
            modelDirPath = "unused", configPath = "unused/config.json", sizeBytes = 0,
            importedAt = 0, isActive = true, backend = "cpu",
        )
        val runtime = Mockito.mock(MnnRuntimeManager::class.java) { invocation ->
            when (invocation.method.name) {
                "activeModel" -> {
                    parsed.countDown()
                    check(finishParsing.await(10, TimeUnit.SECONDS))
                    model
                }
                "cancelGeneration" -> {
                    stopping.countDown()
                    check(finishStopping.await(10, TimeUnit.SECONDS))
                    null
                }
                "generate" -> {
                    generationCalls.incrementAndGet()
                    error("A stopped request must not reach inference")
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val server = MnnOpenAiServer(context, runtime, { emptyMap() }, Mockito.mock(MnnLogStore::class.java))
        val port = ServerSocket(0).use { it.localPort }
        try {
            Mockito.mockStatic(SystemClock::class.java).use {
                server.start(MnnBindMode.LOOPBACK, port, null)
            }
            val pending = executor.submit<Pair<Int, String>> { post(port, stream = false) }
            assertTrue(parsed.await(5, TimeUnit.SECONDS))
            val stop = executor.submit { server.stop() }
            assertTrue(stopping.await(5, TimeUnit.SECONDS))

            // stop() is deliberately blocked inside cancellation, before CIO's
            // graceful shutdown. HTTP remains reachable, but admission is closed.
            val newRequest = post(port, stream = true)
            assertEquals(503, newRequest.first)
            assertTrue(newRequest.second.contains("\"code\":\"server_stopping\""))

            finishParsing.countDown()
            val oldRequest = pending.get(5, TimeUnit.SECONDS)
            assertEquals(503, oldRequest.first)
            assertTrue(oldRequest.second.contains("\"code\":\"server_stopping\""))
            assertEquals(0, generationCalls.get())

            finishStopping.countDown()
            stop.get(10, TimeUnit.SECONDS)
        } finally {
            finishParsing.countDown()
            finishStopping.countDown()
            server.stop()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    private fun post(port: Int, stream: Boolean): Pair<Int, String> {
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
            val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                .bufferedReader().use { it.readText() }
            return status to body
        } finally {
            connection.disconnect()
        }
    }
}
