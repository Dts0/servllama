package com.arkanefans.mnn_engine.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

internal data class MnnToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

internal data class MnnParsedCompletion(
    val content: String?,
    val toolCalls: List<MnnToolCall>,
    val reasoningContent: String? = null,
    val diagnostic: String? = null,
)

internal object MnnToolCallParser {
    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"
    private const val FUNCTION_OPEN = "<function="
    private const val FUNCTION_CLOSE = "</function>"
    private const val PARAMETER_OPEN = "<parameter="
    private const val PARAMETER_CLOSE = "</parameter>"
    private const val MAX_TOOL_BLOCK_BYTES = 64 * 1024
    private const val MAX_PARAMETERS = 64
    private val namePattern = Regex("[A-Za-z0-9_.-]{1,64}")

    fun parse(
        raw: String,
        allowedNames: Set<String>,
        parallel: Boolean,
    ): MnnParsedCompletion {
        val first = raw.indexOf(OPEN)
        if (first < 0) return MnnParsedCompletion(raw.ifBlank { null }, emptyList())
        val content = StringBuilder(raw.substring(0, first).trim())
        val calls = mutableListOf<MnnToolCall>()
        var cursor = first
        while (cursor >= 0 && cursor < raw.length) {
            val blockStart = raw.indexOf(OPEN, cursor)
            if (blockStart < 0) break
            val blockEnd = raw.indexOf(CLOSE, blockStart + OPEN.length)
            if (blockEnd < 0) return malformed(raw, "unterminated_tool_call")
            if (blockEnd - blockStart > MAX_TOOL_BLOCK_BYTES) {
                return malformed(raw, "tool_call_too_large")
            }
            val call = parseBlock(raw.substring(blockStart + OPEN.length, blockEnd), allowedNames)
                ?: return malformed(raw, "malformed_tool_call")
            calls += call
            cursor = blockEnd + CLOSE.length
            val next = raw.indexOf(OPEN, cursor)
            val between = raw.substring(cursor, if (next >= 0) next else raw.length)
            if (between.isNotBlank()) {
                if (content.isNotEmpty()) content.append('\n')
                content.append(between.trim())
            }
            cursor = next
        }
        if (calls.isEmpty()) return malformed(raw, "empty_tool_call")
        val published = if (parallel) calls else calls.take(1)
        val diagnostic = if (!parallel && calls.size > 1) "tool_call_count_clamped" else null
        return MnnParsedCompletion(
            content = content.toString().trim().ifBlank { null },
            toolCalls = published,
            diagnostic = diagnostic,
        )
    }

    private fun parseBlock(block: String, allowedNames: Set<String>): MnnToolCall? =
        parseJsonBlock(block, allowedNames) ?: parseTaggedBlock(block, allowedNames)

    private fun parseJsonBlock(block: String, allowedNames: Set<String>): MnnToolCall? {
        val root = runCatching { JsonParser.parseString(block.trim()).asJsonObject }.getOrNull()
            ?: return null
        val function = root.getAsJsonObject("function") ?: root
        val name = function.get("name")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?: return null
        if (!namePattern.matches(name) || name !in allowedNames) return null
        val arguments = normalizeArguments(function.get("arguments") ?: JsonObject()) ?: return null
        val id = root.get("id")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf { it.isNotBlank() }
        return MnnToolCall(
            id = id ?: newCallId(),
            name = name,
            arguments = arguments.toString(),
        )
    }

    private fun parseTaggedBlock(block: String, allowedNames: Set<String>): MnnToolCall? {
        val functionStart = block.indexOf(FUNCTION_OPEN)
        if (functionStart < 0) return null
        val nameStart = functionStart + FUNCTION_OPEN.length
        val nameEnd = block.indexOf('>', nameStart)
        if (nameEnd <= nameStart) return null
        val name = block.substring(nameStart, nameEnd)
        if (!namePattern.matches(name) || name !in allowedNames) return null
        val functionEnd = block.lastIndexOf(FUNCTION_CLOSE)
        if (functionEnd < nameEnd) return null
        val body = block.substring(nameEnd + 1, functionEnd).trim()
        if (body.isEmpty()) {
            return MnnToolCall(newCallId(), name, JsonObject().toString())
        }
        runCatching { JsonParser.parseString(body) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { arguments ->
                return MnnToolCall(newCallId(), name, arguments.toString())
            }
        val arguments = JsonObject()
        var cursor = 0
        var parameterCount = 0
        while (cursor < body.length) {
            while (cursor < body.length && body[cursor].isWhitespace()) cursor++
            if (cursor >= body.length) break
            if (!body.startsWith(PARAMETER_OPEN, cursor)) return null
            if (++parameterCount > MAX_PARAMETERS) return null
            val parameterStart = cursor + PARAMETER_OPEN.length
            val parameterEnd = body.indexOf('>', parameterStart)
            if (parameterEnd <= parameterStart) return null
            val parameterName = body.substring(parameterStart, parameterEnd)
            if (!namePattern.matches(parameterName)) return null
            val valueEnd = body.indexOf(PARAMETER_CLOSE, parameterEnd + 1)
            if (valueEnd < 0) return null
            val value = body.substring(parameterEnd + 1, valueEnd).trim()
            if (arguments.has(parameterName)) return null
            arguments.add(parameterName, parseValue(value))
            cursor = valueEnd + PARAMETER_CLOSE.length
        }
        return MnnToolCall(
            id = newCallId(),
            name = name,
            arguments = arguments.toString(),
        )
    }

    private fun normalizeArguments(element: com.google.gson.JsonElement?): JsonObject? {
        if (element == null || element.isJsonNull) return JsonObject()
        if (element.isJsonObject) return element.asJsonObject
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return runCatching { JsonParser.parseString(element.asString).asJsonObject }.getOrNull()
    }

    private fun newCallId(): String =
        "call_mnn_${UUID.randomUUID().toString().replace("-", "").take(20)}"

    private fun parseValue(value: String) = runCatching {
        JsonParser.parseString(value)
    }.getOrElse {
        com.google.gson.JsonPrimitive(value)
    }

    private fun malformed(raw: String, diagnostic: String): MnnParsedCompletion =
        MnnParsedCompletion(content = raw, toolCalls = emptyList(), diagnostic = diagnostic)
}
