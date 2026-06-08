package com.trustableai.koru.runtime

import com.trustableai.koru.model.EdgeInferenceMetrics
import com.trustableai.koru.model.RuntimeBackend

object EdgeInferenceMetricsTracker {
    fun build(
        backend: RuntimeBackend,
        triggerId: String?,
        outputTokens: Int,
        latencyMs: Long,
        promptTokens: Int? = null,
        measuredAtMs: Long = System.currentTimeMillis(),
    ): EdgeInferenceMetrics {
        return EdgeInferenceMetrics(
            backend = backend,
            triggerId = triggerId,
            outputTokens = outputTokens.coerceAtLeast(0),
            promptTokens = promptTokens?.takeIf { it > 0 },
            latencyMs = latencyMs.coerceAtLeast(0L),
            tokensPerSecond = tokensPerSecond(outputTokens, latencyMs),
            measuredAtMs = measuredAtMs,
        )
    }

    fun tokensPerSecond(outputTokens: Int, latencyMs: Long): Double {
        if (latencyMs <= 0L || outputTokens <= 0) return 0.0
        return outputTokens * 1000.0 / latencyMs.toDouble()
    }
}
