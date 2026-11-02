package com.arkanefans.mnn_engine.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MnnReasoningOutputParserTest {
    @Test
    fun detectsPromptSeededThinkingFromExplicitConfig() {
        val config = json(
            """{"jinja":{"context":{"enable_thinking":true}}}""",
        )
        val llmConfig = json(
            """{"jinja":{"chat_template":"<think> generated </think>"}}""",
        )

        val profile = MnnReasoningProfileDetector.detect(config, llmConfig)

        assertTrue(profile.parseThinkTags)
        assertEquals(MnnReasoningInitialState.REASONING, profile.initialState)
    }

    @Test
    fun detectsQwen3DefaultThinkingTemplate() {
        val llmConfig = json(
            """{
              "jinja": {
                "chat_template": "<think></think> {% if enable_thinking is defined and enable_thinking is false %}disabled{% endif %}"
              }
            }""",
        )

        val profile = MnnReasoningProfileDetector.detect(JsonObject(), llmConfig)

        assertTrue(profile.parseThinkTags)
        assertEquals(MnnReasoningInitialState.REASONING, profile.initialState)
    }

    @Test
    fun explicitDisabledThinkingStartsInContent() {
        val config = json(
            """{"jinja":{"context":{"enable_thinking":false}}}""",
        )
        val llmConfig = json(
            """{"jinja":{"chat_template":"<think></think>"}}""",
        )

        val profile = MnnReasoningProfileDetector.detect(config, llmConfig)

        assertTrue(profile.parseThinkTags)
        assertEquals(MnnReasoningInitialState.CONTENT, profile.initialState)
    }

    @Test
    fun contextFileCanDisableDefaultThinking() {
        val llmConfig = json(
            """{
              "jinja": {
                "chat_template": "<think></think> {% if enable_thinking is defined and enable_thinking is false %}disabled{% endif %}"
              }
            }""",
        )
        val context = json("""{"enable_thinking":false}""")

        val profile = MnnReasoningProfileDetector.detect(
            JsonObject(),
            llmConfig,
            context,
        )

        assertTrue(profile.parseThinkTags)
        assertEquals(MnnReasoningInitialState.CONTENT, profile.initialState)
    }

    @Test
    fun templateWithOnlyOpeningTagStartsInReasoning() {
        val profile = MnnReasoningProfileDetector.detect(
            JsonObject(),
            json("""{"jinja":{"chat_template":"assistant: <think>"}}"""),
        )

        assertTrue(profile.parseThinkTags)
        assertEquals(MnnReasoningInitialState.REASONING, profile.initialState)
    }

    @Test
    fun modelWithoutThinkingTemplateUsesPlainProfile() {
        val profile = MnnReasoningProfileDetector.detect(
            JsonObject(),
            json("""{"jinja":{"chat_template":"plain assistant"}}"""),
        )

        assertFalse(profile.parseThinkTags)
        assertEquals(MnnReasoningInitialState.CONTENT, profile.initialState)
    }

    @Test
    fun promptSeededThinkingStreamsReasoningBeforeClosingTag() {
        val parser = MnnReasoningOutputParser(reasoningProfile())
        val deltas = mutableListOf<MnnReasoningDelta>()

        deltas += parser.accept("分析")
        deltas += parser.accept("过程</thi")
        deltas += parser.accept("nk>最终")
        deltas += parser.accept("答案")
        deltas += parser.finish()

        assertEquals("分析过程", deltas.joinToString("") { it.reasoningContent })
        assertEquals("最终答案", deltas.joinToString("") { it.content })
    }

    @Test
    fun explicitThinkTagsCanBeSplitAcrossChunks() {
        val parser = MnnReasoningOutputParser(taggedContentProfile())
        val deltas = mutableListOf<MnnReasoningDelta>()

        deltas += parser.accept("<thi")
        deltas += parser.accept("nk>内部</th")
        deltas += parser.accept("ink>正文")
        deltas += parser.finish()

        assertEquals("内部", deltas.joinToString("") { it.reasoningContent })
        assertEquals("正文", deltas.joinToString("") { it.content })
    }

    @Test
    fun undecidedProfileHandlesClosingTagWithoutOpeningTag() {
        val parser = MnnReasoningOutputParser(
            MnnReasoningProfile(
                parseThinkTags = true,
                initialState = MnnReasoningInitialState.UNDECIDED,
            ),
        )

        assertTrue(parser.accept("尚未确定的内容").isEmpty())
        val deltas = parser.accept("</think>最终答案") + parser.finish()

        assertEquals("尚未确定的内容", deltas.joinToString("") { it.reasoningContent })
        assertEquals("最终答案", deltas.joinToString("") { it.content })
    }

    @Test
    fun textOutsideExplicitThinkBlockRemainsContent() {
        val result = MnnReasoningOutputParser.parse(
            "前言<think>分析</think>答案",
            taggedContentProfile(),
        )

        assertEquals("前言答案", result.content)
        assertEquals("分析", result.reasoningContent)
    }

    @Test
    fun unfinishedPromptSeededThinkingRemainsReasoningAtEndOfStream() {
        val result = MnnReasoningOutputParser.parse(
            "生成达到长度上限",
            reasoningProfile(),
        )

        assertNull(result.content)
        assertEquals("生成达到长度上限", result.reasoningContent)
    }

    @Test
    fun reasoningIsSeparatedBeforeToolCallParsing() {
        val reasoning = MnnReasoningOutputParser.parse(
            "先查询时间</think><tool_call><function=get_time></function></tool_call>",
            reasoningProfile(),
        )
        val completion = MnnToolCallParser.parse(
            reasoning.content.orEmpty(),
            setOf("get_time"),
            parallel = false,
        )

        assertEquals("先查询时间", reasoning.reasoningContent)
        assertNull(completion.content)
        assertEquals("get_time", completion.toolCalls.single().name)
    }

    @Test
    fun plainProfileDoesNotInterpretLiteralThinkTags() {
        val result = MnnReasoningOutputParser.parse(
            "示例：<think>不是控制块</think>",
            MnnReasoningProfile.PLAIN,
        )

        assertEquals("示例：<think>不是控制块</think>", result.content)
        assertNull(result.reasoningContent)
    }

    private fun reasoningProfile() = MnnReasoningProfile(
        parseThinkTags = true,
        initialState = MnnReasoningInitialState.REASONING,
    )

    private fun taggedContentProfile() = MnnReasoningProfile(
        parseThinkTags = true,
        initialState = MnnReasoningInitialState.CONTENT,
    )

    private fun json(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject
}
