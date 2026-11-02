package com.arkanefans.mnn_engine.runtime

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/** DSP binaries are assets, not Android AArch64 shared libraries. */
internal object MnnHexagonAssets {
    private const val ASSET_ROOT = "mnn/hexagon"
    private val supportedArchitectures = setOf("v73", "v75", "v79", "v81")
    private val requiredFiles = setOf("libMNN_htpops_skel.so", "libc++.so.1", "libc++abi.so.1")
    private val hashPattern = Regex("[0-9a-f]{64}")

    class ArchitectureUnavailable(architecture: String, packaged: Set<String>) : IllegalStateException(
        "The device uses Hexagon $architecture; this package contains ${packaged.sorted().joinToString()}.",
    )

    fun isPackaged(context: Context): Boolean =
        context.assets.list(ASSET_ROOT)?.contains("manifest.json") == true

    fun prepare(context: Context, mnnCommit: String, architecture: String): File {
        val manifestBytes = context.assets.open("$ASSET_ROOT/manifest.json").use { it.readBytes() }
        val stub = File(context.applicationInfo.nativeLibraryDir, "libMNN_htpops.so")
        // AGP may strip Android ELF sections. Gradle/APK verification checks
        // stub identity; the native probe checks its exports and device driver.
        check(stub.isFile) { "Hexagon Android stub is missing." }
        return extract(
            manifestBytes, mnnCommit, architecture, File(context.noBackupFilesDir, "mnn/hexagon"),
            readAsset = { name -> context.assets.open("$ASSET_ROOT/$name").use { it.readBytes() } },
            writeAsset = ::writeAtomic,
        )
    }

    internal fun extract(
        manifestBytes: ByteArray,
        mnnCommit: String,
        architecture: String,
        storageRoot: File,
        readAsset: (String) -> ByteArray,
        writeAsset: (File, ByteArray) -> Unit,
    ): File {
        val manifest = JsonParser.parseString(manifestBytes.toString(Charsets.UTF_8)).asJsonObject
        check(manifest.get("schemaVersion").asInt == 2 && manifest.get("stubAbiVersion").asInt == 1) {
            "Unsupported Hexagon runtime manifest. Rebuild the native libraries and DSP assets."
        }
        check(manifest.get("mnnCommit").asString == mnnCommit) { "Hexagon assets use a different MNN revision." }
        val architectures = manifest.getAsJsonObject("architectures")
        check(architectures.size() > 0 && supportedArchitectures.containsAll(architectures.keySet())) {
            "Unsupported architecture in the Hexagon asset manifest."
        }
        for (entry in architectures.entrySet()) {
            val files = entry.value.asJsonObject.getAsJsonObject("files")
            check(files.keySet() == requiredFiles) { "Hexagon DSP runtime assets are incomplete: ${entry.key}" }
            for (file in files.entrySet()) {
                val info = file.value.asJsonObject
                check(hashPattern.matches(info.get("sha256").asString) && info.get("sizeBytes").asLong > 0) {
                    "Invalid Hexagon asset metadata: ${entry.key}/${file.key}"
                }
            }
        }
        if (!architectures.has(architecture)) throw ArchitectureUnavailable(architecture, architectures.keySet())
        val files = architectures.getAsJsonObject(architecture).getAsJsonObject("files")
        val destination = File(storageRoot, "${sha256(manifestBytes)}/$architecture")
        check(destination.isDirectory || destination.mkdirs()) { "Could not create the Hexagon runtime directory." }
        for (name in requiredFiles) {
            val info = files.getAsJsonObject(name)
            val expectedHash = info.get("sha256").asString
            val expectedSize = info.get("sizeBytes").asLong
            val output = File(destination, name)
            if (output.isFile && output.length() == expectedSize && sha256(output.readBytes()) == expectedHash) continue
            val bytes = readAsset("$architecture/$name")
            check(bytes.size.toLong() == expectedSize && sha256(bytes) == expectedHash) {
                "Hexagon asset checksum failed: $architecture/$name"
            }
            writeAsset(output, bytes)
        }
        return destination
    }

    private fun writeAtomic(output: File, bytes: ByteArray) {
        val atomic = AtomicFile(output)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
