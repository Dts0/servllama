package com.arkanefans.mnn_engine.server

import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal enum class MnnReasoningInitialState {
    CONTENT,
    REASONING,
    UNDECIDED,
}

internal data class MnnReasoningProfile(
    val parseThinkTags: Boolean,
    val initialState: MnnReasoningInitialState,
) {
    companion object {
        val PLAIN = MnnReasoningProfile(
            parseThinkTags = false,
            initialState = MnnReasoningInitialState.CONTENT,
        )
    }
}

internal data class MnnReasoningDelta(
    val content: String = "",
    val reasoningContent: String = "",
) {
    val isEmpty: Boolean
        get() = content.isEmpty() && reasoningContent.isEmpty()
}

internal data class MnnReasoningResult(
    val content: String?,
    val reasoningContent: String?,
)

/**
 * Detects whether the active model's chat template expects a thinking block.
 *
 * Some templates (notably Qwen3.5) append `<think>` to the assistant prompt.
 * That opening tag is therefore part of the prompt and never appears in the
 * generated token stream; generation starts inside the reasoning block and
 * only emits `</think>`. Other templates let the model emit both tags. The
 * profile lets the streaming parser handle both forms without delaying known
 * non-thinking models.
 */
internal object MnnReasoningProfileDetector {
    fun detect(model: MnnModelInfo): MnnReasoningProfile {
        val configFile = File(model.configPath)
        val config = readJson(configFile) ?: return MnnReasoningProfile.PLAIN
        val modelDir = configFile.parentFile ?: return MnnReasoningProfile.PLAIN
        val llmConfigPath = config.stringValue("llm_config") ?: DEFAULT_LLM_CONFIG_FILE
        val llmConfig = readJson(File(modelDir, llmConfigPath))
        val contextPath = config.stringValue("context_file")
            ?: llmConfig?.stringValue("context_file")
            ?: DEFAULT_CONTEXT_FILE
        val context = readJson(File(modelDir, contextPath))
        return detect(config, llmConfig, context)
    }

    internal fun detect(
        config: JsonObject,
        llmConfig: JsonObject?,
        context: JsonObject? = null,
    ): MnnReasoningProfile {
        val template = config.chatTemplate() ?: llmConfig?.chatTemplate().orEmpty()
        val hasOpenTag = template.contains(OPEN)
        val hasCloseTag = template.contains(CLOSE)
        if (!hasOpenTag && !hasCloseTag) return MnnReasoningProfile.PLAIN

        // The runtime re-applies config.json for every request, while
        // context.json is merged during model load. Mirror that effective
        // precedence so the protocol parser agrees with the native template.
        val explicitThinking = config.enableThinking()
            ?: context?.booleanValue("enable_thinking")
            ?: llmConfig?.enableThinking()
        if (explicitThinking != null) {
            return MnnReasoningProfile(
                parseThinkTags = true,
                initialState = if (explicitThinking) {
                    MnnReasoningInitialState.REASONING
                } else {
                    MnnReasoningInitialState.CONTENT
                },
            )
        }

        val normalized = template.lowercase().replace(Regex("\\s+"), " ")
        val defaultsToThinking = DEFAULT_THINKING_PATTERN.containsMatchIn(normalized)
        val defaultsToContent = DEFAULT_CONTENT_PATTERN.containsMatchIn(normalized)
        return MnnReasoningProfile(
            parseThinkTags = true,
            initialState = when {
                hasOpenTag && !hasCloseTag -> MnnReasoningInitialState.REASONING
                defaultsToThinking -> MnnReasoningInitialState.REASONING
                defaultsToContent -> MnnReasoningInitialState.CONTENT
                else -> MnnReasoningInitialState.UNDECIDED
            },
        )
    }

    private fun readJson(file: File): JsonObject? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        }.getOrNull()
    }

    private fun JsonObject.chatTemplate(): String? =
        objectValue("jinja")?.stringValue("chat_template")

    private fun JsonObject.enableThinking(): Boolean? =
        objectValue("jinja")?.objectValue("context")?.booleanValue("enable_thinking")

    private fun JsonObject.objectValue(key: String): JsonObject? {
        val value = get(key) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = get(key) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    private fun JsonObject.booleanValue(key: String): Boolean? {
        val value = get(key) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
    }

    private const val DEFAULT_LLM_CONFIG_FILE = "llm_config.json"
    private const val DEFAULT_CONTEXT_FILE = "context.json"
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"
    private val DEFAULT_THINKING_PATTERN = Regex(
        "enable_thinking\\s+is\\s+defined\\s+and\\s+enable_thinking\\s+is\\s+false",
    )
    private val DEFAULT_CONTENT_PATTERN = Regex(
        "enable_thinking\\s+is\\s+defined\\s+and\\s+enable_thinking\\s+is\\s+true",
    )
}

/**
 * Converts raw MNN token text into OpenAI-compatible content and
 * `reasoning_content` deltas while removing model control tags.
 */
internal class MnnReasoningOutputParser(
    private val profile: MnnReasoningProfile,
) {
    private enum class State {
        CONTENT,
        REASONING,
        UNDECIDED,
    }

    private val pending = StringBuilder()
    private var state = when (profile.initialState) {
        MnnReasoningInitialState.CONTENT -> State.CONTENT
        MnnReasoningInitialState.REASONING -> State.REASONING
        MnnReasoningInitialState.UNDECIDED -> State.UNDECIDED
    }

    fun accept(chunk: String): List<MnnReasoningDelta> {
        if (chunk.isEmpty()) return emptyList()
        if (!profile.parseThinkTags) {
            return listOf(MnnReasoningDelta(content = chunk))
        }
        pending.append(chunk)
        return drain(endOfStream = false)
    }

    fun finish(): List<MnnReasoningDelta> {
        if (!profile.parseThinkTags || pending.isEmpty()) return emptyList()
        return drain(endOfStream = true)
    }

    private fun drain(endOfStream: Boolean): List<MnnReasoningDelta> {
        val deltas = mutableListOf<MnnReasoningDelta>()
        while (pending.isNotEmpty()) {
            val text = pending.toString()
            val openIndex = text.indexOf(OPEN)
            val closeIndex = text.indexOf(CLOSE)
            val marker = firstMarker(openIndex, closeIndex)

            if (state == State.UNDECIDED) {
                if (marker == null) {
                    if (endOfStream) {
                        emit(deltas, State.CONTENT, text)
                        pending.clear()
                    }
                    break
                }
                val before = text.substring(0, marker.index)
                if (marker.value == OPEN) {
                    emit(deltas, State.CONTENT, before)
                    state = State.REASONING
                } else {
                    emit(deltas, State.REASONING, before)
                    state = State.CONTENT
                }
                pending.delete(0, marker.index + marker.value.length)
                continue
            }

            if (marker != null) {
                emit(deltas, state, text.substring(0, marker.index))
                state = when (marker.value) {
                    OPEN -> State.REASONING
                    else -> State.CONTENT
                }
                pending.delete(0, marker.index + marker.value.length)
                continue
            }

            val emitLength = if (endOfStream) text.length else safePrefixLength(text)
            if (emitLength <= 0) break
            emit(deltas, state, text.substring(0, emitLength))
            pending.delete(0, emitLength)
        }
        return deltas
    }

    private fun firstMarker(openIndex: Int, closeIndex: Int): Marker? = when {
        openIndex < 0 && closeIndex < 0 -> null
        openIndex >= 0 && (closeIndex < 0 || openIndex < closeIndex) -> Marker(openIndex, OPEN)
        else -> Marker(closeIndex, CLOSE)
    }

    private fun safePrefixLength(text: String): Int {
        var heldSuffixLength = 0
        for (marker in MARKERS) {
            val maximum = minOf(text.length, marker.length - 1)
            for (length in maximum downTo 1) {
                if (text.endsWith(marker.substring(0, length))) {
                    heldSuffixLength = maxOf(heldSuffixLength, length)
                    break
                }
            }
        }
        return text.length - heldSuffixLength
    }

    private fun emit(
        deltas: MutableList<MnnReasoningDelta>,
        target: State,
        text: String,
    ) {
        if (text.isEmpty()) return
        val delta = if (target == State.REASONING) {
            MnnReasoningDelta(reasoningContent = text)
        } else {
            MnnReasoningDelta(content = text)
        }
        val last = deltas.lastOrNull()
        if (last != null && last.content.isEmpty() == delta.content.isEmpty()) {
            deltas[deltas.lastIndex] = MnnReasoningDelta(
                content = last.content + delta.content,
                reasoningContent = last.reasoningContent + delta.reasoningContent,
            )
        } else {
            deltas += delta
        }
    }

    private data class Marker(val index: Int, val value: String)

    companion object {
        private const val OPEN = "<think>"
        private const val CLOSE = "</think>"
        private val MARKERS = listOf(OPEN, CLOSE)

        fun parse(raw: String, profile: MnnReasoningProfile): MnnReasoningResult {
            val parser = MnnReasoningOutputParser(profile)
            val deltas = parser.accept(raw) + parser.finish()
            val content = buildString {
                deltas.forEach { append(it.content) }
            }.ifBlank { null }
            val reasoningContent = buildString {
                deltas.forEach { append(it.reasoningContent) }
            }.ifBlank { null }
            return MnnReasoningResult(content, reasoningContent)
        }
    }
}
