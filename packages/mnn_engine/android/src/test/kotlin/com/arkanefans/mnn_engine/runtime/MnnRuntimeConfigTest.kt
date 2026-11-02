package com.arkanefans.mnn_engine.runtime

import com.arkanefans.mnn_engine.MnnEngineOperationException
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MnnRuntimeConfigTest {
    @Test
    fun defaultIsCpuAndNpuDoesNotAliasHexagon() {
        assertEquals(MnnBackend.CPU, MnnLoadOptions.fromMap(null).backend)
        assertEquals(false, MnnLoadOptions.fromMap(null).useMmap)
        assertEquals(MnnPrecision.LOW, MnnLoadOptions.fromMap(null).precision)
        assertEquals(4, MnnLoadOptions.fromMap(null).threadNum)
        for (backend in MnnBackend.entries) {
            assertEquals(backend, MnnLoadOptions.fromMap(mapOf("backend" to backend.wireName)).backend)
        }
        for (invalid in listOf("npu", "auto", "OpenCL", "", 3)) {
            val error = assertFailsWith<MnnEngineOperationException> {
                MnnLoadOptions.fromMap(mapOf("backend" to invalid))
            }
            assertEquals("invalid_backend", error.code)
        }
    }

    @Test
    fun loadOptionsParseMmapPrecisionAndThreadCount() {
        val parsed = MnnLoadOptions.fromMap(
            mapOf(
                "backend" to "opencl",
                "useMmap" to true,
                "precision" to "high",
                "threadNum" to 6,
            ),
        )
        assertEquals(MnnBackend.OPENCL, parsed.backend)
        assertTrue(parsed.useMmap)
        assertEquals(MnnPrecision.HIGH, parsed.precision)
        assertEquals(6, parsed.threadNum)

        for (invalid in listOf("normal", "fp16", "", 2)) {
            val error = assertFailsWith<MnnEngineOperationException> {
                MnnLoadOptions.fromMap(mapOf("precision" to invalid))
            }
            assertEquals("invalid_argument", error.code)
        }
        for (invalid in listOf(0, 9, "8")) {
            val error = assertFailsWith<MnnEngineOperationException> {
                MnnLoadOptions.fromMap(mapOf("threadNum" to invalid))
            }
            assertEquals("invalid_argument", error.code)
        }
    }

    @Test
    fun backendOverridePreservesModelConfigurationAndSeparatesCaches() {
        val directory = Files.createTempDirectory("mnn-runtime-config").toFile()
        try {
            val source = JsonParser.parseString("""{
                "backend_type":"cpu", "thread_num":3, "use_mmap":true,
                "temperature":0.6, "precision":"low", "mllm":{"backend_type":"cpu"}
            }""").asJsonObject
            val original = source.deepCopy()
            val cpu = MnnRuntimeConfig.create(source, MnnLoadOptions(), directory, "3.6.1")
            val gpu = MnnRuntimeConfig.create(source, MnnLoadOptions(MnnBackend.OPENCL), directory, "3.6.1")
            val nextVersion = MnnRuntimeConfig.create(source, MnnLoadOptions(MnnBackend.OPENCL), directory, "3.6.2")
            assertEquals(original, source)
            assertEquals("opencl", gpu.get("backend_type").asString)
            assertFalse(gpu.get("use_mmap").asBoolean)
            assertEquals("low", gpu.get("precision").asString)
            assertEquals(4, gpu.get("thread_num").asInt)
            assertEquals(0.6, gpu.get("temperature").asDouble)
            assertEquals(original.get("mllm"), gpu.get("mllm"))
            assertNotEquals(cpu.get("tmp_path"), gpu.get("tmp_path"))
            assertNotEquals(gpu.get("tmp_path"), nextVersion.get("tmp_path"))
            assertTrue(File(gpu.get("tmp_path").asString).isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun appliesMmapPrecisionAndThreadOverrides() {
        val directory = Files.createTempDirectory("mnn-runtime-options").toFile()
        try {
            val source = JsonParser.parseString("""{
                "backend_type":"cpu", "thread_num":1, "use_mmap":false, "precision":"low"
            }""").asJsonObject
            val options = MnnLoadOptions(
                backend = MnnBackend.VULKAN,
                useMmap = true,
                precision = MnnPrecision.HIGH,
                threadNum = 7,
            )
            val config = MnnRuntimeConfig.create(source, options, directory, "3.6.1")
            assertEquals("vulkan", config.get("backend_type").asString)
            assertTrue(config.get("use_mmap").asBoolean)
            assertEquals("high", config.get("precision").asString)
            assertEquals(7, config.get("thread_num").asInt)
            assertTrue(File(config.get("tmp_path").asString).isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun backendsDoNotInjectDebugConfigurationAndHonorExplicitValues() {
        val directory = Files.createTempDirectory("mnn-runtime-diagnostics").toFile()
        try {
            val source = JsonParser.parseString("{}").asJsonObject
            for (backend in MnnBackend.entries) {
                assertFalse(MnnRuntimeConfig.create(source, MnnLoadOptions(backend), directory, "3.6.1")
                    .has("enable_debug"))
                for (enabled in listOf(false, true)) {
                    val configured = source.deepCopy().apply { addProperty("enable_debug", enabled) }
                    assertEquals(enabled, MnnRuntimeConfig.create(configured, MnnLoadOptions(backend), directory, "3.6.1")
                        .get("enable_debug").asBoolean)
                }
            }
            assertFalse(source.has("enable_debug"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingThreadsUseTheDefaultThreadCount() {
        val directory = Files.createTempDirectory("mnn-runtime-threads").toFile()
        try {
            val source = JsonParser.parseString("{}").asJsonObject
            assertEquals(4, MnnRuntimeConfig.create(source, MnnLoadOptions(), directory, "3.6.1")
                .get("thread_num").asInt)
            assertEquals(8, MnnRuntimeConfig.create(
                source,
                MnnLoadOptions(threadNum = 8),
                directory,
                "3.6.1",
            ).get("thread_num").asInt)
        } finally {
            directory.deleteRecursively()
        }
    }
}
