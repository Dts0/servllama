package com.arkanefans.mnn_engine.runtime

import com.google.gson.JsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MnnHexagonAssetsTest {
    private val architectures = listOf("v73", "v75", "v79", "v81")
    private val names = listOf("libMNN_htpops_skel.so", "libc++.so.1", "libc++abi.so.1")
    private val assets = architectures.flatMap { arch -> names.map { "$arch/$it" } }
        .associateWith { "DSP resource: $it".toByteArray() }

    private fun manifest(): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", 2)
        addProperty("stubAbiVersion", 1)
        addProperty("mnnCommit", "test-commit")
        add("architectures", JsonObject().apply {
            for (arch in architectures) add(arch, JsonObject().apply {
                add("files", JsonObject().apply {
                    for (name in names) add(name, JsonObject().apply {
                        val bytes = assets.getValue("$arch/$name")
                        addProperty("sizeBytes", bytes.size)
                        addProperty("sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) })
                    })
                })
            })
        })
    }

    private fun withStorage(block: (File) -> Unit) {
        val root = Files.createTempDirectory("mnn-hexagon-assets").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun extract(
        root: File,
        arch: String = "v79",
        info: JsonObject = manifest(),
        reads: MutableList<String> = mutableListOf(),
        read: (String) -> ByteArray = { assets.getValue(it) },
        write: (File, ByteArray) -> Unit = { file, bytes -> file.writeBytes(bytes) },
    ): File = MnnHexagonAssets.extract(
        info.toString().toByteArray(), "test-commit", arch, root,
        readAsset = { name -> reads.add(name); read(name) }, writeAsset = write,
    )

    @Test
    fun extractsOnlyTheExactDeviceArchitecture() = withStorage { root ->
        for (arch in architectures) {
            val reads = mutableListOf<String>()
            val output = extract(File(root, arch), arch, reads = reads)
            assertEquals(names.map { "$arch/$it" }.toSet(), reads.toSet())
            assertEquals(listOf(arch), output.parentFile.list()!!.toList())
            assertEquals(names.toSet(), output.list()!!.toSet())
            for (name in names) assertContentEquals(assets.getValue("$arch/$name"), File(output, name).readBytes())
        }
    }

    @Test
    fun reusesVerifiedFilesAndRepairsOnlyACorruptCachedFile() = withStorage { root ->
        val output = extract(root)
        val reads = mutableListOf<String>()
        assertEquals(output, extract(root, reads = reads))
        assertTrue(reads.isEmpty())
        File(output, names[1]).writeText("corrupt")
        extract(root, reads = reads)
        assertEquals(listOf("v79/${names[1]}"), reads)
        assertContentEquals(assets.getValue(reads.single()), File(output, names[1]).readBytes())
    }

    @Test
    fun neverGuessesAnArchitectureOrFallsBackToAnotherIsa() = withStorage { root ->
        for (arch in listOf("v68", "v74", "v85", "", "../v79")) {
            assertFailsWith<MnnHexagonAssets.ArchitectureUnavailable> { extract(root, arch) }
        }
        val single = manifest().apply {
            getAsJsonObject("architectures").keySet().retainAll(setOf("v73"))
        }
        val error = assertFailsWith<MnnHexagonAssets.ArchitectureUnavailable> { extract(root, info = single) }
        assertTrue(error.message!!.contains("v79"))
        assertTrue(error.message!!.contains("v73"))
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun rejectsOldManifestAbiAndDifferentMnnRevisionsBeforeReadingAssets() = withStorage { root ->
        for ((key, value) in listOf("schemaVersion" to 1, "stubAbiVersion" to 0)) {
            val info = manifest().apply { addProperty(key, value) }
            assertFailsWith<IllegalStateException> { extract(root, info = info, read = { error("Unexpected read") }) }
        }
        val info = manifest().apply { addProperty("mnnCommit", "another-commit") }
        assertFailsWith<IllegalStateException> { extract(root, info = info, read = { error("Unexpected read") }) }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun refusesUnknownDirectoriesAndIncompleteDependencySets() = withStorage { root ->
        for (arch in listOf("../v79", "v85")) {
            val info = manifest().apply {
                val entries = getAsJsonObject("architectures")
                entries.add(arch, entries.remove("v79"))
            }
            assertFailsWith<IllegalStateException> { extract(root, info = info) }
        }
        val info = manifest()
        val files = info.getAsJsonObject("architectures").getAsJsonObject("v79").getAsJsonObject("files")
        files.add("../outside.so", files.remove(names[0]))
        assertFailsWith<IllegalStateException> { extract(root, info = info) }
        files.remove("../outside.so")
        assertFailsWith<IllegalStateException> { extract(root, info = info) }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun doesNotInstallAnAssetWithADifferentChecksumOrSize() = withStorage { root ->
        assertFailsWith<IllegalStateException> { extract(root, read = { "bad bytes".toByteArray() }) }
        assertFalse(root.walkTopDown().any { it.isFile })
        val info = manifest().apply {
            getAsJsonObject("architectures").getAsJsonObject("v79").getAsJsonObject("files")
                .getAsJsonObject(names[0]).addProperty("sizeBytes", 1)
        }
        assertFailsWith<IllegalStateException> { extract(root, info = info) }
        assertFalse(root.walkTopDown().any { it.isFile })
    }

    @Test
    fun canRetryAfterAnInterruptedExtraction() = withStorage { root ->
        var writes = 0
        assertFailsWith<IOException> {
            extract(root, write = { file, bytes ->
                if (++writes == 2) throw IOException("disk unavailable")
                file.writeBytes(bytes)
            })
        }
        val reads = mutableListOf<String>()
        val output = extract(root, reads = reads)
        assertEquals(names.drop(1).map { "v79/$it" }.toSet(), reads.toSet())
        assertEquals(names.toSet(), output.list()!!.toSet())
    }

    @Test
    fun aNewBundleUsesANewDirectoryWithoutReusingOldLibraries() = withStorage { root ->
        val previous = extract(root)
        val changed = manifest().apply { addProperty("buildLabel", "next-bundle") }
        val reads = mutableListOf<String>()
        val current = extract(root, info = changed, reads = reads)
        assertNotEquals(previous, current)
        assertEquals(3, reads.size)
        assertEquals(names.toSet(), current.list()!!.toSet())
    }
}
