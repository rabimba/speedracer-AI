package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionMatrixTest {
    @Test
    fun `forEachAction matches evaluateAll ordering`() {
        val frame = TelemetryFrame(
            timeSeconds = 1.0,
            latitude = 38.16120,
            longitude = -122.45330,
            speedMph = 92.0,
            throttle = 0.0,
            brake = 84.0,
            gLat = 1.1,
            gLong = -1.3,
            sourceMode = SessionMode.TELEMETRY,
        )

        val collected = mutableListOf<CoachAction>()
        DecisionMatrix.forEachAction(frame) { collected += it }

        assertEquals(DecisionMatrix.evaluateAll(frame), collected)
    }
}
