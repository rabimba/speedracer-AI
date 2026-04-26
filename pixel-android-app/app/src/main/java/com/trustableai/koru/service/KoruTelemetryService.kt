package com.trustableai.koru.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.trustableai.koru.R
import com.trustableai.koru.audio.CoachAudioDispatcher
import com.trustableai.koru.camera.VisionFeatureStore
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.bridgeValue
import com.trustableai.koru.runtime.EdgeRuntimeManager
import com.trustableai.koru.runtime.KoruRealtimeEngine
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.ModelAssetManager
import com.trustableai.koru.runtime.PhraseCatalog
import com.trustableai.koru.runtime.TrackCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class KoruTelemetryService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fusionEngine = TelemetryFusionEngine()
    private val telemetrySource: TelemetrySource = SyntheticTrackSource()

    private lateinit var audioDispatcher: CoachAudioDispatcher
    private lateinit var phraseCatalog: PhraseCatalog
    private lateinit var modelAssetManager: ModelAssetManager
    private lateinit var runtimeManager: EdgeRuntimeManager
    private lateinit var engine: KoruRealtimeEngine

    private var sessionJob: Job? = null
    private var foregroundStarted = false
    private var audioEnabled = true
    private var activeCoachId = "superaj"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        audioDispatcher = CoachAudioDispatcher(this)
        phraseCatalog = PhraseCatalog(this)
        modelAssetManager = ModelAssetManager(this)
        runtimeManager = EdgeRuntimeManager(this, modelAssetManager, phraseCatalog, activeCoachId)
        engine = KoruRealtimeEngine(TrackCatalog.thunderhillEast, phraseCatalog) {
            runtimeManager.currentReasoner()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sessionJson = intent.getStringExtra(EXTRA_SESSION_CONFIG) ?: "{}"
                serviceScope.launch { startSession(SessionConfig.fromJson(sessionJson)) }
            }

            ACTION_STOP -> {
                serviceScope.launch { stopSession() }
            }

            ACTION_SET_COACH -> {
                activeCoachId = intent.getStringExtra(EXTRA_COACH_ID) ?: activeCoachId
                engine.setActiveCoach(activeCoachId)
                runtimeManager.updateCoach(activeCoachId)
            }

            ACTION_SET_AUDIO -> {
                audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, audioEnabled)
                audioDispatcher.setEnabled(audioEnabled)
            }

            ACTION_REQUEST_STATUS -> {
                KoruSessionBus.tryEmitStatus(runtimeManager.currentStatus())
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        audioDispatcher.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startSession(config: SessionConfig) {
        stopActiveLoop()
        ensureForeground(getString(R.string.notification_content))

        activeCoachId = config.coachId
        audioEnabled = config.audioEnabled
        audioDispatcher.setEnabled(audioEnabled)
        phraseCatalog.ensureLoaded()
        engine.setActiveCoach(activeCoachId)
        runtimeManager.updateCoach(activeCoachId)

        val startingStatus = LiveBackendStatus(
            backend = RuntimeBackend.DETERMINISTIC,
            state = LiveBackendState.STARTING,
            detail = "Starting foreground telemetry service and selecting edge runtime backend",
            usesOnDeviceModel = false,
            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
        )
        KoruSessionBus.tryEmitStatus(startingStatus)

        val selectedBackend = runtimeManager.warmupPreferredBackend()
        KoruSessionBus.tryEmitStatus(selectedBackend)

        val track = TrackCatalog.fromName(config.trackName)
        updateNotification(selectedBackend.detail)
        sessionJob = serviceScope.launch {
            telemetrySource.start()
            var step = 0
            while (isActive) {
                val elapsedSeconds = step / 10.0
                val rawFrame = telemetrySource.nextFrame(step, track, elapsedSeconds)
                val frame = fusionEngine.fuse(rawFrame, VisionFeatureStore.latest.value)
                KoruSessionBus.tryEmitFrame(frame)
                engine.processFrame(frame).forEach { decision ->
                    KoruSessionBus.tryEmitDecision(decision)
                    if (audioEnabled) {
                        audioDispatcher.speak(decision.text, "${decision.path.bridgeValue()}-${decision.timestampMs}")
                    }
                }
                step += 1
                delay(100L)
            }
        }
    }

    private suspend fun stopSession() {
        stopActiveLoop()
        KoruSessionBus.tryEmitStatus(
            LiveBackendStatus(
                backend = RuntimeBackend.DETERMINISTIC,
                state = LiveBackendState.IDLE,
                detail = "Android live session stopped",
                usesOnDeviceModel = false,
                supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD),
            ),
        )
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private suspend fun stopActiveLoop() {
        sessionJob?.cancelAndJoin()
        sessionJob = null
        telemetrySource.stop()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(content: String) {
        if (!foregroundStarted) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun ensureForeground(content: String) {
        if (foregroundStarted) {
            updateNotification(content)
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification(content))
        foregroundStarted = true
    }

    private data class SessionConfig(
        val coachId: String,
        val audioEnabled: Boolean,
        val trackName: String,
        val sourceUrl: String?,
    ) {
        companion object {
            fun fromJson(json: String): SessionConfig {
                val root = JSONObject(json)
                return SessionConfig(
                    coachId = root.optString("coachId", "superaj"),
                    audioEnabled = root.optBoolean("audioEnabled", true),
                    trackName = root.optString("trackName", TrackCatalog.thunderhillEast.name),
                    sourceUrl = root.optString("sourceUrl").ifBlank { null },
                )
            }
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "koru_live_session"
        private const val NOTIFICATION_ID = 7301

        private const val ACTION_START = "com.trustableai.koru.action.START"
        private const val ACTION_STOP = "com.trustableai.koru.action.STOP"
        private const val ACTION_SET_COACH = "com.trustableai.koru.action.SET_COACH"
        private const val ACTION_SET_AUDIO = "com.trustableai.koru.action.SET_AUDIO"
        private const val ACTION_REQUEST_STATUS = "com.trustableai.koru.action.REQUEST_STATUS"

        private const val EXTRA_SESSION_CONFIG = "session_config"
        private const val EXTRA_COACH_ID = "coach_id"
        private const val EXTRA_AUDIO_ENABLED = "audio_enabled"

        fun startIntent(context: Context, sessionConfig: String): Intent {
            return Intent(context, KoruTelemetryService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_CONFIG, sessionConfig)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, KoruTelemetryService::class.java).setAction(ACTION_STOP)
        }

        fun setCoachIntent(context: Context, coachId: String): Intent {
            return Intent(context, KoruTelemetryService::class.java)
                .setAction(ACTION_SET_COACH)
                .putExtra(EXTRA_COACH_ID, coachId)
        }

        fun setAudioIntent(context: Context, enabled: Boolean): Intent {
            return Intent(context, KoruTelemetryService::class.java)
                .setAction(ACTION_SET_AUDIO)
                .putExtra(EXTRA_AUDIO_ENABLED, enabled)
        }

        fun requestStatusIntent(context: Context): Intent {
            return Intent(context, KoruTelemetryService::class.java).setAction(ACTION_REQUEST_STATUS)
        }
    }
}
