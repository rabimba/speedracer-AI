package com.trustableai.koru.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.trustableai.koru.model.AudioDispatchEvent
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
                ),
            )
            return
        }

        if (priority == 0) {
            if (isPlaybackActive() && action == lastP0Action) {
                onAudioEvent(
                    event(
                        decisionId = decisionId,
                        utteranceId = utteranceId,
                        action = action,
                        priority = priority,
                        requestedAtMs = requestedAtMs,
                        status = AudioDispatchStatus.BUSY,
                        fallbackReason = "p0_repeat_while_playing",
                    ),
                )
                return
            }
            speechEngine.stop()
            clipPlayer.stopClip()
            resetSpeechState()
            val clipName = safetyClipName(action, text)
            if (clipPlayer.playClip(clipName)) {
                lastP0Action = action
                val dispatchEvent =
                    event(
                        decisionId = decisionId,
                        utteranceId = utteranceId,
                        action = action,
                        priority = priority,
                        requestedAtMs = requestedAtMs,
                        status = AudioDispatchStatus.CLIP_STARTED,
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
                fallbackReason = "clip_unavailable:$clipName",
                forceFlush = true,
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
        )
    }

    private fun safetyClipName(action: CoachAction?, text: String): String {
        return when {
            action == CoachAction.OVERSTEER_RECOVERY -> "p0_both_feet_in"
            action == CoachAction.BRAKE -> "p0_brake_now"
            text.contains("both feet", ignoreCase = true) -> "p0_both_feet_in"
            else -> "p0_brake_now"
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
        val onAudioEvent: (AudioDispatchEvent) -> Unit,
    )
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
    private var clearPlayingRunnable: Runnable? = null
    override val isPlaying: Boolean
        get() = activeStreamId != 0
    private val soundPool =
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
        loadSafetyClip("p0_brake_now")
        loadSafetyClip("p0_both_feet_in")
    }

    override fun playClip(resourceName: String): Boolean {
        val soundId = safetyClipIds[resourceName]
        if (soundId == null || soundId !in loadedSoundIds) return false
        stopClip()
        val streamId = soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
        if (streamId == 0) return false
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
    }

    override fun shutdown() {
        stopClip()
        soundPool.release()
    }

    private fun loadSafetyClip(resourceName: String) {
        val resourceId = appContext.resources.getIdentifier(resourceName, "raw", appContext.packageName)
        if (resourceId == 0) return
        safetyClipIds[resourceName] = soundPool.load(appContext, resourceId, 1)
    }

    companion object {
        private const val CLIP_PLAYBACK_MS = 1800L
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
