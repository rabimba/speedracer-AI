package com.trustableai.koru.runtime.reasoner

import android.content.Context
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ReasonerDecision
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.runtime.PhraseCatalog

class AiCoreReasoner(
    private val context: Context,
    private val phraseCatalog: PhraseCatalog,
    private var coachId: String,
) : OnDeviceReasoner {
    override val backend: RuntimeBackend = RuntimeBackend.AICORE
    private var usable = false

    fun setCoach(coachId: String) {
        this.coachId = coachId
    }

    override suspend fun warmup(): LiveBackendStatus {
        usable = runCatching { Class.forName("com.google.ai.edge.aicore.GenerativeModel") }.isSuccess
        val state = if (usable) LiveBackendState.DEGRADED else LiveBackendState.UNAVAILABLE
        val detail = if (usable) {
            "AICore SDK detected. Structured-output reaction lane is scaffolded for on-device Prompt API integration."
        } else {
            "AICore SDK not detected in the current build/runtime."
        }
        return LiveBackendStatus(
            backend = backend,
            state = state,
            detail = detail,
            model = "Gemma 4 / Gemini Nano 4 preview",
            usesOnDeviceModel = usable,
            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
        )
    }

    override suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision? {
        if (!usable) return null
        val phraseId = phraseCatalog.phraseIdFor(window.actionHint, window.skillLevel, coachId)
        return structuredFallbackDecision(
            window = window.copy(
                suggestedText = phraseCatalog.render(window.actionHint, window.skillLevel, coachId),
            ),
            backend = backend,
            phraseId = phraseId,
            confidence = 0.78,
        )
    }
}
