package com.arkanefans.mnn_engine.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class MnnTestModelRepository(
    private val directories: MnnTestDirectories,
    private val validator: MnnModelValidator,
) {
    fun list(activeModelId: String? = null): List<MnnModelInfo> {
        directories.ensureCreated()
        return directories.modelsDir.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { dir -> modelInfo(dir, activeModelId) }
            .sortedBy { it.displayName.lowercase() }
    }

    fun find(modelId: String, activeModelId: String? = null): MnnModelInfo? {
        return list(activeModelId).firstOrNull { it.modelId == modelId }
    }

    fun delete(modelId: String, activeModelId: String?) {
        require(modelId != activeModelId) { "The active model cannot be deleted." }
        val model = find(modelId, activeModelId) ?: throw IllegalArgumentException("Model not found: $modelId")
        val modelsRoot = directories.modelsDir.canonicalFile
        val target = File(model.modelDirPath).canonicalFile
        require(target.parentFile == modelsRoot) { "Model path is outside the test model directory." }
        check(target.deleteRecursively()) { "Failed to delete ${target.absolutePath}" }
        directories.modelRuntimeDir(model.modelKey).takeIf(File::exists)?.deleteRecursively()
    }

    fun rename(modelId: String, newName: String, activeModelId: String?): MnnModelInfo {
        require(modelId != activeModelId) { "The active model cannot be renamed." }
        val model = find(modelId, activeModelId)
            ?: throw IllegalArgumentException("Model not found: $modelId")
        val trimmedName = newName.trim()
        validateModelName(trimmedName)
        if (trimmedName == model.modelKey) return model

        val duplicate = list(activeModelId).any { candidate ->
            !candidate.modelId.equals(modelId, ignoreCase = true) &&
                candidate.modelId.equals(trimmedName, ignoreCase = true)
        }
        require(!duplicate) { "Model name already exists: $trimmedName" }

        val modelsRoot = directories.modelsDir.canonicalFile
        val source = File(model.modelDirPath).canonicalFile
        val target = File(modelsRoot, trimmedName)
        require(source.parentFile == modelsRoot) { "Model path is outside the model directory." }
        val caseOnlyRename = source.path.equals(target.path, ignoreCase = true)
        require(!target.exists() || caseOnlyRename) { "Model name already exists: $trimmedName" }
        if (caseOnlyRename) {
            val intermediate = File(modelsRoot, ".rename-${java.util.UUID.randomUUID()}")
            check(source.renameTo(intermediate)) { "Failed to rename model directory." }
            if (!intermediate.renameTo(target)) {
                intermediate.renameTo(source)
                error("Failed to rename model directory.")
            }
        } else {
            check(source.renameTo(target)) { "Failed to rename model directory." }
        }

        // Runtime files are disposable and keyed by the model directory name.
        directories.modelRuntimeDir(model.modelKey).takeIf(File::exists)?.deleteRecursively()
        return modelInfo(target, activeModelId)
            ?: throw IllegalStateException("Renamed model could not be scanned.")
    }

    fun modelInfo(dir: File, activeModelId: String? = null): MnnModelInfo? {
        if (!dir.isDirectory || !File(dir, MnnModelValidator.CONFIG_FILE).isFile) return null
        val metadata = readMarketMetadata(dir)
        val modelKey = dir.name
        val validationResult = runCatching { validator.validate(dir) }
        val validation = validationResult.getOrNull()
        val warnings = validation?.warnings
            ?: listOf(validationResult.exceptionOrNull()?.message ?: "Model validation failed.")
        return MnnModelInfo(
            modelId = modelKey,
            modelKey = modelKey,
            displayName = modelKey,
            vendor = metadata.string("vendor"),
            modelDirPath = dir.absolutePath,
            configPath = File(dir, MnnModelValidator.CONFIG_FILE).absolutePath,
            sizeBytes = directorySize(dir),
            importedAt = dir.lastModified(),
            isActive = modelKey == activeModelId,
            supportsVision = validation?.supportsVision == true,
            supportsToolCalling = validation?.supportsToolCalling == true,
            validationWarnings = warnings,
        )
    }

    private fun readMarketMetadata(dir: File): JsonObject {
        val file = File(dir, "market_config.json")
        if (!file.isFile) return JsonObject()
        return runCatching { JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject }
            .getOrDefault(JsonObject())
    }

    private fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            val value = get(key) ?: continue
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString
        }
        return null
    }

    private fun directorySize(root: File): Long {
        return root.walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    private fun validateModelName(modelName: String) {
        require(modelName.isNotEmpty()) { "Model name must not be empty." }
        require(modelName != "." && modelName != "..") { "Invalid model name." }
        require(modelName.none { it == '/' || it == '\\' || it.code in 0..31 }) {
            "Invalid model name."
        }
    }
}
