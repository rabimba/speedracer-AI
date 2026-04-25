package com.trustableai.koru.runtime

import android.content.Context
import android.content.SharedPreferences
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.runtime.reasoner.AiCoreReasoner
import com.trustableai.koru.runtime.reasoner.DeterministicOnlyReasoner
import com.trustableai.koru.runtime.reasoner.LiteRtLmReasoner
import com.trustableai.koru.runtime.reasoner.OnDeviceReasoner

class EdgeRuntimeManager(
    context: Context,
    private val modelAssetManager: ModelAssetManager,
    private val phraseCatalog: PhraseCatalog,
    initialCoachId: String,
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("koru_edge_runtime", Context.MODE_PRIVATE)
    private val aiCoreReasoner = AiCoreReasoner(context, phraseCatalog, initialCoachId)
    private val liteRtLmReasoner = LiteRtLmReasoner(context, modelAssetManager, phraseCatalog, initialCoachId)
    private val deterministicReasoner = DeterministicOnlyReasoner()
    private var activeReasoner: OnDeviceReasoner = deterministicReasoner
    private var currentStatus: LiveBackendStatus = LiveBackendStatus(
        backend = RuntimeBackend.DETERMINISTIC,
        state = LiveBackendState.IDLE,
        detail = "Runtime manager idle",
        usesOnDeviceModel = false,
        supportedPaths = emptyList(),
    )

    suspend fun warmupPreferredBackend(): LiveBackendStatus {
        val aiCoreStatus = aiCoreReasoner.warmup()
        if (aiCoreStatus.state != LiveBackendState.UNAVAILABLE && aiCoreStatus.state != LiveBackendState.ERROR) {
            activeReasoner = aiCoreReasoner
            currentStatus = aiCoreStatus
            persistBackend(aiCoreStatus.backend)
            return currentStatus
        }

        val liteRtStatus = liteRtLmReasoner.warmup()
        if (liteRtStatus.state != LiveBackendState.UNAVAILABLE && liteRtStatus.state != LiveBackendState.ERROR) {
            activeReasoner = liteRtLmReasoner
            currentStatus = liteRtStatus
            persistBackend(liteRtStatus.backend)
            return currentStatus
        }

        val deterministicStatus = deterministicReasoner.warmup()
        activeReasoner = deterministicReasoner
        currentStatus = deterministicStatus
        persistBackend(deterministicStatus.backend)
        return currentStatus
    }

    fun currentReasoner(): OnDeviceReasoner = activeReasoner

    fun currentStatus(): LiveBackendStatus = currentStatus

    fun updateCoach(coachId: String) {
        aiCoreReasoner.setCoach(coachId)
        liteRtLmReasoner.setCoach(coachId)
    }

    private fun persistBackend(backend: RuntimeBackend) {
        preferences.edit().putString(KEY_BACKEND, backend.name).apply()
    }

    companion object {
        private const val KEY_BACKEND = "selected_backend"
    }
}
