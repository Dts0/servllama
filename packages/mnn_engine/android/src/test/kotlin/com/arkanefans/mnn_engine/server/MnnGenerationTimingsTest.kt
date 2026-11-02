package com.arkanefans.mnn_engine.server

import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager.GenerationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MnnGenerationTimingsTest {
    private val complete = GenerationResult(100, 20, 250_000, 2_000_000, 750_000, "stop")

    @Test
    fun usesNativeMicrosecondsAndDoesNotAddSamplingOrInventCacheHits() {
        val timings = assertNotNull(MnnGenerationTimings.from(complete))
        assertEquals(100, timings["prompt_n"].asInt)
        assertEquals(250.0, timings["prompt_ms"].asDouble)
        assertEquals(400.0, timings["prompt_per_second"].asDouble)
        assertEquals(20, timings["predicted_n"].asInt)
        assertEquals(2000.0, timings["predicted_ms"].asDouble)
        assertEquals(10.0, timings["predicted_per_second"].asDouble)
        assertEquals("mnn", timings["backend"].asString)
        assertEquals("total_input", timings["prompt_tokens_scope"].asString)
        assertEquals("native_generated", timings["predicted_tokens_scope"].asString)
        assertEquals("native_decode", timings["decode_time_scope"].asString)
        assertFalse(timings.has("cache_n"))
    }

    @Test
    fun acceptsTokenLimitButNeverCancelledFailedOrUnknownResults() {
        assertNotNull(MnnGenerationTimings.from(complete.copy(finishReason = "length")))
        for (reason in listOf("cancelled", "error", "failed", "timeout", "", "unknown")) {
            assertNull(MnnGenerationTimings.from(complete.copy(finishReason = reason)), reason)
        }
    }

    @Test
    fun unavailablePhaseIsOmittedRatherThanReportedAsZero() {
        for (micros in listOf(0L, -1L)) {
            val prefillOnly = assertNotNull(MnnGenerationTimings.from(complete.copy(decodeUs = micros)))
            assertTrue(prefillOnly.has("prompt_per_second"))
            assertFalse(prefillOnly.has("predicted_per_second"))
            assertFalse(prefillOnly.has("predicted_ms"))
            assertFalse(prefillOnly.has("predicted_n"))
            val decodeOnly = assertNotNull(MnnGenerationTimings.from(complete.copy(prefillUs = micros)))
            assertTrue(decodeOnly.has("predicted_per_second"))
            assertFalse(decodeOnly.has("prompt_per_second"))
            assertFalse(decodeOnly.has("prompt_ms"))
            assertFalse(decodeOnly.has("prompt_n"))
            assertNull(MnnGenerationTimings.from(complete.copy(prefillUs = micros, decodeUs = micros)))
        }
    }

    @Test
    fun invalidTokenCountsAreUnavailableAndLargeCountsDoNotOverflow() {
        for (tokens in listOf(0, -1)) {
            assertNull(MnnGenerationTimings.from(complete.copy(promptTokens = tokens, completionTokens = tokens)))
        }
        val large = assertNotNull(MnnGenerationTimings.from(complete.copy(
            promptTokens = Int.MAX_VALUE, completionTokens = Int.MAX_VALUE,
            prefillUs = Long.MAX_VALUE, decodeUs = Long.MAX_VALUE,
        )))
        assertTrue(large["prompt_per_second"].asDouble.isFinite())
        assertTrue(large["prompt_per_second"].asDouble > 0)
        assertTrue(large["predicted_per_second"].asDouble.isFinite())
    }
}
