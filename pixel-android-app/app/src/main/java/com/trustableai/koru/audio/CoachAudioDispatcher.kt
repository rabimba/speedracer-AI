package com.trustableai.koru.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class CoachAudioDispatcher(context: Context) {
    private var enabled = true
    private lateinit var textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
                textToSpeech.setSpeechRate(1.08f)
                textToSpeech.setPitch(0.92f)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            textToSpeech.stop()
        }
    }

    fun speak(text: String, utteranceId: String) {
        if (!enabled || text.isBlank()) return
        textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }
}
