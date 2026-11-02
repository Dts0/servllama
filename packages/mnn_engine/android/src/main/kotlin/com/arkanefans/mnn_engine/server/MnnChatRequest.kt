package com.arkanefans.mnn_engine.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal enum class MnnToolChoiceMode {
    NONE,
    AUTO,
    REQUIRED,
    NAMED,
}

internal data class MnnToolChoice(
    val mode: MnnToolChoiceMode,
    val name: String? = null,
)

internal data class MnnChatRequest(
    val model: String?,
    val messages: List<JsonObject>,
    val stream: Boolean,
    val temperature: Double?,
    val topP: Double?,
    val maxTokens: Int,
    val tools: JsonArray,
    val toolChoice: MnnToolChoice,
    val parallelToolCalls: Boolean,
) {
    val hasTools: Boolean
        get() = tools.size() > 0 && toolChoice.mode != MnnToolChoiceMode.NONE

    fun effectiveTools(): JsonArray {
        if (!hasTools) return JsonArray()
        if (toolChoice.mode != MnnToolChoiceMode.NAMED) return tools.deepCopy()
        return JsonArray().also { selected ->
            tools.forEach { tool ->
                val name = tool.asJsonObject.getAsJsonObject("function").get("name")?.asString
                if (name == toolChoice.name) selected.add(tool.deepCopy())
            }
        }
    }

    fun withToolPolicy(messages: List<JsonObject>): List<JsonObject> {
        if (!hasTools || toolChoice.mode == MnnToolChoiceMode.AUTO) return messages
        val policy = when (toolChoice.mode) {
            MnnToolChoiceMode.REQUIRED ->
                "You must call one of the available functions to answer this request."
            MnnToolChoiceMode.NAMED ->
                "You must call the ${toolChoice.name} function to answer this request."
            else -> return messages
        }
        val result = messages.map { it.deepCopy() }.toMutableList()
        val first = result.firstOrNull()
        if (first?.get("role")?.asString == "system") {
            val content = first.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            first.addProperty("content", if (content.isBlank()) policy else "$content\n\n$policy")
        } else {
            result.add(0, JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", policy)
            })
        }
        return result
    }
}

internal object MnnChatRequestParser {
    const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
    private const val MAX_TOOLS = 32
    private const val MAX_TOOLS_JSON_BYTES = 128 * 1024
    private const val NO_TOKEN_LIMIT = -1
    private val allowedRoles = setOf("system", "user", "assistant", "tool")
    private val unsupportedFields = setOf(
        "response_format",
        "logprobs",
        "top_logprobs",
    )

    fun parse(body: String): MnnChatRequest {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
            "Request body exceeds 16 MiB."
        }
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw IllegalArgumentException("Request body must be a JSON object.") }
        val messagesElement = root.get("messages")
        require(messagesElement != null && messagesElement.isJsonArray && messagesElement.asJsonArray.size() > 0) {
            "messages must be a non-empty array."
        }
        val messages = messagesElement.asJsonArray.map { parseMessage(it) }
        val tools = parseTools(root.get("tools"))
        val toolChoice = parseToolChoice(root.get("tool_choice"), tools)
        val parallel = root.bool("parallel_tool_calls") ?: false
        val temperature = root.double("temperature")
        val topP = root.double("top_p")
        val maxTokens = root.int("n_predict")
            ?: root.int("max_completion_tokens")
            ?: root.int("max_tokens")
            ?: NO_TOKEN_LIMIT
        require(temperature == null || temperature in 0.0..2.0) {
            "temperature must be between 0 and 2."
        }
        require(topP == null || topP in 0.0..1.0) { "top_p must be between 0 and 1." }
        require(maxTokens >= NO_TOKEN_LIMIT) { "max_tokens must be -1 or greater." }
        val frequencyPenalty = root.double("frequency_penalty") ?: 0.0
        val presencePenalty = root.double("presence_penalty") ?: 0.0
        require(frequencyPenalty == 0.0 && presencePenalty == 0.0) {
            "frequency_penalty and presence_penalty are not supported in phase 2."
        }
        val n = root.int("n") ?: 1
        require(n == 1) { "n must be 1 in phase 2." }
        unsupportedFields.forEach { key ->
            val value = root.get(key)
            require(value == null || value.isJsonNull) { "$key is not supported in phase 2." }
        }
        return MnnChatRequest(
            model = root.string("model"),
            messages = messages,
            stream = root.bool("stream") ?: false,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            tools = tools,
            toolChoice = toolChoice,
            parallelToolCalls = parallel,
        )
    }

    private fun parseMessage(element: JsonElement): JsonObject {
        require(element.isJsonObject) { "Each message must be an object." }
        val item = element.asJsonObject.deepCopy()
        val role = item.string("role") ?: throw IllegalArgumentException("Message role is required.")
        require(role in allowedRoles) { "Unsupported message role: $role" }
        val hasContent = item.has("content")
        val toolCalls = item.get("tool_calls")
        if (role == "assistant") {
            require(hasContent || toolCalls != null) {
                "Assistant message must contain either content or tool_calls."
            }
        } else {
            require(hasContent) { "All non-assistant messages must contain content." }
        }
        if (hasContent) validateContent(item.get("content"))
        when (role) {
            "tool" -> {
                item.optionalString("tool_call_id")?.let {
                    require(it.isNotBlank()) { "Tool message tool_call_id must not be empty." }
                }
                item.optionalString("name")
            }
            "assistant" -> {
                if (toolCalls != null) validateToolCalls(toolCalls)
                item.optionalString("reasoning_content")
            }
        }
        return item
    }

    private fun validateContent(element: JsonElement) {
        if (element.isJsonNull) return
        if (element.isJsonPrimitive) {
            require(element.asJsonPrimitive.isString) { "Message content must be a string or content array." }
            return
        }
        require(element.isJsonArray) { "Message content must be a string or content array." }
        element.asJsonArray.forEach { part ->
            require(part.isJsonObject) { "Each content part must be an object." }
            val type = part.asJsonObject.string("type")
            when (type) {
                "text" -> require(part.asJsonObject.string("text") != null) {
                    "Text content part requires text."
                }
                "image_url" -> validateImagePart(part.asJsonObject)
                else -> throw IllegalArgumentException("Unsupported content part type: $type")
            }
        }
    }

    private fun validateImagePart(part: JsonObject) {
        val imageUrl = part.getAsJsonObject("image_url")
            ?: throw IllegalArgumentException("image_url content part requires image_url object.")
        val url = imageUrl.string("url") ?: throw IllegalArgumentException("image_url.url is required.")
        require(url.startsWith("data:image/jpeg;base64,", ignoreCase = true) ||
            url.startsWith("data:image/png;base64,", ignoreCase = true)) {
            "Only JPEG/PNG Base64 data image URLs are supported."
        }
        imageUrl.string("detail")?.let {
            require(it in setOf("auto", "low", "high")) { "image_url.detail must be auto, low, or high." }
        }
    }

    private fun validateToolCalls(element: JsonElement) {
        require(element.isJsonArray) { "assistant.tool_calls must be an array." }
        element.asJsonArray.forEach { call ->
            require(call.isJsonObject) { "Each tool call must be an object." }
            val item = call.asJsonObject
            item.optionalString("id")?.let {
                require(it.isNotBlank()) { "tool_call id must not be empty." }
            }
            require(item.string("type") == "function") { "Only function tool calls are supported." }
            val function = item.getAsJsonObject("function")
                ?: throw IllegalArgumentException("tool_call.function is required.")
            validateFunctionName(function.string("name"))
            val arguments = function.get("arguments")
                ?: throw IllegalArgumentException("tool_call function arguments are required.")
            require(arguments.isJsonObject || arguments.isJsonPrimitive && arguments.asJsonPrimitive.isString) {
                "tool_call function arguments must be a JSON object or a string containing one."
            }
            if (arguments.isJsonPrimitive) {
                val parsed = runCatching { JsonParser.parseString(arguments.asString) }.getOrNull()
                require(parsed?.isJsonObject == true) {
                    "tool_call function arguments string must contain a JSON object."
                }
            }
        }
    }

    private fun parseTools(element: JsonElement?): JsonArray {
        if (element == null || element.isJsonNull) return JsonArray()
        require(element.isJsonArray) { "tools must be an array." }
        require(element.asJsonArray.size() <= MAX_TOOLS) { "tools must contain at most $MAX_TOOLS items." }
        val tools = element.asJsonArray.deepCopy()
        val names = mutableSetOf<String>()
        tools.forEach { tool ->
            require(tool.isJsonObject) { "Each tool must be an object." }
            val item = tool.asJsonObject
            require(item.string("type") == "function") { "Only function tools are supported." }
            val function = item.getAsJsonObject("function")
                ?: throw IllegalArgumentException("function tool requires function object.")
            val name = function.string("name")
            validateFunctionName(name)
            require(names.add(name!!)) { "Duplicate tool function name: $name" }
            val parameters = function.get("parameters")
            require(parameters == null || parameters.isJsonObject) {
                "function.parameters must be a JSON object."
            }
        }
        require(tools.toString().toByteArray(Charsets.UTF_8).size <= MAX_TOOLS_JSON_BYTES) {
            "tools definition exceeds 128 KiB."
        }
        return tools
    }

    private fun parseToolChoice(element: JsonElement?, tools: JsonArray): MnnToolChoice {
        if (element == null || element.isJsonNull) {
            return MnnToolChoice(if (tools.size() > 0) MnnToolChoiceMode.AUTO else MnnToolChoiceMode.NONE)
        }
        if (element.isJsonPrimitive) {
            require(element.asJsonPrimitive.isString) {
                "tool_choice must be none, auto, required, or a function object."
            }
            return when (element.asString) {
                "none" -> MnnToolChoice(MnnToolChoiceMode.NONE)
                "auto" -> MnnToolChoice(MnnToolChoiceMode.AUTO)
                "required" -> {
                    require(tools.size() > 0) { "tool_choice required needs at least one tool." }
                    MnnToolChoice(MnnToolChoiceMode.REQUIRED)
                }
                else -> throw IllegalArgumentException("Unsupported tool_choice: ${element.asString}")
            }
        }
        require(element.isJsonObject) { "tool_choice must be none, auto, required, or a function object." }
        val choice = element.asJsonObject
        require(choice.string("type") == "function") { "Only function tool_choice is supported." }
        val name = choice.getAsJsonObject("function")?.string("name")
        validateFunctionName(name)
        require(tools.any { it.asJsonObject.getAsJsonObject("function").string("name") == name }) {
            "tool_choice function is not present in tools."
        }
        return MnnToolChoice(MnnToolChoiceMode.NAMED, name)
    }

    private fun validateFunctionName(name: String?) {
        require(name != null && Regex("[A-Za-z0-9_.-]{1,64}").matches(name)) {
            "Function name must match [A-Za-z0-9_.-]{1,64}."
        }
    }

    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string." }
        return value.asString
    }

    private fun JsonObject.optionalString(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string." }
        return value.asString
    }

    private fun JsonObject.double(key: String): Double? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be a number." }
        return value.asDouble
    }

    private fun JsonObject.int(key: String): Int? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be an integer." }
        return value.asString.toIntOrNull() ?: throw IllegalArgumentException("$key must be an integer.")
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$key must be a boolean." }
        return value.asBoolean
    }
}
