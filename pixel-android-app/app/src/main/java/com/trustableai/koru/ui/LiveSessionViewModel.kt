package com.trustableai.koru.ui

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trustableai.koru.audio.CoachAudioDispatcher
import com.trustableai.koru.model.AimCanBitrate
import com.trustableai.koru.model.AudioDispatchEvent
import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.EdgeInferenceMetrics
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.RecordingStatus
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionGoalFocus
import com.trustableai.koru.model.SessionGoalSource
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.model.TrackHudMode
import com.trustableai.koru.model.bridgeValue
import com.trustableai.koru.model.trackHudModeFromBridge
import com.trustableai.koru.runtime.CameraDirectSessionController
import com.trustableai.koru.runtime.CoachRecommender
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.LiveSessionConfig
import com.trustableai.koru.runtime.TrackCatalog
import com.trustableai.koru.service.KoruTelemetryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class CoachOption(
    val id: String,
    val name: String,
    val style: String,
)

data class GoalOption(
    val focus: SessionGoalFocus,
    val label: String,
    val description: String,
    val actions: List<CoachAction>,
)

data class SessionUiState(
    val backendStatus: LiveBackendStatus = LiveBackendStatus(
        backend = RuntimeBackend.DETERMINISTIC,
        state = LiveBackendState.IDLE,
        detail = "Android session idle",
        usesOnDeviceModel = false,
        supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
    ),
    val latestFrame: TelemetryFrame? = null,
    val decisions: List<CoachingDecision> = emptyList(),
    val lastAudioEvent: AudioDispatchEvent? = null,
    val recordingStatus: RecordingStatus = RecordingStatus(),
    val savedSession: RecordedSessionArtifact? = null,
    val savedSessions: List<RecordedSessionArtifact> = emptyList(),
    val replaySessionId: String? = null,
    val replayFrameIndex: Int = 0,
    val replayPlaying: Boolean = false,
    val activeCoachId: String = "superaj",
    val audioEnabled: Boolean = true,
    val trackName: String = TrackCatalog.defaultTrack.name,
    val sessionMode: SessionMode = SessionMode.TELEMETRY,
    val telemetrySource: TelemetrySourceKind = TelemetrySourceKind.AIM_CAN_USB,
    val obdTransportPreference: ObdTransportPreference = ObdTransportPreference.AUTO,
    val aimCanBitrate: AimCanBitrate = AimCanBitrate.S8_1MBPS,
    val cameraFusionEnabled: Boolean = false,
    val selectedGoalFocuses: Set<SessionGoalFocus> = emptySet(),
    val customGoalDescription: String = "",
    val cameraStatus: String = "Camera lane waiting for permission",
    val activeSessionMode: SessionMode? = null,
    val edgeInferenceMetrics: EdgeInferenceMetrics? = null,
    val trackHudMode: TrackHudMode = TrackHudMode.SIGNAL_ONLY,
) {
    val isSessionActive: Boolean
        get() = activeSessionMode != null ||
            backendStatus.state == LiveBackendState.STARTING ||
            backendStatus.state == LiveBackendState.READY ||
            backendStatus.state == LiveBackendState.DEGRADED
}

class LiveSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val cameraDirectController = CameraDirectSessionController(appContext)
    private val audioCheckDispatcher = CoachAudioDispatcher(appContext)
    private var replayJob: Job? = null
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        SessionUiState(
            trackHudMode = trackHudModeFromBridge(preferences.getString(KEY_TRACK_HUD_MODE, null)),
        ),
    )
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    val coachOptions = listOf(
        CoachOption("superaj", "Super AJ", "Adaptive"),
        CoachOption("aj", "AJ", "Direct"),
        CoachOption("rachel", "Rachel", "Technical"),
        CoachOption("tony", "Tony", "Motivational"),
        CoachOption("garmin", "Garmin", "Data"),
    )

    val goalOptions = listOf(
        GoalOption(
            focus = SessionGoalFocus.BRAKING,
            label = "Braking",
            description = "Hard initial pressure and clean release.",
            actions = listOf(CoachAction.THRESHOLD, CoachAction.SPIKE_BRAKE, CoachAction.TRAIL_BRAKE),
        ),
        GoalOption(
            focus = SessionGoalFocus.THROTTLE,
            label = "Throttle",
            description = "Commit on exits without rushing the apex.",
            actions = listOf(CoachAction.HUSTLE, CoachAction.COMMIT, CoachAction.EARLY_THROTTLE),
        ),
        GoalOption(
            focus = SessionGoalFocus.VISION,
            label = "Vision",
            description = "Eyes up and looking through the next reference.",
            actions = listOf(CoachAction.PUSH, CoachAction.COAST),
        ),
        GoalOption(
            focus = SessionGoalFocus.LINES,
            label = "Lines",
            description = "Turn-in, apex, and exit placement.",
            actions = listOf(CoachAction.TURN_IN, CoachAction.APEX),
        ),
        GoalOption(
            focus = SessionGoalFocus.SMOOTHNESS,
            label = "Smoothness",
            description = "Calm inputs when the car is loaded.",
            actions = listOf(CoachAction.COGNITIVE_OVERLOAD, CoachAction.LIFT_MID_CORNER),
        ),
        GoalOption(
            focus = SessionGoalFocus.CUSTOM,
            label = "Custom",
            description = "One operator-defined focus for this session.",
            actions = listOf(CoachAction.COGNITIVE_OVERLOAD),
        ),
    )

    init {
        viewModelScope.launch {
            KoruSessionBus.status.collect { status ->
                _uiState.update {
                    it.copy(
                        backendStatus = status,
                        activeSessionMode = if (
                            status.state == LiveBackendState.IDLE ||
                            status.state == LiveBackendState.ERROR ||
                            status.state == LiveBackendState.UNAVAILABLE
                        ) {
                            null
                        } else {
                            it.activeSessionMode
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.latestTelemetry.collect { frame ->
                _uiState.update { it.copy(latestFrame = frame) }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.decisionHistory.collect { decisions ->
                _uiState.update { it.copy(decisions = decisions) }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.latestAudioEvent.collect { event ->
                _uiState.update { it.copy(lastAudioEvent = event) }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.recordingStatus.collect { status ->
                _uiState.update { it.copy(recordingStatus = status) }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.latestSavedSession.collect { session ->
                if (session != null) {
                    _uiState.update { state ->
                        val sessions = (listOf(session) + state.savedSessions.filterNot { it.id == session.id }).take(20)
                        state.copy(
                            savedSession = session,
                            savedSessions = sessions,
                            replaySessionId = state.replaySessionId ?: session.id,
                            replayFrameIndex = if (state.replaySessionId == null) 0 else state.replayFrameIndex,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.edgeInferenceMetrics.collect { metrics ->
                _uiState.update { it.copy(edgeInferenceMetrics = metrics) }
            }
        }
    }

    fun currentConfig(): LiveSessionConfig {
        val state = _uiState.value
        return LiveSessionConfig(
            coachId = state.activeCoachId,
            audioEnabled = state.audioEnabled,
            trackName = if (state.sessionMode == SessionMode.DEVICE_TEST) {
                "Device GPS Test"
            } else if (state.sessionMode == SessionMode.CAN_INTERFACE_CHECK) {
                "CAN Interface Check"
            } else {
                state.trackName
            },
            sessionMode = state.sessionMode,
            telemetrySource = state.telemetrySource,
            obdTransportPreference = state.obdTransportPreference,
            aimCanBitrate = state.aimCanBitrate,
            cameraFusionEnabled = state.cameraFusionEnabled,
            sessionGoals = sessionGoals(state),
            sourceUrl = null,
        )
    }

    fun startSession(config: LiveSessionConfig = currentConfig()) {
        KoruSessionBus.resetLiveState()
        _uiState.update { it.copy(activeSessionMode = config.sessionMode) }
        when (config.sessionMode) {
            SessionMode.CAMERA_DIRECT -> cameraDirectController.start(config)
            SessionMode.CAN_INTERFACE_CHECK, SessionMode.DEVICE_TEST, SessionMode.TELEMETRY ->
                ContextCompat.startForegroundService(appContext, KoruTelemetryService.startIntent(appContext, config.toJson()))
        }
    }

    fun stopSession() {
        when (_uiState.value.activeSessionMode) {
            SessionMode.CAMERA_DIRECT -> cameraDirectController.stop()
            SessionMode.CAN_INTERFACE_CHECK, SessionMode.DEVICE_TEST, SessionMode.TELEMETRY ->
                appContext.startService(KoruTelemetryService.stopIntent(appContext))
            null -> Unit
        }
        _uiState.update { it.copy(activeSessionMode = null) }
    }

    fun setActiveCoach(coachId: String) {
        _uiState.update { it.copy(activeCoachId = coachId) }
        cameraDirectController.setActiveCoach(coachId)
        if (_uiState.value.activeSessionMode != null && _uiState.value.activeSessionMode != SessionMode.CAMERA_DIRECT) {
            appContext.startService(KoruTelemetryService.setCoachIntent(appContext, coachId))
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        _uiState.update { it.copy(audioEnabled = enabled) }
        cameraDirectController.setAudioEnabled(enabled)
        if (_uiState.value.activeSessionMode != null && _uiState.value.activeSessionMode != SessionMode.CAMERA_DIRECT) {
            appContext.startService(KoruTelemetryService.setAudioIntent(appContext, enabled))
        }
    }

    fun playAudioCheck() {
        audioCheckDispatcher.setEnabled(true)
        audioCheckDispatcher.playSessionClip(
            clipName = "coach_ready",
            utteranceId = "audio-check-${System.currentTimeMillis()}",
        ) { event ->
            KoruSessionBus.tryEmitAudioEvent(event)
            _uiState.update { it.copy(lastAudioEvent = event) }
        }
    }

    fun setSessionMode(mode: SessionMode) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(sessionMode = mode) }
    }

    fun setTelemetrySource(source: TelemetrySourceKind) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(telemetrySource = source) }
    }

    fun setObdTransportPreference(preference: ObdTransportPreference) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(obdTransportPreference = preference) }
    }

    fun setAimCanBitrate(bitrate: AimCanBitrate) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(aimCanBitrate = bitrate) }
    }

    fun setCameraFusionEnabled(enabled: Boolean) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(cameraFusionEnabled = enabled) }
    }

    fun setTrackName(trackName: String) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(trackName = trackName) }
    }

    fun toggleGoal(focus: SessionGoalFocus) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { state ->
            val selected = state.selectedGoalFocuses
            val next = when {
                focus in selected -> selected - focus
                selected.size >= 3 -> selected
                else -> selected + focus
            }
            state.copy(selectedGoalFocuses = next)
        }
    }

    fun setCustomGoalDescription(description: String) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(customGoalDescription = description.take(120)) }
    }

    fun setCameraStatus(status: String) {
        _uiState.update { it.copy(cameraStatus = status) }
    }

    fun requestBackendStatus() {
        KoruSessionBus.tryEmitStatus(KoruSessionBus.status.value)
    }

    fun sessionGoals(): List<SessionGoal> = sessionGoals(_uiState.value)

    fun recommendedCoachId(): String =
        CoachRecommender.recommendCoachId(sessionGoals(), _uiState.value.sessionMode)

    fun setTrackHudMode(mode: TrackHudMode) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(trackHudMode = mode) }
        preferences.edit().putString(KEY_TRACK_HUD_MODE, mode.bridgeValue()).apply()
    }

    fun selectReplaySession(sessionId: String) {
        stopReplay()
        _uiState.update {
            it.copy(
                replaySessionId = sessionId,
                replayFrameIndex = 0,
                replayPlaying = false,
            )
        }
    }

    fun setReplayFrameIndex(index: Int) {
        val maxIndex = selectedReplaySession()?.frames?.lastIndex?.coerceAtLeast(0) ?: 0
        _uiState.update { it.copy(replayFrameIndex = index.coerceIn(0, maxIndex)) }
    }

    fun toggleReplay() {
        if (_uiState.value.replayPlaying) {
            stopReplay()
        } else {
            startReplay()
        }
    }

    private fun startReplay() {
        val session = selectedReplaySession() ?: return
        if (session.frames.isEmpty()) return
        replayJob?.cancel()
        _uiState.update { it.copy(replayPlaying = true) }
        replayJob = viewModelScope.launch {
            while (_uiState.value.replayPlaying) {
                delay(REPLAY_TICK_MS)
                _uiState.update { state ->
                    val active = state.savedSessions.firstOrNull { it.id == state.replaySessionId }
                        ?: state.savedSession
                    val lastIndex = active?.frames?.lastIndex ?: 0
                    if (state.replayFrameIndex >= lastIndex) {
                        state.copy(replayFrameIndex = lastIndex, replayPlaying = false)
                    } else {
                        state.copy(replayFrameIndex = state.replayFrameIndex + 1)
                    }
                }
                if (!_uiState.value.replayPlaying) break
            }
        }
    }

    private fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        _uiState.update { it.copy(replayPlaying = false) }
    }

    private fun selectedReplaySession(): RecordedSessionArtifact? {
        val state = _uiState.value
        return state.savedSessions.firstOrNull { it.id == state.replaySessionId } ?: state.savedSession
    }

    override fun onCleared() {
        stopReplay()
        cameraDirectController.shutdown()
        audioCheckDispatcher.shutdown()
        super.onCleared()
    }

    private fun sessionGoals(state: SessionUiState): List<SessionGoal> {
        return goalOptions
            .filter { it.focus in state.selectedGoalFocuses }
            .mapIndexed { index, option ->
                val description = if (option.focus == SessionGoalFocus.CUSTOM) {
                    state.customGoalDescription.ifBlank { "Custom session focus" }
                } else {
                    option.description
                }
                SessionGoal(
                    id = "native-goal-${index + 1}-${option.focus.name.lowercase()}",
                    focus = option.focus,
                    description = description,
                    source = SessionGoalSource.PRE_RACE_CHAT,
                    prioritizedActions = option.actions,
                )
            }
            .take(3)
    }

    private companion object {
        const val PREFS_NAME = "koru_session_prefs"
        const val KEY_TRACK_HUD_MODE = "track_hud_mode"
        const val REPLAY_TICK_MS = 120L
    }
}
