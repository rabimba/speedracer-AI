package com.trustableai.koru.audio

import android.speech.tts.TextToSpeech
import com.trustableai.koru.model.AudioDispatchEvent
import com.trustableai.koru.model.AudioDispatchStatus
import com.trustableai.koru.model.CoachAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachAudioDispatcherTest {
    @Test
    fun `p0 spoken clip dispatch records clip event inside budget`() {
        val clipPlayer = FakeClipPlayer(playable = true)
        val speechEngine = FakeSpeechEngine(initialized = true)
        val dispatcher = CoachAudioDispatcher(clipPlayer, speechEngine)
        val events = mutableListOf<AudioDispatchEvent>()

        dispatcher.speak(
            text = "Brake now",
            utteranceId = "hot-1",
            priority = 0,
            action = CoachAction.BRAKE,
            decisionId = "decision-1",
            onAudioEvent = events::add,
        )

        assertEquals(listOf("brake_now"), clipPlayer.playedClips)
        assertFalse(speechEngine.speakCalled)
        assertEquals(AudioDispatchStatus.CLIP_STARTED, events.single().status)
        assertEquals("brake_now", events.single().clipName)
        assertEquals("decision-1", events.single().decisionId)
        assertTrue(events.single().dispatchLatencyMs < 100L)
    }

    @Test
    fun `p0 missing clip falls back to flushed tts and records start latency`() {
        val clipPlayer = FakeClipPlayer(playable = false)
        val speechEngine = FakeSpeechEngine(initialized = true)
        val dispatcher = CoachAudioDispatcher(clipPlayer, speechEngine)
        val events = mutableListOf<AudioDispatchEvent>()

        dispatcher.speak(
            text = "Both feet in",
            utteranceId = "hot-2",
            priority = 0,
            action = CoachAction.OVERSTEER_RECOVERY,
            decisionId = "decision-2",
            onAudioEvent = events::add,
        )
        speechEngine.fireStart("hot-2")

        assertEquals(listOf("both_feet_in"), clipPlayer.playedClips)
        assertTrue(speechEngine.speakCalled)
        assertEquals(TextToSpeech.QUEUE_FLUSH, speechEngine.lastQueueMode)
        assertEquals(AudioDispatchStatus.TTS_QUEUED, events.first().status)
        assertEquals("clip_unavailable:both_feet_in", events.first().fallbackReason)
        assertEquals(AudioDispatchStatus.TTS_STARTED, events.last().status)
        assertEquals("decision-2", events.last().decisionId)
        assertTrue((events.last().ttsStartLatencyMs ?: Long.MAX_VALUE) < 100L)
    }

    @Test
    fun `second p0 cue during active playback is suppressed`() {
        val clipPlayer = FakeClipPlayer(playable = true, staysPlaying = true)
        val speechEngine = FakeSpeechEngine(initialized = true)
        val dispatcher = CoachAudioDispatcher(clipPlayer, speechEngine)
        val events = mutableListOf<AudioDispatchEvent>()

        dispatcher.speak(
            text = "Brake now",
            utteranceId = "hot-1",
            priority = 0,
            action = CoachAction.BRAKE,
            decisionId = "decision-1",
            onAudioEvent = events::add,
        )
        dispatcher.speak(
            text = "Brake now",
            utteranceId = "hot-2",
            priority = 0,
            action = CoachAction.BRAKE,
            decisionId = "decision-2",
            onAudioEvent = events::add,
        )

        assertEquals(1, clipPlayer.playedClips.size)
        assertEquals(AudioDispatchStatus.CLIP_STARTED, events.first().status)
        assertEquals(AudioDispatchStatus.BUSY, events.last().status)
        assertEquals("repeat_while_playing", events.last().fallbackReason)
    }

    @Test
    fun `non p0 cue during active playback is suppressed`() {
        val clipPlayer = FakeClipPlayer(playable = true, staysPlaying = true)
        val speechEngine = FakeSpeechEngine(initialized = true)
        val dispatcher = CoachAudioDispatcher(clipPlayer, speechEngine)
        val events = mutableListOf<AudioDispatchEvent>()

        dispatcher.speak(
            text = "Brake now",
            utteranceId = "hot-1",
            priority = 0,
            action = CoachAction.BRAKE,
            decisionId = "decision-1",
            onAudioEvent = events::add,
        )
        dispatcher.speak(
            text = "Commit harder",
            utteranceId = "hot-2",
            priority = 2,
            action = CoachAction.COMMIT,
            decisionId = "decision-2",
            onAudioEvent = events::add,
        )

        assertFalse(speechEngine.speakCalled)
        assertEquals(AudioDispatchStatus.BUSY, events.last().status)
        assertEquals("playback_active", events.last().fallbackReason)
    }

    @Test
    fun `session audio check plays coach ready fixed clip`() {
        val clipPlayer = FakeClipPlayer(playable = true)
        val speechEngine = FakeSpeechEngine(initialized = false)
        val dispatcher = CoachAudioDispatcher(clipPlayer, speechEngine)
        val events = mutableListOf<AudioDispatchEvent>()

        dispatcher.playSessionClip("coach_ready", utteranceId = "session-ready", onAudioEvent = events::add)

        assertEquals(listOf("coach_ready"), clipPlayer.playedClips)
        assertEquals(AudioDispatchStatus.CLIP_STARTED, events.single().status)
        assertEquals("SESSION", events.single().scope.name)
        assertEquals("coach_ready", events.single().clipName)
        assertFalse(speechEngine.speakCalled)
    }

    private class FakeClipPlayer(
        private val playable: Boolean,
        private val staysPlaying: Boolean = false,
    ) : SafetyClipPlayer {
        val playedClips = mutableListOf<String>()
        override var isPlaying: Boolean = false
            private set

        override fun playClip(resourceName: String): Boolean {
            playedClips += resourceName
            isPlaying = staysPlaying
            return playable
        }

        override fun stopClip() {
            isPlaying = false
        }

        override fun shutdown() = Unit
    }

    private class FakeSpeechEngine(
        override val initialized: Boolean,
    ) : CoachSpeechEngine {
        override var isSpeaking: Boolean = false
        var speakCalled = false
        var lastQueueMode = -1
        private var onStart: (String) -> Unit = {}

        override fun setCallbacks(onStart: (String) -> Unit, onFinished: (String?) -> Unit) {
            this.onStart = onStart
        }

        override fun stop() {
            isSpeaking = false
        }

        override fun speak(text: String, queueMode: Int, utteranceId: String) {
            speakCalled = true
            lastQueueMode = queueMode
            isSpeaking = true
        }

        override fun shutdown() = Unit

        fun fireStart(utteranceId: String) {
            onStart(utteranceId)
        }
    }
}
