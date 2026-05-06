package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.runtime.TrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedforwardTimingPolicyTest {
    @Test
    fun `lookahead scales with speed and clamps to field window`() {
        val corner = TrackCatalog.sonomaRaceway.corners.first { it.id == 2 }

        assertEquals(120.0, FeedforwardTimingPolicy.lookaheadMeters(40.0, TrackCatalog.sonomaRaceway, corner), 0.01)
        assertEquals(120.0, FeedforwardTimingPolicy.lookaheadMeters(60.0, TrackCatalog.sonomaRaceway, corner), 0.01)
        assertTrue(FeedforwardTimingPolicy.lookaheadMeters(100.0, TrackCatalog.sonomaRaceway, corner) > 150.0)
    }

    @Test
    fun `sonoma turn ten and eleven get longer lead time`() {
        val turnTen = TrackCatalog.sonomaRaceway.corners.first { it.id == 910 }
        val turnTwo = TrackCatalog.sonomaRaceway.corners.first { it.id == 2 }

        assertTrue(
            FeedforwardTimingPolicy.lookaheadMeters(100.0, TrackCatalog.sonomaRaceway, turnTen) >
                FeedforwardTimingPolicy.lookaheadMeters(100.0, TrackCatalog.sonomaRaceway, turnTwo),
        )
    }

    @Test
    fun `spatial trigger fires while approaching but not after moving away`() {
        val trigger = SpatialAnticipationTrigger()
        val track = TrackCatalog.sonomaRaceway
        val approaching = telemetryAt(38.16400, -122.45500, 95.0)
        val insideWindow = telemetryAt(38.16272, -122.45500, 95.0)
        val movingAway = telemetryAt(38.16296, -122.45500, 95.0)

        assertNull(trigger.evaluate(approaching, track))
        assertNotNull(trigger.evaluate(insideWindow, track))
        assertNull(trigger.evaluate(movingAway, track))
    }

    private fun telemetryAt(lat: Double, lon: Double, speedMph: Double): TelemetryFrame {
        return TelemetryFrame(
            timeSeconds = 1.0,
            latitude = lat,
            longitude = lon,
            speedMph = speedMph,
            throttle = 0.0,
            brake = 0.0,
            gLat = 0.0,
            gLong = 0.0,
            sourceMode = SessionMode.TELEMETRY,
        )
    }
}
