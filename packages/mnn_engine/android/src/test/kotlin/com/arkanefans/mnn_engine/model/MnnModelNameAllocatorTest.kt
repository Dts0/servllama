package com.arkanefans.mnn_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MnnModelNameAllocatorTest {
    @Test
    fun `uses the smallest case-insensitive suffix`() {
        val result = MnnModelNameAllocator.nextAvailable(
            requestedModelName = "Qwen",
            unavailableNames = listOf("qwen", "QWEN (2)", "Qwen (4)"),
        )

        assertEquals("Qwen (3)", result)
    }

    @Test
    fun `keeps an available name unchanged`() {
        val result = MnnModelNameAllocator.nextAvailable(
            requestedModelName = "Qwen",
            unavailableNames = listOf("Llama"),
        )

        assertEquals("Qwen", result)
    }
}
