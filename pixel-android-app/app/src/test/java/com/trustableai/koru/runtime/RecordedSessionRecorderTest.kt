package com.trustableai.koru.runtime

import com.trustableai.koru.model.AudioDispatchEvent
import com.trustableai.koru.model.AudioDispatchStatus
import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedSessionRecorderTest {
    @Test
    fun `schema v2 json includes audio events linked to decision ids`() {
        val recorder = RecordedSessionRecorder(persistArtifacts = false)
        recorder.start(SessionMode.TELEMETRY, "Sonoma Raceway", "superaj")
        val decision =
            CoachingDecision(
                path = CoachingPath.HOT,
                action = CoachAction.BRAKE,
                text = "Brake now",
                priority = 0,
                cornerPhase = CornerPhase.BRAKE_ZONE,
                timestampMs = 123L,
                backend = RuntimeBackend.DETERMINISTIC,
                id = "decision-p0",
            )
        recorder.recordDecision(decision)
        recorder.recordAudioEvent(
            AudioDispatchEvent(
                decisionId = decision.id,
                utteranceId = "hot-123",
                action = CoachAction.BRAKE,
                priority = 0,
                requestedAtMs = 123L,
                dispatchLatencyMs = 6L,
                status = AudioDispatchStatus.CLIP_STARTED,
            ),
        )

        val artifact = recorder.finish() ?: error("expected artifact")
        val json = recorder.artifactJson(artifact)
        val audioEvents = json.getJSONArray("audioEvents")

        assertEquals(2, json.getInt("schemaVersion"))
        assertEquals("decision-p0", json.getJSONArray("decisions").getJSONObject(0).getString("id"))
        assertEquals("decision-p0", audioEvents.getJSONObject(0).getString("decisionId"))
        assertEquals("CLIP_STARTED", audioEvents.getJSONObject(0).getString("status"))
        assertTrue(audioEvents.getJSONObject(0).getLong("dispatchLatencyMs") < 100L)
    }
}
