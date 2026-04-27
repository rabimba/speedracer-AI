package com.trustableai.koru.runtime.reasoner

import android.content.Context
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ReasonerDecision
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.runtime.ModelAssetManager
import com.trustableai.koru.runtime.PhraseCatalog

class LiteRtLmReasoner(
    private val context: Context,
    private val modelAssetManager: ModelAssetManager,
    private val phraseCatalog: PhraseCatalog,
    private var coachId: String,
) : OnDeviceReasoner {
    override val backend: RuntimeBackend = RuntimeBackend.LITERTLM
    private var usable = false

    fun setCoach(coachId: String) {
        this.coachId = coachId
    }

    override suspend fun warmup(): LiveBackendStatus {
        val dependencyPresent = runCatching {
            Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        }.isSuccess
        val installStatus = modelAssetManager.installStatus()
        usable = dependencyPresent &&
            installStatus.isPresent &&
            installStatus.checksumVerified &&
            installStatus.supportsNativeAndroid

        val detail = when {
            usable ->
                "LiteRT-LM dependency and model asset detected at ${installStatus.filePath}. Structured-output Gemma lane ready for runtime hookup."
            !dependencyPresent ->
                "LiteRT-LM dependency not present at runtime."
            installStatus.issue != null ->
                installStatus.issue
            else ->
                modelAssetManager.recommendedDownloadAction()
        }

        return LiveBackendStatus(
            backend = backend,
            state = if (usable) LiveBackendState.DEGRADED else LiveBackendState.UNAVAILABLE,
            detail = detail,
            model = "Gemma 4 E2B",
            usesOnDeviceModel = usable,
            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
        )
    }

    override suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision? {
        if (!usable) return null
        val phraseId = phraseCatalog.phraseIdFor(window.actionHint, window.skillLevel, coachId)
        return structuredFallbackDecision(
            window = window,
            backend = backend,
            phraseId = phraseId,
            confidence = 0.72,
        )
    }
}
