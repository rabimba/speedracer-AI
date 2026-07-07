package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.runtime.TrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `Thunderhill catalog has full east course GPS anchors`() {
        val track = TrackCatalog.thunderhillEast

        assertEquals(15, track.corners.size)
        assertTrue(track.corners.all { it.entryLat != null && it.entryLon != null })
        assertTrue(track.corners.all { it.targetSpeed != null })
    }

    @Test
    fun `gps detector can classify Thunderhill Cyclone entry`() {
        val track = TrackCatalog.thunderhillEast
        val corner = track.corners.first { it.name.contains("Cyclone approach") }
        val detector = CornerPhaseDetector(track)

        val detection = detector.detect(
            TelemetryFrame(
                timeSeconds = 12.0,
                latitude = corner.entryLat ?: corner.lat,
                longitude = corner.entryLon ?: corner.lon,
                speedMph = 62.0,
                throttle = 0.0,
                brake = 8.0,
                gLat = 0.1,
                gLong = -0.1,
                sourceMode = SessionMode.TELEMETRY,
            ),
        )

        assertEquals(corner.id, detection.corner?.id)
        assertEquals(CornerPhase.TURN_IN, detection.phase)
    }

    @Test
    fun `gps detector does not reclassify Thunderhill apex movement as brake zone`() {
        val track = TrackCatalog.thunderhillEast
        val corner = track.corners.first { it.id == 7 }
        val detector = CornerPhaseDetector(track)

        detector.detect(
            TelemetryFrame(
                timeSeconds = 12.0,
                latitude = corner.entryLat ?: corner.lat,
                longitude = corner.entryLon ?: corner.lon,
                speedMph = 58.0,
                throttle = 0.0,
                brake = 8.0,
                gLat = 0.1,
                gLong = -0.1,
                sourceMode = SessionMode.TELEMETRY,
            ),
        )
        val detection = detector.detect(
            TelemetryFrame(
                timeSeconds = 13.0,
                latitude = corner.lat,
                longitude = corner.lon,
                speedMph = 54.0,
                throttle = 15.0,
                brake = 0.0,
                gLat = 0.65,
                gLong = 0.0,
                sourceMode = SessionMode.TELEMETRY,
            ),
        )

        assertEquals(corner.id, detection.corner?.id)
        assertEquals(CornerPhase.APEX, detection.phase)
    }
}
