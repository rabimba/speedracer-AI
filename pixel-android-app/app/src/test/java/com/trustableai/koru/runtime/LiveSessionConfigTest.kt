package com.trustableai.koru.runtime

import com.trustableai.koru.model.TelemetrySourceKind
import org.junit.Assert.assertEquals
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
}
