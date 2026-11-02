package com.arkanefans.mnn_engine.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MnnModelImporter(
    private val context: Context,
    private val directories: MnnTestDirectories,
    private val validator: MnnModelValidator,
    private val repository: MnnTestModelRepository,
    private val logStore: MnnLogStore,
) {
    fun import(treeUri: Uri, replaceExisting: Boolean, activeModelId: String?): MnnModelInfo =
        importWithResult(
            treeUri = treeUri,
            replaceExisting = replaceExisting,
            activeModelId = activeModelId,
            autoRename = false,
            unavailableNames = emptyList(),
        ).model

    fun importWithResult(
        treeUri: Uri,
        replaceExisting: Boolean,
        activeModelId: String?,
        autoRename: Boolean,
        unavailableNames: Collection<String>,
    ): MnnModelImportResult {
        directories.ensureCreated()
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Unable to open the selected directory.")
        require(source.isDirectory && source.canRead()) { "Selected directory is not readable." }

        val staging = File(directories.stagingDir, UUID.randomUUID().toString())
        check(staging.mkdirs()) { "Failed to create import staging directory." }
        logStore.info(TAG, "Importing ${source.name ?: treeUri} into ${staging.absolutePath}")
        try {
            copyDirectoryContents(source, staging)
            return commit(
                staging = staging,
                sourceName = source.name,
                replaceExisting = replaceExisting,
                activeModelId = activeModelId,
                autoRename = autoRename,
                unavailableNames = unavailableNames,
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            logStore.error(TAG, "Model import failed", error)
            throw error
        }
    }

    private fun copyDirectoryContents(source: DocumentFile, target: File) {
        source.listFiles().forEach { child ->
            val name = child.name ?: throw IllegalArgumentException("Selected directory contains an unnamed entry.")
            require(isSafeName(name)) { "Unsafe file name: $name" }
            val output = File(target, name)
            if (child.isDirectory) {
                check(output.mkdir()) { "Failed to create directory: $name" }
                copyDirectoryContents(child, output)
            } else if (child.isFile) {
                val input = context.contentResolver.openInputStream(child.uri)
                    ?: throw IllegalArgumentException("Unable to read: $name")
                input.use { sourceStream ->
                    FileOutputStream(output).use { targetStream ->
                        sourceStream.copyTo(targetStream, DEFAULT_BUFFER_SIZE)
                    }
                }
            }
        }
    }

    /// Imports a directory the app already owns (a completed download in the
    /// app's private storage), bypassing the SAF picker. Shares the staging,
    /// validation and commit path with [import] so both entry points produce
    /// identical on-disk layouts.
    fun importFromPath(sourceDir: File, replaceExisting: Boolean, activeModelId: String?): MnnModelInfo =
        importFromPathWithResult(
            sourceDir = sourceDir,
            replaceExisting = replaceExisting,
            activeModelId = activeModelId,
            autoRename = false,
            unavailableNames = emptyList(),
        ).model

    fun importFromPathWithResult(
        sourceDir: File,
        replaceExisting: Boolean,
        activeModelId: String?,
        autoRename: Boolean,
        unavailableNames: Collection<String>,
    ): MnnModelImportResult {
        directories.ensureCreated()
        require(sourceDir.isDirectory && sourceDir.canRead()) { "Source directory is not readable." }

        val staging = File(directories.stagingDir, UUID.randomUUID().toString())
        check(staging.mkdirs()) { "Failed to create import staging directory." }
        logStore.info(TAG, "Importing ${sourceDir.absolutePath} into ${staging.absolutePath}")
        try {
            copyLocalDirectoryContents(sourceDir, staging)
            return commit(
                staging = staging,
                sourceName = sourceDir.name,
                replaceExisting = replaceExisting,
                activeModelId = activeModelId,
                autoRename = autoRename,
                unavailableNames = unavailableNames,
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            logStore.error(TAG, "Model import failed", error)
            throw error
        }
    }

    private fun copyLocalDirectoryContents(source: File, target: File) {
        source.listFiles()?.forEach { child ->
            val name = child.name
            require(isSafeName(name)) { "Unsafe file name: $name" }
            val output = File(target, name)
            if (child.isDirectory) {
                check(output.mkdir()) { "Failed to create directory: $name" }
                copyLocalDirectoryContents(child, output)
            } else if (child.isFile) {
                child.copyTo(output, overwrite = true)
            }
        }
    }

    private fun commit(
        staging: File,
        sourceName: String?,
        replaceExisting: Boolean,
        activeModelId: String?,
        autoRename: Boolean,
        unavailableNames: Collection<String>,
    ): MnnModelImportResult {
        validator.validate(staging)
        val requestedModelName = resolveModelKey(staging, sourceName)
        val modelKey = if (autoRename && !replaceExisting) {
            MnnModelNameAllocator.nextAvailable(
                requestedModelName = requestedModelName,
                unavailableNames = buildList {
                    repository.list(activeModelId).forEach { add(it.modelId) }
                    directories.modelsDir.listFiles().orEmpty()
                        .filter(File::isDirectory)
                        .forEach { add(it.name) }
                    addAll(unavailableNames)
                },
            )
        } else {
            requestedModelName
        }
        val finalDir = directories.modelDir(modelKey)
        val existing = repository.list(activeModelId)
            .firstOrNull { it.modelId.equals(modelKey, ignoreCase = true) }
        if (existing != null) {
            require(replaceExisting) { "Model already exists: ${existing.modelId}" }
            require(existing.modelId != activeModelId) { "The active model cannot be replaced." }
            check(File(existing.modelDirPath).deleteRecursively()) { "Failed to replace existing model." }
        } else {
            require(!finalDir.exists()) { "Model already exists: $modelKey" }
        }
        if (!staging.renameTo(finalDir)) {
            check(staging.copyRecursively(finalDir, overwrite = true)) {
                "Failed to commit imported model."
            }
            check(staging.deleteRecursively()) { "Failed to clean import staging directory." }
        }
        finalDir.setLastModified(System.currentTimeMillis())
        val result = repository.modelInfo(finalDir, activeModelId)
            ?: throw IllegalStateException("Imported model could not be scanned.")
        logStore.info(TAG, "Imported ${result.modelId} (${result.sizeBytes} bytes)")
        return MnnModelImportResult(
            requestedModelName = requestedModelName,
            model = result,
        )
    }

    private fun resolveModelKey(staging: File, sourceName: String?): String {
        val metadataFile = File(staging, "market_config.json")
        val metadata = if (metadataFile.isFile) {
            runCatching { JsonParser.parseString(metadataFile.readText(Charsets.UTF_8)).asJsonObject }
                .getOrDefault(JsonObject())
        } else {
            JsonObject()
        }
        val displayName = listOf("modelName", "model_name", "name", "modelId", "model_id")
            .firstNotNullOfOrNull { key ->
                metadata.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: sourceName
            ?: "mnn-model-${UUID.randomUUID().toString().take(8)}"
        return normalizeModelName(displayName)
    }

    private fun normalizeModelName(value: String): String {
        val normalized = value.trim()
            .replace(Regex("[\\\\/\\u0000-\\u001F]"), "-")
            .take(80)
            .trim()
        val fallback = "mnn-model-${UUID.randomUUID().toString().take(8)}"
        return normalized.ifBlank { fallback }
            .let { name -> if (name == "." || name == "..") fallback else name }
    }

    private fun isSafeName(name: String): Boolean {
        return name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')
    }

    private companion object {
        const val TAG = "MnnModelImporter"
    }
}
