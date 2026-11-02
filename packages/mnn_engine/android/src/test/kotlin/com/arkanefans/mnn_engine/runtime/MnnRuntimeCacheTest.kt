package com.arkanefans.mnn_engine.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MnnRuntimeCacheTest {
    @Test
    fun reportsAndClearsPerModelAndGlobalCaches() {
        val root = Files.createTempDirectory("mnn-runtime-cache").toFile()
        try {
            writeFile(File(root, "qwen/tmp/3.6.1/cpu/weight.bin"), 10)
            writeFile(File(root, "other/tmp/3.6.1/opencl/mnn_cachefile.bin"), 7)
            assertEquals(17, MnnRuntimeCache.sizeBytes(root))
            assertEquals(10, MnnRuntimeCache.sizeBytes(root, "qwen"))
            assertEquals(0, MnnRuntimeCache.sizeBytes(root, "missing"))

            assertEquals(10, MnnRuntimeCache.clear(root, "qwen"))
            assertFalse(File(root, "qwen").exists())
            assertEquals(7, MnnRuntimeCache.sizeBytes(root))
            assertTrue(File(root, "other/tmp/3.6.1/opencl/mnn_cachefile.bin").isFile)

            assertEquals(7, MnnRuntimeCache.clear(root))
            assertEquals(0, MnnRuntimeCache.sizeBytes(root))
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeFile(file: File, size: Int) {
        check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        file.writeBytes(ByteArray(size))
    }
}
