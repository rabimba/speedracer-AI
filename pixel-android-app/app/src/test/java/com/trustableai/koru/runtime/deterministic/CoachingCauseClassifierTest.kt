package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.DriverState
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.model.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CoachingCauseClassifierTest {
    @Test
    fun `coasting trigger produces cause-first coaching context`() {
        val cause = CoachingCauseClassifier.classify(
            triggerId = "repeated_coasting",
            action = CoachAction.COAST,
            frame = TelemetryFrame(
                timeSeconds = 1.0,
                latitude = 38.15950,
                longitude = -122.45720,
                speedMph = 68.0,
                throttle = 0.0,
                brake = 0.0,
                gLat = 0.1,
                gLong = 0.0,
                sourceMode = SessionMode.TELEMETRY,
            ),
            phase = CornerPhase.MID_CORNER,
            driverState = DriverState(
                skillLevel = SkillLevel.BEGINNER,
                cognitiveLoad = 0.35,
                inputSmoothness = 0.65,
                coastingRatio = 0.4,
            ),
            corner = null,
        )

        assertNotNull(cause)
        assertEquals("coasting_commitment_gap", cause?.id)
    }
}
