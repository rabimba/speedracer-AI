package com.trustableai.koru.runtime

import android.content.Context
import android.util.Log
import com.trustableai.koru.audio.CoachAudioDispatcher
import com.trustableai.koru.camera.VisionFeatureStore
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.bridgeValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CameraDirectSessionController(context: Context) {
    private val tag = "KoruCameraDirect"
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioDispatcher = CoachAudioDispatcher(appContext)
    private val phraseCatalog = PhraseCatalog(appContext)
    private val modelAssetManager = ModelAssetManager(appContext)
    private val runtimeManager = EdgeRuntimeManager(appContext, modelAssetManager, phraseCatalog, "superaj")
    private val sessionRecorder = RecordedSessionRecorder(appContext)

    private var sessionJob: Job? = null
    private var activeCoachId = "superaj"
    private var audioEnabled = true
    private var engine: KoruRealtimeEngine? = null

    fun start(config: LiveSessionConfig) {
        scope.launch { startInternal(config) }
    }

    fun stop() {
        scope.launch { stopInternal() }
    }

    fun setActiveCoach(coachId: String) {
        activeCoachId = coachId
        engine?.setActiveCoach(coachId)
        runtimeManager.updateCoach(coachId)
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        audioDispatcher.setEnabled(enabled)
    }

    fun isRunning(): Boolean = sessionJob != null

    fun shutdown() {
        scope.launch { stopInternal() }
        scope.cancel()
        audioDispatcher.shutdown()
    }

    private suspend fun startInternal(config: LiveSessionConfig) {
        stopLoop()
        sessionRecorder.discard()
        activeCoachId = config.coachId
        audioEnabled = config.audioEnabled
        audioDispatcher.setEnabled(audioEnabled)
        phraseCatalog.ensureLoaded()
        runtimeManager.updateCoach(activeCoachId)
        Log.d(tag, "Starting camera-direct session for track=${config.trackName} coach=$activeCoachId")

        KoruSessionBus.tryEmitStatus(
            LiveBackendStatus(
                backend = com.trustableai.koru.model.RuntimeBackend.DETERMINISTIC,
                state = LiveBackendState.STARTING,
                detail = "Starting direct camera reasoning session on device",
                usesOnDeviceModel = false,
                supportedPaths = listOf(CoachingPath.EDGE),
            ),
        )

        val selectedBackend = runtimeManager.warmupPreferredBackend()
        KoruSessionBus.tryEmitStatus(
            selectedBackend.copy(
                state = LiveBackendState.STARTING,
                detail = "Edge runtime warmed. Waiting for camera lane frames",
                supportedPaths = listOf(CoachingPath.EDGE),
            ),
        )

        val track = TrackCatalog.fromName(config.trackName)
        val currentEngine = KoruRealtimeEngine(track, phraseCatalog) { runtimeManager.currentReasoner() }
        currentEngine.setActiveCoach(activeCoachId)
        engine = currentEngine

        sessionRecorder.start(SessionMode.CAMERA_DIRECT, track.name, activeCoachId)
        val startedAtMs = System.currentTimeMillis()
        var lastVisionTimestampMs = -1L
        var readyEmitted = false

        sessionJob = scope.launch {
            VisionFeatureStore.latest.collect { snapshot ->
                val currentSnapshot = snapshot ?: return@collect
                if (currentSnapshot.timestampMs == lastVisionTimestampMs) return@collect
                lastVisionTimestampMs = currentSnapshot.timestampMs

                if (!readyEmitted) {
                    readyEmitted = true
                    Log.d(tag, "First camera snapshot received, enabling direct reasoning")
                    KoruSessionBus.tryEmitStatus(
                        selectedBackend.copy(
                            state = LiveBackendState.READY,
                            detail = "Direct camera reasoning active on device",
                            supportedPaths = listOf(CoachingPath.EDGE),
                        ),
                    )
                }

                val frame = TelemetryFrame(
                    timeSeconds = (System.currentTimeMillis() - startedAtMs) / 1000.0,
                    latitude = 0.0,
                    longitude = 0.0,
                    speedMph = 0.0,
                    throttle = 0.0,
                    brake = 0.0,
                    gLat = 0.0,
                    gLong = 0.0,
                    sourceMode = SessionMode.CAMERA_DIRECT,
                    vision = currentSnapshot,
                )
                sessionRecorder.recordFrame(frame)
                KoruSessionBus.tryEmitFrame(frame)
                currentEngine.processFrame(frame).forEach { decision ->
                    Log.d(
                        tag,
                        "Live decision path=${decision.path.bridgeValue()} backend=${decision.backend.bridgeValue()} text=${decision.text}",
                    )
                    sessionRecorder.recordDecision(decision)
                    KoruSessionBus.tryEmitDecision(decision)
                    if (audioEnabled) {
                        audioDispatcher.speak(decision.text, "${decision.path.bridgeValue()}-${decision.timestampMs}")
                    }
                }
            }
        }
    }

    private suspend fun stopInternal() {
        stopLoop()
        completeRecordedSession()
        KoruSessionBus.tryEmitStatus(
            LiveBackendStatus(
                backend = com.trustableai.koru.model.RuntimeBackend.DETERMINISTIC,
                state = LiveBackendState.IDLE,
                detail = "Android camera feedback session stopped",
                usesOnDeviceModel = false,
                supportedPaths = listOf(CoachingPath.EDGE),
            ),
        )
        Log.d(tag, "Camera-direct session stopped")
    }

    private suspend fun stopLoop() {
        sessionJob?.cancelAndJoin()
        sessionJob = null
        engine = null
    }

    private fun completeRecordedSession(): RecordedSessionArtifact? {
        val artifact = sessionRecorder.finish() ?: return null
        Log.d(tag, "Saved camera-direct session ${artifact.id} frames=${artifact.summary.frameCount}")
        KoruSessionBus.tryEmitSavedSession(artifact)
        return artifact
    }
}
