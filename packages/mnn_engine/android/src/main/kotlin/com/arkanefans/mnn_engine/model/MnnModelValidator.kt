package com.arkanefans.mnn_engine.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class MnnModelValidator {
    data class ValidationResult(
        val config: JsonObject,
        val warnings: List<String>,
        val supportsVision: Boolean,
        val supportsToolCalling: Boolean,
    )

    fun validate(modelDir: File): ValidationResult {
        require(modelDir.isDirectory) { "Model directory does not exist." }
        val configFile = File(modelDir, CONFIG_FILE)
        require(configFile.isFile && configFile.canRead() && configFile.length() > 0) {
            "config.json is missing, empty, or unreadable."
        }
        val root = runCatching {
            JsonParser.parseString(configFile.readText(Charsets.UTF_8)).asJsonObject
        }.getOrElse { error ->
            throw IllegalArgumentException("config.json is invalid: ${error.message}", error)
        }

        val llmModel = root.stringValue("llm_model")
        require(!llmModel.isNullOrBlank()) { "config.json must define llm_model." }

        val llmConfigPath = root.stringValue("llm_config") ?: DEFAULT_LLM_CONFIG_FILE
        val llmConfigFile = File(modelDir, llmConfigPath)
        val llmConfig = if (llmConfigFile.isFile) {
            runCatching { JsonParser.parseString(llmConfigFile.readText(Charsets.UTF_8)).asJsonObject }
                .getOrElse { error ->
                    throw IllegalArgumentException("$llmConfigPath is invalid: ${error.message}", error)
                }
        } else {
            JsonObject()
        }

        val warnings = mutableListOf<String>()
        val backend = root.stringValue("backend_type")
        if (!backend.isNullOrBlank() && backend.lowercase() != "cpu") {
            warnings += "backend_type '$backend' will be overridden to cpu."
        }
        if (!root.stringValue("audio_model").isNullOrBlank()) {
            warnings += "audio_model is present but the API does not expose audio input."
        }

        REFERENCED_FILE_KEYS.forEach { key ->
            root.stringValue(key)?.takeIf(String::isNotBlank)?.let { relativePath ->
                validateReferencedFile(modelDir, key, relativePath)
            }
        }
        val supportsVision = root.booleanValue("is_visual") == true ||
            llmConfig.booleanValue("is_visual") == true ||
            !root.stringValue("visual_model").isNullOrBlank()
        if (supportsVision) {
            val visualModel = root.stringValue("visual_model") ?: DEFAULT_VISUAL_MODEL_FILE
            validateReferencedFile(
                modelDir,
                "visual_model",
                visualModel,
            )
            val visualWeight = root.stringValue("visual_weight")
                ?: "$visualModel.weight"
            validateReferencedFile(modelDir, "visual_weight", visualWeight)
        }
        val chatTemplate = root.chatTemplate() ?: llmConfig.chatTemplate().orEmpty()
        val supportsToolCalling = chatTemplate.contains("tools") &&
            chatTemplate.contains("tool_call") && chatTemplate.contains("tool_response")
        return ValidationResult(root, warnings, supportsVision, supportsToolCalling)
    }

    private fun validateReferencedFile(modelDir: File, key: String, configuredPath: String) {
        val rawFile = File(configuredPath)
        require(!rawFile.isAbsolute) { "$key must use a relative path." }
        val canonicalRoot = modelDir.canonicalFile
        val referenced = File(canonicalRoot, configuredPath).canonicalFile
        val allowedPrefix = canonicalRoot.path + File.separator
        require(referenced.path.startsWith(allowedPrefix)) { "$key escapes the model directory." }
        require(referenced.isFile) { "$key references a missing file: $configuredPath" }
    }

    private fun JsonObject.stringValue(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            null
        }
    }

    private fun JsonObject.booleanValue(key: String): Boolean? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) element.asBoolean else null
    }

    private fun JsonObject.chatTemplate(): String? =
        getAsJsonObject("jinja")?.stringValue("chat_template")

    companion object {
        const val CONFIG_FILE = "config.json"
        private const val DEFAULT_LLM_CONFIG_FILE = "llm_config.json"
        private const val DEFAULT_VISUAL_MODEL_FILE = "visual.mnn"
        private val REFERENCED_FILE_KEYS = listOf(
            "llm_model",
            "llm_weight",
            "embedding_model",
            "embedding_file",
            "tokenizer_file",
            "llm_config",
            "visual_model",
            "visual_weight",
            "audio_model",
        )
    }
}
