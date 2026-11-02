package com.arkanefans.mnn_engine.model

import android.content.Context
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mockito.Mockito

class MnnTestModelRepositoryTest {
    @Test
    fun usesTheModelKeyAsTheDefaultModelId() {
        val filesDir = createTempDirectory("mnn-repository").toFile()
        try {
            val context = Mockito.mock(Context::class.java)
            Mockito.`when`(context.filesDir).thenReturn(filesDir)
            val directories = MnnTestDirectories(context)
            directories.ensureCreated()
            val modelDir = directories.modelDir("qwen3-5-0-8b-mnn").apply { mkdirs() }
            File(modelDir, "llm.mnn").writeBytes(byteArrayOf(1))
            File(modelDir, "config.json").writeText("""{"llm_model":"llm.mnn"}""")
            val repository = MnnTestModelRepository(directories, MnnModelValidator())

            val model = repository.find("qwen3-5-0-8b-mnn")

            assertNotNull(model)
            assertEquals("qwen3-5-0-8b-mnn", model.modelId)
            assertNull(repository.find("local/qwen3-5-0-8b-mnn"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun alwaysUsesTheDirectoryNameAsTheModelId() {
        val filesDir = createTempDirectory("mnn-repository").toFile()
        try {
            val repository = repository(filesDir)
            val modelDir = createModel(filesDir, "folder-name")
            File(modelDir, "market_config.json").writeText(
                """{"modelId":"local/metadata-id","modelName":"Metadata name"}""",
            )

            val model = repository.find("folder-name")

            assertNotNull(model)
            assertEquals("folder-name", model.modelId)
            assertEquals("folder-name", model.displayName)
            assertNull(repository.find("local/metadata-id"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun renamesTheModelDirectoryAndRuntimeId() {
        val filesDir = createTempDirectory("mnn-repository").toFile()
        try {
            val repository = repository(filesDir)
            createModel(filesDir, "before")

            val renamed = repository.rename("before", "After model", null)

            assertEquals("After model", renamed.modelId)
            assertEquals("After model", renamed.displayName)
            assertNull(repository.find("before"))
            assertNotNull(repository.find("After model"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsCaseInsensitiveDuplicateNames() {
        val filesDir = createTempDirectory("mnn-repository").toFile()
        try {
            val repository = repository(filesDir)
            createModel(filesDir, "alpha")
            createModel(filesDir, "beta")

            assertFailsWith<IllegalArgumentException> {
                repository.rename("beta", "ALPHA", null)
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun supportsCaseOnlyRenames() {
        val filesDir = createTempDirectory("mnn-repository").toFile()
        try {
            val repository = repository(filesDir)
            createModel(filesDir, "alpha")

            val renamed = repository.rename("alpha", "Alpha", null)

            assertEquals("Alpha", renamed.modelId)
            assertNull(repository.find("alpha"))
            assertNotNull(repository.find("Alpha"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun repository(filesDir: File): MnnTestModelRepository {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(filesDir)
        return MnnTestModelRepository(MnnTestDirectories(context), MnnModelValidator())
    }

    private fun createModel(filesDir: File, name: String): File {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.filesDir).thenReturn(filesDir)
        val directories = MnnTestDirectories(context)
        directories.ensureCreated()
        return directories.modelDir(name).apply {
            mkdirs()
            File(this, "llm.mnn").writeBytes(byteArrayOf(1))
            File(this, "config.json").writeText("""{"llm_model":"llm.mnn"}""")
        }
    }
}
