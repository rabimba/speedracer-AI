package com.trustableai.koru.runtime

import com.trustableai.koru.model.AimCanBitrate
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveSessionConfigTest {
    @Test
    fun `missing telemetry source defaults to AiM CAN USB instead of synthetic`() {
        val config = LiveSessionConfig.fromJson("{}")

        assertEquals(TelemetrySourceKind.AIM_CAN_USB, config.telemetrySource)
    }

    @Test
    fun `unknown telemetry source defaults to AiM CAN USB while explicit synthetic remains manual`() {
        val unknownConfig = LiveSessionConfig.fromJson("""{"telemetrySource":"bad_value"}""")
        val syntheticConfig = LiveSessionConfig.fromJson("""{"telemetrySource":"synthetic"}""")
        val aimCanConfig = LiveSessionConfig.fromJson("""{"telemetrySource":"aim_can_usb"}""")

        assertEquals(TelemetrySourceKind.AIM_CAN_USB, unknownConfig.telemetrySource)
        assertEquals(TelemetrySourceKind.SYNTHETIC, syntheticConfig.telemetrySource)
        assertEquals(TelemetrySourceKind.AIM_CAN_USB, aimCanConfig.telemetrySource)
    }

    @Test
    fun `AiM CAN bitrate and camera fusion round trip through json`() {
        val config = LiveSessionConfig.fromJson(
            """
            {
              "sessionMode": "can_interface_check",
              "telemetrySource": "aim_can_usb",
              "aimCanBitrate": "s6",
              "cameraFusionEnabled": true
            }
            """.trimIndent(),
        )

        assertEquals(SessionMode.CAN_INTERFACE_CHECK, config.sessionMode)
        assertEquals(AimCanBitrate.S6_500KBPS, config.aimCanBitrate)
        assertEquals(true, config.cameraFusionEnabled)

        val roundTrip = LiveSessionConfig.fromJson(config.toJson())
        assertEquals(SessionMode.CAN_INTERFACE_CHECK, roundTrip.sessionMode)
        assertEquals(AimCanBitrate.S6_500KBPS, roundTrip.aimCanBitrate)
        assertEquals(true, roundTrip.cameraFusionEnabled)
    }

    @Test
    fun `camera fusion defaults off for field telemetry`() {
        val config = LiveSessionConfig.fromJson("""{"telemetrySource":"aim_can_usb"}""")

        assertFalse(config.cameraFusionEnabled)
        assertEquals(AimCanBitrate.S8_1MBPS, config.aimCanBitrate)
    }
}
