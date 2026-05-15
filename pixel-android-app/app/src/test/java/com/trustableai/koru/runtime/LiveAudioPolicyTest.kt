package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioPolicyTest {
    @Test
    fun `no live data suppresses even priority zero audio`() {
        val gate = LiveAudioPolicy.shouldSpeak(
            frame = trustedFrame(
                sourceHealth = TelemetrySourceHealth(
                    status = "No real telemetry source available",
                    motionConnected = false,
                    fallbackStage = "no_live_data",
                ),
            ),
            decision = decision(CoachAction.BRAKE, priority = 0),
        )

        assertFalse(gate.allowSpeak)
        assertEquals("no_live_data", gate.reason)
    }

    @Test
    fun `phone only suppresses throttle dependent cues but keeps safety cues`() {
        val frame = trustedFrame(
            sourceHealth = TelemetrySourceHealth(
                status = "Phone GPS/IMU live",
                motionSource = "phone",
                motionConnected = true,
                motionFixGood = true,
                fallbackStage = "phone_only",
            ),
        )

        val throttleGate = LiveAudioPolicy.shouldSpeak(frame, decision(CoachAction.THROTTLE, priority = 1))
        val brakeGate = LiveAudioPolicy.shouldSpeak(frame, decision(CoachAction.BRAKE, priority = 0))

        assertFalse(throttleGate.allowSpeak)
        assertEquals("phone_only_throttle_dependent", throttleGate.reason)
        assertTrue(brakeGate.allowSpeak)
    }

    private fun trustedFrame(sourceHealth: TelemetrySourceHealth): TelemetryFrame {
        return TelemetryFrame(
            timeSeconds = 1.0,
            latitude = 38.16272,
            longitude = -122.455,
            speedMph = 64.0,
            throttle = 20.0,
            brake = 0.0,
            gLat = 0.3,
            gLong = 0.1,
            sourceHealth = sourceHealth,
        )
    }

    private fun decision(action: CoachAction, priority: Int): CoachingDecision {
        return CoachingDecision(
            path = CoachingPath.HOT,
            action = action,
            text = action.name,
            priority = priority,
            cornerPhase = CornerPhase.STRAIGHT,
            timestampMs = 1_000L,
            backend = RuntimeBackend.DETERMINISTIC,
        )
    }
}
