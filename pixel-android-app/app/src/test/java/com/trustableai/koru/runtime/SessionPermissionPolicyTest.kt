package com.trustableai.koru.runtime

import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPermissionPolicyTest {
    @Test
    fun `AiM CAN telemetry starts without fallback permission prompts`() {
        val config = config(SessionMode.TELEMETRY, TelemetrySourceKind.AIM_CAN_USB)

        assertFalse(SessionPermissionPolicy.requiresBluetooth(config))
        assertFalse(SessionPermissionPolicy.requiresFineLocation(config))
        assertFalse(SessionPermissionPolicy.requiresLocationForegroundType(config))
    }

    @Test
    fun `CAN interface check starts without Bluetooth or location`() {
        val config = config(SessionMode.CAN_INTERFACE_CHECK, TelemetrySourceKind.AIM_CAN_USB)

        assertFalse(SessionPermissionPolicy.requiresBluetooth(config))
        assertFalse(SessionPermissionPolicy.requiresFineLocation(config))
    }

    @Test
    fun `fallback based sources still request their required permissions`() {
        assertTrue(
            SessionPermissionPolicy.requiresBluetooth(
                config(SessionMode.TELEMETRY, TelemetrySourceKind.RACEBOX_OBD_FUSION),
            ),
        )
        assertTrue(
            SessionPermissionPolicy.requiresFineLocation(
                config(SessionMode.TELEMETRY, TelemetrySourceKind.PHONE_IMU_GPS),
            ),
        )
        assertTrue(
            SessionPermissionPolicy.requiresFineLocation(
                config(SessionMode.DEVICE_TEST, TelemetrySourceKind.PHONE_IMU_GPS),
            ),
        )
    }

    private fun config(
        mode: SessionMode,
        source: TelemetrySourceKind,
    ): LiveSessionConfig {
        return LiveSessionConfig(
            coachId = "superaj",
            audioEnabled = true,
            trackName = TrackCatalog.defaultTrack.name,
            sessionMode = mode,
            telemetrySource = source,
            obdTransportPreference = ObdTransportPreference.AUTO,
            sessionGoals = emptyList(),
            sourceUrl = null,
        )
    }
}
