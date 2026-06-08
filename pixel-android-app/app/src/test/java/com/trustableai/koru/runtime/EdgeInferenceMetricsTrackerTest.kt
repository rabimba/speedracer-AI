package com.trustableai.koru.runtime

import com.trustableai.koru.model.RuntimeBackend
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeInferenceMetricsTrackerTest {
    @Test
    fun `tokens per second uses output tokens and latency`() {
        assertEquals(0.0, EdgeInferenceMetricsTracker.tokensPerSecond(0, 100L), 0.001)
        assertEquals(0.0, EdgeInferenceMetricsTracker.tokensPerSecond(12, 0L), 0.001)
        assertEquals(24.0, EdgeInferenceMetricsTracker.tokensPerSecond(12, 500L), 0.001)
    }

    @Test
    fun `build copies measured fields`() {
        val metrics =
            EdgeInferenceMetricsTracker.build(
                backend = RuntimeBackend.LITERTLM,
                triggerId = "exit_hesitation",
                outputTokens = 18,
                latencyMs = 450L,
                promptTokens = 96,
                measuredAtMs = 1_700_000_000_000L,
            )

        assertEquals(RuntimeBackend.LITERTLM, metrics.backend)
        assertEquals("exit_hesitation", metrics.triggerId)
        assertEquals(18, metrics.outputTokens)
        assertEquals(96, metrics.promptTokens)
        assertEquals(450L, metrics.latencyMs)
        assertEquals(40.0, metrics.tokensPerSecond, 0.001)
        assertEquals(1_700_000_000_000L, metrics.measuredAtMs)
    }
}
