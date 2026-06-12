package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.runtime.TrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class CornerPhaseDetectorTest {
    @Test
    fun `gps detector can classify turn-in before apex`() {
        val detector = CornerPhaseDetector(TrackCatalog.sonomaRaceway)

        val detection = detector.detect(
            TelemetryFrame(
                timeSeconds = 12.0,
                latitude = 38.16080,
                longitude = -122.45720,
                speedMph = 62.0,
                throttle = 0.0,
                brake = 10.0,
                gLat = 0.1,
                gLong = -0.1,
                sourceMode = SessionMode.TELEMETRY,
            ),
        )

        assertEquals(3, detection.corner?.id)
        assertEquals(CornerPhase.TURN_IN, detection.phase)
    }
}
