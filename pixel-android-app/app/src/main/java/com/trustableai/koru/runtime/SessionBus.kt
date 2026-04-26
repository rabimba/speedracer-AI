package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.TelemetryFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object KoruSessionBus {
    private val telemetryFlow = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity = 32)
    private val decisionFlow = MutableSharedFlow<CoachingDecision>(extraBufferCapacity = 16)
    private val savedSessionFlow = MutableSharedFlow<RecordedSessionArtifact>(extraBufferCapacity = 2)
    private val statusFlow = MutableStateFlow(
        LiveBackendStatus(
            backend = RuntimeBackend.DETERMINISTIC,
            state = LiveBackendState.IDLE,
            detail = "Android session idle",
            usesOnDeviceModel = false,
            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
        ),
    )

    val telemetry: SharedFlow<TelemetryFrame> = telemetryFlow.asSharedFlow()
    val decisions: SharedFlow<CoachingDecision> = decisionFlow.asSharedFlow()
    val savedSessions: SharedFlow<RecordedSessionArtifact> = savedSessionFlow.asSharedFlow()
    val status: StateFlow<LiveBackendStatus> = statusFlow.asStateFlow()

    fun tryEmitFrame(frame: TelemetryFrame) {
        telemetryFlow.tryEmit(frame)
    }

    fun tryEmitDecision(decision: CoachingDecision) {
        decisionFlow.tryEmit(decision)
    }

    fun tryEmitSavedSession(session: RecordedSessionArtifact) {
        savedSessionFlow.tryEmit(session)
    }

    fun tryEmitStatus(status: LiveBackendStatus) {
        statusFlow.value = status
    }
}
