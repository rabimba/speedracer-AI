package com.trustableai.koru.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.trustableai.koru.model.AudioDispatchEvent
import com.trustableai.koru.model.AudioDispatchScope
import com.trustableai.koru.model.AudioDispatchStatus
import com.trustableai.koru.model.CoachAction
import java.util.Locale

class CoachAudioDispatcher internal constructor(
    private val clipPlayer: SafetyClipPlayer,
    private val speechEngine: CoachSpeechEngine,
) {
    constructor(context: Context) : this(
        clipPlayer = AndroidSafetyClipPlayer(context.applicationContext),
        speechEngine = AndroidCoachSpeechEngine(context.applicationContext),
    )

    private val logTag = "KoruAudioDispatcher"
    private var enabled = true
    private val pendingSpeech = mutableMapOf<String, PendingSpeech>()
    @Volatile private var currentPriority = Int.MAX_VALUE
    @Volatile private var currentUtteranceId: String? = null
    @Volatile private var lastP0Action: CoachAction? = null

    init {
        speechEngine.setCallbacks(
            onStart = ::handleSpeechStart,
            onFinished = ::clearIfCurrent,
        )
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            resetSpeechState()
            speechEngine.stop()
        }
    }

    @Synchronized
    fun playSessionClip(
        clipName: String,
        utteranceId: String = "session-$clipName-${System.currentTimeMillis()}",
        onAudioEvent: (AudioDispatchEvent) -> Unit = {},
    ) {
        val requestedAtMs = System.currentTimeMillis()
        if (!enabled) {
            onAudioEvent(
                event(
                    decisionId = utteranceId,
                    utteranceId = utteranceId,
                    action = null,
                    priority = 0,
                    requestedAtMs = requestedAtMs,
                    status = AudioDispatchStatus.DISABLED,
                    fallbackReason = "audio_disabled",
                    scope = AudioDispatchScope.SESSION,
                    clipName = clipName,
                ),
            )
            return
        }
        if (isPlaybackActive()) {
            onAudioEvent(
                event(
                    decisionId = utteranceId,
                    utteranceId = utteranceId,
                    action = null,
                    priority = 0,
                    requestedAtMs = requestedAtMs,
                    status = AudioDispatchStatus.BUSY,
                    fallbackReason = "playback_active",
                    scope = AudioDispatchScope.SESSION,
                    clipName = clipName,
                ),
            )
            return
        }
        speechEngine.stop()
        clipPlayer.stopClip()
        resetSpeechState()
        if (clipPlayer.playClip(clipName)) {
            onAudioEvent(
                event(
                    decisionId = utteranceId,
                    utteranceId = utteranceId,
                    action = null,
                    priority = 0,
                    requestedAtMs = requestedAtMs,
                    status = AudioDispatchStatus.CLIP_STARTED,
                    scope = AudioDispatchScope.SESSION,
                    clipName = clipName,
                ),
            )
            return
        }
        enqueueSpeech(
            text = sessionClipFallbackText(clipName),
            utteranceId = utteranceId,
            priority = 0,
            action = null,
            decisionId = utteranceId,
            requestedAtMs = requestedAtMs,
            fallbackReason = "clip_unavailable:$clipName",
            forceFlush = true,
            scope = AudioDispatchScope.SESSION,
            clipName = clipName,
            onAudioEvent = onAudioEvent,
        )
    }

    @Synchronized
    fun speak(
        text: String,
        utteranceId: String,
        priority: Int,
        action: CoachAction? = null,
        decisionId: String = utteranceId,
        onAudioEvent: (AudioDispatchEvent) -> Unit = {},
    ) {
        val requestedAtMs = System.currentTimeMillis()
        if (!enabled || text.isBlank()) {
            onAudioEvent(
                event(
                    decisionId = decisionId,
                    utteranceId = utteranceId,
                    action = action,
                    priority = priority,
                    requestedAtMs = requestedAtMs,
                    status = AudioDispatchStatus.DISABLED,
                    fallbackReason = if (!enabled) "audio_disabled" else "blank_text",
                    scope = AudioDispatchScope.DECISION,
                ),
            )
            return
        }

        val fixedClipName = coachingClipName(action, text)
        if (fixedClipName != null && priority <= FIXED_CLIP_PRIORITY_MAX) {
            if (isPlaybackActive() && priority > 0) {
                onAudioEvent(
                    event(
                        decisionId = decisionId,
                        utteranceId = utteranceId,
                        action = action,
                        priority = priority,
                        requestedAtMs = requestedAtMs,
                        status = AudioDispatchStatus.BUSY,
                        fallbackReason = "playback_active",
                        clipName = fixedClipName,
                    ),
                )
                return
            }
            if (isPlaybackActive() && action == lastP0Action) {
                onAudioEvent(
                    event(
                        decisionId = decisionId,
                        utteranceId = utteranceId,
                        action = action,
                        priority = priority,
                        requestedAtMs = requestedAtMs,
                        status = AudioDispatchStatus.BUSY,
                        fallbackReason = "repeat_while_playing",
                        clipName = fixedClipName,
                    ),
                )
                return
            }
            speechEngine.stop()
            clipPlayer.stopClip()
            resetSpeechState()
            if (clipPlayer.playClip(fixedClipName)) {
                lastP0Action = action
                val dispatchEvent =
                    event(
                        decisionId = decisionId,
                        utteranceId = utteranceId,
                        action = action,
                        priority = priority,
                        requestedAtMs = requestedAtMs,
                        status = AudioDispatchStatus.CLIP_STARTED,
                        clipName = fixedClipName,
                    )
                onAudioEvent(dispatchEvent)
                logDebug("P0 clip dispatched utterance=$utteranceId latencyMs=${dispatchEvent.dispatchLatencyMs}")
                return
            }

            enqueueSpeech(
                text = text,
                utteranceId = utteranceId,
                priority = priority,
                action = action,
                decisionId = decisionId,
                requestedAtMs = requestedAtMs,
                fallbackReason = "clip_unavailable:$fixedClipName",
                forceFlush = true,
                scope = AudioDispatchScope.DECISION,
                clipName = fixedClipName,
                onAudioEvent = onAudioEvent,
            )
            return
        }

        if (isPlaybackActive()) {
            onAudioEvent(
                event(
                    decisionId = decisionId,
                    utteranceId = utteranceId,
                    action = action,
                    priority = priority,
                    requestedAtMs = requestedAtMs,
                    status = AudioDispatchStatus.BUSY,
                    fallbackReason = "playback_active",
                    scope = AudioDispatchScope.DECISION,
                ),
            )
            return
        }

        enqueueSpeech(
            text = text,
            utteranceId = utteranceId,
            priority = priority,
            action = action,
            decisionId = decisionId,
            requestedAtMs = requestedAtMs,
            fallbackReason = null,
            forceFlush = false,
            scope = AudioDispatchScope.DECISION,
            clipName = null,
            onAudioEvent = onAudioEvent,
        )
    }

    fun shutdown() {
        resetSpeechState()
        speechEngine.stop()
        speechEngine.shutdown()
        clipPlayer.shutdown()
    }

    @Synchronized
    private fun enqueueSpeech(
        text: String,
        utteranceId: String,
        priority: Int,
        action: CoachAction?,
        decisionId: String,
        requestedAtMs: Long,
        fallbackReason: String?,
        forceFlush: Boolean,
        scope: AudioDispatchScope,
        clipName: String?,
        onAudioEvent: (AudioDispatchEvent) -> Unit,
    ) {
        if (!speechEngine.initialized) {
            onAudioEvent(
                event(
                    decisionId = decisionId,
                    utteranceId = utteranceId,
                    action = action,
                    priority = priority,
                    requestedAtMs = requestedAtMs,
                    status = AudioDispatchStatus.TTS_UNAVAILABLE,
                    fallbackReason = fallbackReason ?: "tts_uninitialized",
                    scope = scope,
                    clipName = clipName,
                ),
            )
            return
        }

        val interrupt = speechEngine.isSpeaking && priority < currentPriority
        val queueMode = if (forceFlush || interrupt || priority == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            speechEngine.stop()
        }
        currentPriority = priority
        currentUtteranceId = utteranceId
        pendingSpeech[utteranceId] =
            PendingSpeech(
                decisionId = decisionId,
                utteranceId = utteranceId,
                action = action,
                priority = priority,
                requestedAtMs = requestedAtMs,
                fallbackReason = fallbackReason,
                scope = scope,
                clipName = clipName,
                onAudioEvent = onAudioEvent,
            )
        speechEngine.speak(text, queueMode, utteranceId)
        val queuedEvent =
            event(
                decisionId = decisionId,
                utteranceId = utteranceId,
                action = action,
                priority = priority,
                requestedAtMs = requestedAtMs,
                status = AudioDispatchStatus.TTS_QUEUED,
                fallbackReason = fallbackReason,
                scope = scope,
                clipName = clipName,
            )
        onAudioEvent(queuedEvent)
        logDebug("TTS queued utterance=$utteranceId latencyMs=${queuedEvent.dispatchLatencyMs}")
    }

    @Synchronized
    private fun handleSpeechStart(utteranceId: String) {
        currentUtteranceId = utteranceId
        val pending = pendingSpeech.remove(utteranceId) ?: return
        val nowMs = System.currentTimeMillis()
        pending.onAudioEvent(
            AudioDispatchEvent(
                decisionId = pending.decisionId,
                utteranceId = pending.utteranceId,
                action = pending.action,
                priority = pending.priority,
                requestedAtMs = pending.requestedAtMs,
                dispatchLatencyMs = nowMs - pending.requestedAtMs,
                ttsStartLatencyMs = nowMs - pending.requestedAtMs,
                status = AudioDispatchStatus.TTS_STARTED,
                fallbackReason = pending.fallbackReason,
                scope = pending.scope,
                clipName = pending.clipName,
            ),
        )
        logDebug("TTS started utterance=$utteranceId latencyMs=${nowMs - pending.requestedAtMs}")
    }

    @Synchronized
    private fun clearIfCurrent(utteranceId: String?) {
        if (utteranceId == null || utteranceId == currentUtteranceId) {
            resetSpeechState()
        }
    }

    @Synchronized
    private fun resetSpeechState() {
        currentPriority = Int.MAX_VALUE
        currentUtteranceId = null
        lastP0Action = null
        pendingSpeech.clear()
    }

    private fun isPlaybackActive(): Boolean = clipPlayer.isPlaying || speechEngine.isSpeaking

    private fun event(
        decisionId: String,
        utteranceId: String,
        action: CoachAction?,
        priority: Int,
        requestedAtMs: Long,
        status: AudioDispatchStatus,
        fallbackReason: String? = null,
        scope: AudioDispatchScope = AudioDispatchScope.DECISION,
        clipName: String? = null,
    ): AudioDispatchEvent {
        return AudioDispatchEvent(
            decisionId = decisionId,
            utteranceId = utteranceId,
            action = action,
            priority = priority,
            requestedAtMs = requestedAtMs,
            dispatchLatencyMs = System.currentTimeMillis() - requestedAtMs,
            status = status,
            fallbackReason = fallbackReason,
            scope = scope,
            clipName = clipName,
        )
    }

    private fun coachingClipName(action: CoachAction?, text: String): String? {
        return when {
            action == CoachAction.OVERSTEER_RECOVERY -> "both_feet_in"
            action == CoachAction.BRAKE || action == CoachAction.SPIKE_BRAKE || action == CoachAction.THRESHOLD -> "brake_now"
            action == CoachAction.WAIT || action == CoachAction.EARLY_THROTTLE -> "wait"
            action == CoachAction.THROTTLE || action == CoachAction.FULL_THROTTLE || action == CoachAction.COMMIT -> "throttle"
            action == CoachAction.PUSH || action == CoachAction.HUSTLE -> "push"
            action == CoachAction.LIFT_MID_CORNER -> "lift"
            action == CoachAction.COAST -> "coast"
            action == CoachAction.STABILIZE || action == CoachAction.MAINTAIN || action == CoachAction.COGNITIVE_OVERLOAD -> "smooth"
            text.contains("both feet", ignoreCase = true) -> "both_feet_in"
            text.contains("brake", ignoreCase = true) -> "brake_now"
            text.contains("eyes", ignoreCase = true) -> "eyes_up"
            else -> null
        }
    }

    private fun sessionClipFallbackText(clipName: String): String {
        return when (clipName) {
            "coach_ready" -> "Coach ready"
            "brake_now" -> "Brake now"
            "wait" -> "Wait"
            "throttle" -> "Throttle"
            "eyes_up" -> "Eyes up"
            "smooth" -> "Smooth"
            "push" -> "Push"
            "lift" -> "Lift"
            "coast" -> "Coast"
            "both_feet_in" -> "Both feet in"
            else -> clipName.replace('_', ' ')
        }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(logTag, message) }
    }

    private data class PendingSpeech(
        val decisionId: String,
        val utteranceId: String,
        val action: CoachAction?,
        val priority: Int,
        val requestedAtMs: Long,
        val fallbackReason: String?,
        val scope: AudioDispatchScope,
        val clipName: String?,
        val onAudioEvent: (AudioDispatchEvent) -> Unit,
    )

    private companion object {
        private const val FIXED_CLIP_PRIORITY_MAX = 3
    }
}

internal interface SafetyClipPlayer {
    val isPlaying: Boolean

    fun playClip(resourceName: String): Boolean

    fun stopClip()

    fun shutdown()
}

internal interface CoachSpeechEngine {
    val initialized: Boolean
    val isSpeaking: Boolean

    fun setCallbacks(onStart: (String) -> Unit, onFinished: (String?) -> Unit)

    fun stop()

    fun speak(text: String, queueMode: Int, utteranceId: String)

    fun shutdown()
}

private class AndroidSafetyClipPlayer(context: Context) : SafetyClipPlayer {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var activeStreamId = 0
    @Volatile private var fallbackToneActive = false
    private var clearPlayingRunnable: Runnable? = null
    private val toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
    override val isPlaying: Boolean
        get() = activeStreamId != 0 || fallbackToneActive
    private val soundPool =
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
    private val loadedSoundIds = mutableSetOf<Int>()
    private val safetyClipIds = mutableMapOf<String, Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSoundIds.add(sampleId)
        }
        FIXED_CLIP_NAMES.forEach(::loadSafetyClip)
    }

    override fun playClip(resourceName: String): Boolean {
        val soundId = safetyClipIds[resourceName]
        if (soundId == null || soundId !in loadedSoundIds) return playFallbackTone(resourceName)
        stopClip()
        val streamId = soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
        if (streamId == 0) return playFallbackTone(resourceName)
        activeStreamId = streamId
        clearPlayingRunnable?.let { handler.removeCallbacks(it) }
        val clearRunnable = Runnable { activeStreamId = 0 }
        clearPlayingRunnable = clearRunnable
        handler.postDelayed(clearRunnable, CLIP_PLAYBACK_MS)
        return true
    }

    override fun stopClip() {
        clearPlayingRunnable?.let { handler.removeCallbacks(it) }
        clearPlayingRunnable = null
        if (activeStreamId != 0) {
            soundPool.stop(activeStreamId)
            activeStreamId = 0
        }
        if (fallbackToneActive) {
            toneGenerator?.stopTone()
            fallbackToneActive = false
        }
    }

    override fun shutdown() {
        stopClip()
        soundPool.release()
        toneGenerator?.release()
    }

    private fun loadSafetyClip(resourceName: String) {
        val resourceId = appContext.resources.getIdentifier(rawResourceName(resourceName), "raw", appContext.packageName)
        if (resourceId == 0) return
        safetyClipIds[resourceName] = soundPool.load(appContext, resourceId, 1)
    }

    private fun rawResourceName(resourceName: String): String {
        return when (resourceName) {
            "brake_now" -> "p0_brake_now"
            "both_feet_in" -> "p0_both_feet_in"
            else -> resourceName
        }
    }

    private fun playFallbackTone(resourceName: String): Boolean {
        val generator = toneGenerator ?: return false
        stopClip()
        val durationMs = when (resourceName) {
            "coach_ready" -> 360
            "brake_now", "both_feet_in" -> 650
            else -> 420
        }
        val tone = when (resourceName) {
            "coach_ready" -> ToneGenerator.TONE_PROP_ACK
            "brake_now", "both_feet_in" -> ToneGenerator.TONE_PROP_NACK
            "wait", "coast", "lift" -> ToneGenerator.TONE_PROP_BEEP2
            else -> ToneGenerator.TONE_PROP_PROMPT
        }
        val started = generator.startTone(tone, durationMs)
        if (!started) return false
        fallbackToneActive = true
        clearPlayingRunnable?.let { handler.removeCallbacks(it) }
        val clearRunnable = Runnable { fallbackToneActive = false }
        clearPlayingRunnable = clearRunnable
        handler.postDelayed(clearRunnable, durationMs.toLong() + 80L)
        return true
    }

    companion object {
        private const val CLIP_PLAYBACK_MS = 1800L
        private val FIXED_CLIP_NAMES = listOf(
            "coach_ready",
            "brake_now",
            "wait",
            "throttle",
            "eyes_up",
            "smooth",
            "push",
            "lift",
            "coast",
            "both_feet_in",
        )
    }
}

private class AndroidCoachSpeechEngine(context: Context) : CoachSpeechEngine {
    private val appContext = context.applicationContext
    private lateinit var textToSpeech: TextToSpeech
    private var onStart: (String) -> Unit = {}
    private var onFinished: (String?) -> Unit = {}
    @Volatile private var ready = false

    override val initialized: Boolean
        get() = ready

    override val isSpeaking: Boolean
        get() = ready && textToSpeech.isSpeaking

    init {
        textToSpeech =
            TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    textToSpeech.language = Locale.US
                    textToSpeech.setSpeechRate(1.08f)
                    textToSpeech.setPitch(0.92f)
                    textToSpeech.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                utteranceId?.let(onStart)
                            }

                            override fun onDone(utteranceId: String?) {
                                onFinished(utteranceId)
                            }

                            override fun onError(utteranceId: String?) {
                                onFinished(utteranceId)
                            }

                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                onFinished(utteranceId)
                            }
                        },
                    )
                }
            }
    }

    override fun setCallbacks(onStart: (String) -> Unit, onFinished: (String?) -> Unit) {
        this.onStart = onStart
        this.onFinished = onFinished
    }

    override fun stop() {
        if (ready) textToSpeech.stop()
    }

    override fun speak(text: String, queueMode: Int, utteranceId: String) {
        if (!ready) return
        textToSpeech.speak(text, queueMode, null, utteranceId)
    }

    override fun shutdown() {
        if (ready) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        ready = false
    }
}
