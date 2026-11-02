package com.arkanefans.mnn_engine.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MnnToolCallParserTest {
    @Test
    fun parsesFunctionAndJsonParameters() {
        val result = MnnToolCallParser.parse(
            """before <tool_call><function=get_time><parameter=city>"Beijing"</parameter><parameter=offset>8</parameter></function></tool_call>""",
            setOf("get_time"),
            parallel = false,
        )

        assertEquals("before", result.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("get_time", result.toolCalls.single().name)
        assertEquals("{\"city\":\"Beijing\",\"offset\":8}", result.toolCalls.single().arguments)
        assertNull(result.diagnostic)
    }

    @Test
    fun parsesQwenJsonToolCallFormat() {
        val result = MnnToolCallParser.parse(
            """<tool_call>{"name":"get_time","arguments":{"city":"Beijing","offset":8}}</tool_call>""",
            setOf("get_time"),
            parallel = false,
        )

        assertNull(result.content)
        assertEquals("get_time", result.toolCalls.single().name)
        assertEquals("{\"city\":\"Beijing\",\"offset\":8}", result.toolCalls.single().arguments)
    }

    @Test
    fun parsesFunctionWrapperAndGeneratedId() {
        val result = MnnToolCallParser.parse(
            """<tool_call>{"id":"call-model-1","type":"function","function":{"name":"get_time","arguments":"{\"city\":\"Beijing\"}"}}</tool_call>""",
            setOf("get_time"),
            parallel = false,
        )

        assertEquals("call-model-1", result.toolCalls.single().id)
        assertEquals("{\"city\":\"Beijing\"}", result.toolCalls.single().arguments)
    }

    @Test
    fun parsesJsonArgumentsInsideFunctionTags() {
        val result = MnnToolCallParser.parse(
            """<tool_call><function=get_time>{"city":"Beijing"}</function></tool_call>""",
            setOf("get_time"),
            parallel = false,
        )

        assertEquals("{\"city\":\"Beijing\"}", result.toolCalls.single().arguments)
    }

    @Test
    fun clampsParallelCallsWhenDisabled() {
        val raw = """
            <tool_call><function=one><parameter=value>1</parameter></function></tool_call>
            <tool_call><function=two><parameter=value>2</parameter></function></tool_call>
        """.trimIndent()
        val result = MnnToolCallParser.parse(raw, setOf("one", "two"), parallel = false)

        assertEquals(1, result.toolCalls.size)
        assertEquals("tool_call_count_clamped", result.diagnostic)
    }

    @Test
    fun malformedOrUnknownCallsRemainText() {
        val result = MnnToolCallParser.parse(
            "<tool_call><function=unknown><parameter=x>1</parameter></function></tool_call>",
            setOf("known"),
            parallel = false,
        )

        assertEquals(emptyList(), result.toolCalls)
        assertEquals("malformed_tool_call", result.diagnostic)
        assertEquals(
            "<tool_call><function=unknown><parameter=x>1</parameter></function></tool_call>",
            result.content,
        )
    }
}
