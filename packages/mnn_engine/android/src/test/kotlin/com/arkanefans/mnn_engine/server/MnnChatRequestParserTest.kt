package com.arkanefans.mnn_engine.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MnnChatRequestParserTest {
    @Test
    fun parsesSupportedChatParameters() {
        val request = MnnChatRequestParser.parse(
            """{
              "model":"qwen",
              "messages":[
                {"role":"system","content":"be concise"},
                {"role":"user","content":"hello"}
              ],
              "stream":true,
              "temperature":0.3,
              "top_p":0.8,
              "max_tokens":64,
              "n":1
            }""",
        )

        assertEquals("qwen", request.model)
        assertEquals(true, request.stream)
        assertEquals(0.3, request.temperature)
        assertEquals(0.8, request.topP)
        assertEquals(64, request.maxTokens)
        assertEquals(2, request.messages.size)
    }

    @Test
    fun usesUnlimitedGenerationWhenTokenLimitIsMissing() {
        val request = MnnChatRequestParser.parse(
            """{"messages":[{"role":"user","content":"hello"}]}""",
        )

        assertEquals(-1, request.maxTokens)
    }

    @Test
    fun parsesLlamaServerTokenLimitAliases() {
        val completionTokens = MnnChatRequestParser.parse(
            """{"messages":[{"role":"user","content":"hello"}],"max_completion_tokens":32}""",
        )
        val nativeTokens = MnnChatRequestParser.parse(
            """{"messages":[{"role":"user","content":"hello"}],"n_predict":0}""",
        )

        assertEquals(32, completionTokens.maxTokens)
        assertEquals(0, nativeTokens.maxTokens)
    }

    @Test
    fun rejectsMultipleChoices() {
        val error = assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"n":2}""",
            )
        }
        assertTrue(error.message.orEmpty().contains("n must be 1"))
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"n":1.5}""",
            )
        }
    }

    @Test
    fun parsesToolsAndRequiredChoice() {
        val request = MnnChatRequestParser.parse(
            """{
              "messages":[{"role":"user","content":"hello"}],
              "tools":[{"type":"function","function":{"name":"get_time","parameters":{"type":"object"}}}],
              "tool_choice":"required",
              "parallel_tool_calls":true
            }""",
        )

        assertTrue(request.hasTools)
        assertEquals(MnnToolChoiceMode.REQUIRED, request.toolChoice.mode)
        assertEquals(true, request.parallelToolCalls)
        assertEquals(1, request.effectiveTools().size())

        val noTools = MnnChatRequestParser.parse(
            """{"messages":[{"role":"user","content":"hello"}],"tools":[]}""",
        )
        assertEquals(false, noTools.hasTools)

        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"tools":[],"tool_choice":"required"}""",
            )
        }
    }

    @Test
    fun parsesToolHistoryAndImageContent() {
        val request = MnnChatRequestParser.parse(
            """{
              "messages":[
                {"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"get_time","arguments":"{\"city\":\"Beijing\"}"}}]},
                {"role":"tool","tool_call_id":"call-1","content":"12:00"},
                {"role":"user","content":[{"type":"text","text":"What is this?"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AA==","detail":"high"}}]}
              ]
            }""",
        )

        assertEquals(3, request.messages.size)
        assertTrue(request.messages[0].has("tool_calls"))
        assertEquals("tool", request.messages[1].get("role").asString)
    }

    @Test
    fun acceptsLlamaServerToolHistoryContentVariants() {
        val contentVariants = listOf(
            "",
            "\"content\":null,",
            "\"content\":\"\",",
        )

        contentVariants.forEach { content ->
            val request = MnnChatRequestParser.parse(
                """{
                  "messages":[
                    {"role":"assistant",$content"tool_calls":[{"type":"function","function":{"name":"get_time","arguments":{"city":"Beijing"}}}]},
                    {"role":"tool","content":"12:00"}
                  ]
                }""",
            )

            assertEquals(2, request.messages.size)
            assertTrue(request.messages.first().has("tool_calls"))
        }
    }

    @Test
    fun rejectsStructurallyEmptyAssistantAndInvalidToolArguments() {
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"assistant"}]}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"assistant","content":"","tool_calls":[{"type":"function","function":{"name":"get_time","arguments":"not-json"}}]}]}""",
            )
        }
    }

    @Test
    fun rejectsOutOfRangeParameters() {
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"temperature":3}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"max_tokens":-2}""",
            )
        }
        val emptyContent = MnnChatRequestParser.parse(
            """{"messages":[{"role":"user","content":""}]}""",
        )
        assertEquals("", emptyContent.messages.single().get("content").asString)
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"temperature":"0.7"}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"stream":"true"}""",
            )
        }
    }
}
