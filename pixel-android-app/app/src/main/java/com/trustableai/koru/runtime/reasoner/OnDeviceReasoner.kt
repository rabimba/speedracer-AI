package com.trustableai.koru.runtime.reasoner

import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ReasonerDecision
import com.trustableai.koru.model.RuntimeBackend

interface OnDeviceReasoner {
    val backend: RuntimeBackend

    suspend fun warmup(): LiveBackendStatus

    suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision?

    suspend fun close() = Unit
}

fun structuredFallbackDecision(
    window: EdgeReasoningWindow,
    backend: RuntimeBackend,
    phraseId: String,
    confidence: Double,
): ReasonerDecision {
    val boundedConfidence = confidence.coerceIn(0.0, 1.0)
    return ReasonerDecision(
        speak = true,
        action = window.actionHint,
        priority = window.priority,
        phraseId = phraseId,
        confidence = boundedConfidence,
        text = window.suggestedText,
    )
}
