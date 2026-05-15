package com.trustableai.koru.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdLinkProtocolTest {
    @Test
    fun `parses standard mode 01 pid responses`() {
        assertEquals(1726.0, ObdLinkProtocol.parse(ObdPid.ENGINE_RPM, "010C\r41 0C 1A F8\r>") ?: -1.0, 0.01)
        assertEquals(62.14, ObdLinkProtocol.parse(ObdPid.VEHICLE_SPEED, "41 0D 64\r>") ?: -1.0, 0.01)
        assertEquals(39.21, ObdLinkProtocol.parse(ObdPid.THROTTLE_POSITION, "41 11 64\r>") ?: -1.0, 0.01)
        assertEquals(50.0, ObdLinkProtocol.parse(ObdPid.COOLANT_TEMP, "41 05 5A\r>") ?: -1.0, 0.01)
        assertEquals(76.0, ObdLinkProtocol.parse(ObdPid.ENGINE_OIL_TEMP, "41 5C 74\r>") ?: -1.0, 0.01)
        assertEquals(47.06, ObdLinkProtocol.parse(ObdPid.ENGINE_LOAD, "41 04 78\r>") ?: -1.0, 0.01)
        assertEquals(19.2, ObdLinkProtocol.parse(ObdPid.MAF, "41 10 07 80\r>") ?: -1.0, 0.01)
        assertEquals(30.0, ObdLinkProtocol.parse(ObdPid.INTAKE_TEMP, "41 0F 46\r>") ?: -1.0, 0.01)
        assertEquals(16.0, ObdLinkProtocol.parse(ObdPid.TIMING_ADVANCE, "41 0E A0\r>") ?: -1.0, 0.01)
        assertEquals(3.13, ObdLinkProtocol.parse(ObdPid.SHORT_FUEL_TRIM_1, "41 06 84\r>") ?: -1.0, 0.01)
        assertEquals(-3.13, ObdLinkProtocol.parse(ObdPid.LONG_FUEL_TRIM_1, "41 07 7C\r>") ?: -1.0, 0.01)
        assertEquals(0.5, ObdLinkProtocol.parse(ObdPid.O2_B1S1, "41 14 64 80\r>") ?: -1.0, 0.01)
    }

    @Test
    fun `handles headered responses and unsupported pid replies`() {
        assertEquals(
            1726.0,
            ObdLinkProtocol.parse(ObdPid.ENGINE_RPM, "7E8 04 41 0C 1A F8\r>") ?: -1.0,
            0.01,
        )
        assertNull(ObdLinkProtocol.parse(ObdPid.ENGINE_OIL_TEMP, "015C\rNO DATA\r>"))
        assertNull(ObdLinkProtocol.parse(ObdPid.THROTTLE_POSITION, "?\r>"))
    }

    @Test
    fun `parses supported pid bitmaps for polling selection`() {
        val supported00 = ObdLinkProtocol.parseSupportedMode01Pids("41 00 08 18 80 01\r>", supportPid = 0x00)
        val supported20 = ObdLinkProtocol.parseSupportedMode01Pids("7E8 06 41 20 00 00 00 01\r>", supportPid = 0x20)
        val supported40 = ObdLinkProtocol.parseSupportedMode01Pids("41 40 00 00 00 10\r>", supportPid = 0x40)

        assertEquals(true, 0x05 in supported00)
        assertEquals(true, 0x0C in supported00)
        assertEquals(true, 0x0D in supported00)
        assertEquals(true, 0x11 in supported00)
        assertEquals(true, 0x20 in supported00)
        assertEquals(true, 0x40 in supported20)
        assertEquals(true, 0x5C in supported40)
        assertEquals(emptySet<Int>(), ObdLinkProtocol.parseSupportedMode01Pids("NO DATA\r>", supportPid = 0x40))
    }

    @Test
    fun `applies readings without overwriting prior channels on no data`() {
        val base = ObdSample(receivedAtElapsedMs = 100L, rpm = 1000)
        val withThrottle = ObdLinkProtocol.applyReading(
            base,
            ObdPid.THROTTLE_POSITION,
            "41 11 80\r>",
            receivedAtElapsedMs = 120L,
        )
        val noOil = ObdLinkProtocol.applyReading(
            withThrottle,
            ObdPid.ENGINE_OIL_TEMP,
            "NO DATA\r>",
            receivedAtElapsedMs = 140L,
        )

        assertEquals(1000, noOil.rpm)
        assertEquals(50.19, noOil.throttlePercent ?: -1.0, 0.01)
        assertNull(noOil.oilTempC)
        assertEquals(120L, noOil.receivedAtElapsedMs)
        assertEquals(120L, noOil.channelUpdatedAtElapsedMs[ObdPid.THROTTLE_POSITION])
        assertNull(noOil.channelUpdatedAtElapsedMs[ObdPid.ENGINE_OIL_TEMP])
    }
}
