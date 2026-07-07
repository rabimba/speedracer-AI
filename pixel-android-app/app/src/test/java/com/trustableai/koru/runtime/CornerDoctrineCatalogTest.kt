package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingObjective
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.DriverState
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.model.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerDoctrineCatalogTest {
    @Test
    fun `Turn 6 mid-corner coasting maps to maintenance throttle objective`() {
        val corner = TrackCatalog.sonomaRaceway.corners.first { it.id == 6 }

        val intent = CornerDoctrineCatalog.evaluateHotAction(
            track = TrackCatalog.sonomaRaceway,
            corner = corner,
            phase = CornerPhase.MID_CORNER,
            action = CoachAction.COAST,
            frame = TelemetryFrame(
                timeSeconds = 12.0,
                latitude = corner.lat,
                longitude = corner.lon,
                speedMph = 64.0,
                throttle = 0.0,
                brake = 0.0,
                gLat = 0.7,
                gLong = 0.0,
            ),
            driverState = DriverState(
                skillLevel = SkillLevel.INTERMEDIATE,
                cognitiveLoad = 0.2,
                inputSmoothness = 0.8,
                coastingRatio = 0.1,
            ),
            isEmergencyBrake = false,
        )

        assertFalse(intent.suppressed)
        assertEquals(CoachingObjective.MAINTENANCE_THROTTLE, intent.objective)
        assertEquals("maintenance_throttle_gap", intent.causeId)
    }

    @Test
    fun `Turn 11 emergency entry keeps P0 brake available`() {
        val corner = TrackCatalog.sonomaRaceway.corners.first { it.id == 11 }

        val intent = CornerDoctrineCatalog.evaluateHotAction(
            track = TrackCatalog.sonomaRaceway,
            corner = corner,
            phase = CornerPhase.BRAKE_ZONE,
            action = CoachAction.BRAKE,
            frame = TelemetryFrame(
                timeSeconds = 12.0,
                latitude = corner.entryLat ?: corner.lat,
                longitude = corner.entryLon ?: corner.lon,
                speedMph = 95.0,
                throttle = 0.0,
                brake = 0.0,
                gLat = 0.0,
                gLong = 0.0,
            ),
            driverState = DriverState(
                skillLevel = SkillLevel.INTERMEDIATE,
                cognitiveLoad = 0.2,
                inputSmoothness = 0.8,
                coastingRatio = 0.1,
            ),
            isEmergencyBrake = true,
        )

        assertFalse(intent.suppressed)
        assertEquals(CoachingObjective.BRAKE_ENTRY, intent.objective)
        assertEquals(0, intent.priority)
    }

    @Test
    fun `Thunderhill non emergency brake candidate is suppressed`() {
        val corner = TrackCatalog.thunderhillEast.corners.first { it.id == 10 }

        val intent = CornerDoctrineCatalog.evaluateHotAction(
            track = TrackCatalog.thunderhillEast,
            corner = corner,
            phase = CornerPhase.BRAKE_ZONE,
            action = CoachAction.BRAKE,
            frame = TelemetryFrame(
                timeSeconds = 12.0,
                latitude = corner.entryLat ?: corner.lat,
                longitude = corner.entryLon ?: corner.lon,
                speedMph = 82.0,
                throttle = 0.0,
                brake = 0.0,
                gLat = 0.0,
                gLong = 0.0,
            ),
            driverState = DriverState(
                skillLevel = SkillLevel.INTERMEDIATE,
                cognitiveLoad = 0.2,
                inputSmoothness = 0.8,
                coastingRatio = 0.1,
            ),
            isEmergencyBrake = false,
        )

        assertTrue(intent.suppressed)
    }

    @Test
    fun `Thunderhill Cyclone tactic uses maintenance objective instead of generic brake`() {
        val corner = TrackCatalog.thunderhillEast.corners.first { it.id == 7 }

        val intent = CornerDoctrineCatalog.edgeTactic(
            track = TrackCatalog.thunderhillEast,
            corner = corner,
            phase = CornerPhase.BRAKE_ZONE,
            frame = TelemetryFrame(
                timeSeconds = 12.0,
                latitude = corner.entryLat ?: corner.lat,
                longitude = corner.entryLon ?: corner.lon,
                speedMph = 76.0,
                throttle = 0.0,
                brake = 0.0,
                gLat = 0.0,
                gLong = 0.0,
            ),
            driverState = DriverState(
                skillLevel = SkillLevel.INTERMEDIATE,
                cognitiveLoad = 0.2,
                inputSmoothness = 0.8,
                coastingRatio = 0.1,
            ),
        )

        assertEquals(CoachAction.MAINTAIN, intent?.action)
        assertEquals(CoachingObjective.MAINTENANCE_THROTTLE, intent?.objective)
        assertEquals("thunderhill_t5_cyclone_platform", intent?.causeId)
    }
}
