package com.trustableai.koru.runtime.reasoner

import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ReasonerDecision
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.RuntimeAccelerator

class DeterministicOnlyReasoner : OnDeviceReasoner {
    override val backend: RuntimeBackend = RuntimeBackend.DETERMINISTIC

    override suspend fun warmup(): LiveBackendStatus {
        return LiveBackendStatus(
            backend = backend,
            state = LiveBackendState.READY,
            detail = "Deterministic on-device coaching active for hot, feedforward, and edge fallback paths",
            model = null,
            usesOnDeviceModel = false,
            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
            accelerator = RuntimeAccelerator.NONE,
        )
    }

    override suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision {
        return structuredFallbackDecision(
            window = window,
            backend = backend,
            phraseId = "deterministic-edge/${window.triggerId}",
            confidence = 0.62,
        )
    }
}
