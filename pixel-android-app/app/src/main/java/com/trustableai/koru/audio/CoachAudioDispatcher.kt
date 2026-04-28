package com.trustableai.koru.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class CoachAudioDispatcher(context: Context) {
    private var enabled = true
    private lateinit var textToSpeech: TextToSpeech
    @Volatile private var initialized = false
    @Volatile private var currentPriority = Int.MAX_VALUE
    @Volatile private var currentUtteranceId: String? = null

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                textToSpeech.language = Locale.US
                textToSpeech.setSpeechRate(1.08f)
                textToSpeech.setPitch(0.92f)
                textToSpeech.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            currentUtteranceId = utteranceId
                        }

                        override fun onDone(utteranceId: String?) {
                            clearIfCurrent(utteranceId)
                        }

                        override fun onError(utteranceId: String?) {
                            clearIfCurrent(utteranceId)
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            clearIfCurrent(utteranceId)
                        }
                    },
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            resetSpeechState()
            textToSpeech.stop()
        }
    }

    @Synchronized
    fun speak(text: String, utteranceId: String, priority: Int) {
        if (!enabled || text.isBlank() || !initialized) return
        val interrupt = textToSpeech.isSpeaking && priority < currentPriority
        if (interrupt) {
            textToSpeech.stop()
        }
        currentPriority = priority
        currentUtteranceId = utteranceId
        textToSpeech.speak(
            text,
            if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            utteranceId,
        )
    }

    fun shutdown() {
        resetSpeechState()
        textToSpeech.stop()
        textToSpeech.shutdown()
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
    }
}
