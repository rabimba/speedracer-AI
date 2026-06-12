package com.trustableai.koru.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AimCanProtocolTest {
    @Test
    fun `SLCAN parser handles single multiple partial malformed extended and unknown frames`() {
        val parser = AimCanSlcanParser()

        val first = parser.append("t42086419D20400006C07\r", receivedAtElapsedMs = 100L)
        assertEquals(1, first.size)
        assertEquals(0x420, first[0].id)
        assertEquals(8, first[0].dlc)

        val partialA = parser.append("t42282003", receivedAtElapsedMs = 120L)
        assertEquals(0, partialA.size)
        val partialB = parser.append("B3150100E7FF\rt423885FF590185FF3800\r", receivedAtElapsedMs = 130L)
        assertEquals(2, partialB.size)
        assertEquals(0x422, partialB[0].id)
        assertEquals(0x423, partialB[1].id)

        assertEquals(0, parser.append("T0000000086419D20400006C07\r", receivedAtElapsedMs = 140L).size)
        val unknownStandard = parser.append("t55580000000000000000\r", receivedAtElapsedMs = 150L)
        assertEquals(1, unknownStandard.size)
        assertEquals(0x555, unknownStandard[0].id)
        assertEquals(0, parser.append("bad\r", receivedAtElapsedMs = 160L).size)
        assertEquals(1, parser.decodeErrors)
    }

    @Test
    fun `decoder validates mapped little endian unsigned signed int16 and gps int32 channels`() {
        var sample: AimCanSample? = null
        val now = 1_000L

        listOf(
            "t42086419D20400006C07",
            "t42187B0085FF98088E02",
            "t42282003B3150100E7FF",
            "t423885FF590185FF3800",
            "t4248D2048A00ECFF0000",
            "t45086D0270026B026C02",
            "t45186A02DE02A2080100",
        ).forEachIndexed { index, raw ->
            val frame = AimCanSlcanParser().append("$raw\r", now + index).single()
            sample = AimCanDecoder.applyFrame(sample, frame)
        }
        sample = AimCanDecoder.applyFrame(
            sample,
            SlcanFrame(
                id = AimCanFrameIds.GPS_POSITION,
                dlc = 8,
                data = i32le(381_627_200) + i32le(-1_224_550_000),
                receivedAtElapsedMs = now + 8,
                raw = "t4528...",
            ),
        )

        val decoded = sample ?: error("expected decoded sample")
        assertEquals(6500, decoded.rpm)
        assertEquals(123.4, decoded.gpsSpeedMph ?: -1.0, 0.01)
        assertEquals(87.78, decoded.waterTempC ?: -1.0, 0.01)
        assertEquals(-12.3, decoded.rollRateDegPerSec ?: 0.0, 0.01)
        assertEquals(104.44, decoded.oilFilterTempC ?: -1.0, 0.01)
        assertEquals(800, decoded.brakePressureRaw)
        assertEquals(80.0, decoded.brakePressurePsi ?: -1.0, 0.01)
        assertEquals(10, decoded.brakePressureZeroOffsetRaw)
        assertEquals(79.0, decoded.brakePressureCalibratedPsi ?: -1.0, 0.01)
        assertEquals(5555, decoded.pedalPositionRaw)
        assertEquals(55.55, decoded.pedalPositionPercent ?: -1.0, 0.01)
        assertEquals(1, decoded.brakeSwitchRaw)
        assertEquals(true, decoded.brakeSwitchApplied)
        assertEquals(-2.5, decoded.pitchRateDegPerSec ?: 0.0, 0.01)
        assertEquals(-12.3, decoded.steeringAngleDeg ?: 0.0, 0.01)
        assertEquals(34.5, decoded.yawRateDegPerSec ?: 0.0, 0.01)
        assertEquals(-1.23, decoded.lateralG ?: 0.0, 0.01)
        assertEquals(0.56, decoded.inlineG ?: 0.0, 0.01)
        assertEquals(12.34, decoded.fuelLevelGal ?: 0.0, 0.01)
        assertEquals(13.8, decoded.batteryVoltage ?: 0.0, 0.01)
        assertEquals(-0.20, decoded.verticalG ?: 0.0, 0.01)
        assertEquals(62.1, decoded.wheelSpeedFrontLeftMph ?: 0.0, 0.01)
        assertEquals(61.8, decoded.ecuSpeedMph ?: 0.0, 0.01)
        assertEquals(23.0, decoded.outsideTempC ?: 0.0, 0.01)
        assertEquals(true, decoded.dscRegActive)
        assertEquals(38.16272, decoded.gpsLatitude ?: 0.0, 0.000001)
        assertEquals(-122.455, decoded.gpsLongitude ?: 0.0, 0.000001)
        assertNull(decoded.gearRaw?.takeIf { it > 0 })
        assertEquals("t42282003B3150100E7FF", decoded.rawCanSamplesById[AimCanFrameIds.CONTROLS])
    }

    @Test
    fun `captured stationary brake frame uses tenths psi scale and raw count zero offset`() {
        val frame = AimCanSlcanParser().append("t42280C00000000000100\r", receivedAtElapsedMs = 2_000L).single()

        val decoded = AimCanDecoder.applyFrame(null, frame)

        assertEquals(12, decoded.brakePressureRaw)
        assertEquals(1.2, decoded.brakePressurePsi ?: -1.0, 0.01)
        assertEquals(10, decoded.brakePressureZeroOffsetRaw)
        assertEquals(0.2, decoded.brakePressureCalibratedPsi ?: -1.0, 0.01)
        assertEquals(0, decoded.pedalPositionRaw)
        assertEquals(0.0, decoded.pedalPositionPercent ?: -1.0, 0.01)
        assertEquals(0, decoded.brakeSwitchRaw)
        assertEquals(false, decoded.brakeSwitchApplied)
    }

    @Test
    fun `SLCAN parser byte overload preserves non AiM DLC frames for smoke test visibility`() {
        val parser = AimCanSlcanParser()
        val bytes = "t55520102\r".toByteArray(Charsets.US_ASCII)

        val frames = parser.append(bytes, bytes.size, receivedAtElapsedMs = 3_000L)

        assertEquals(1, frames.size)
        assertEquals(0x555, frames.single().id)
        assertEquals(2, frames.single().dlc)
        assertEquals("t55520102", frames.single().raw)
    }

    @Test
    fun `decoder records raw mapped frame with unexpected DLC without reading past data`() {
        val frame = AimCanSlcanParser().append("t42220102\r", receivedAtElapsedMs = 3_100L).single()

        val decoded = AimCanDecoder.applyFrame(null, frame)

        assertEquals("t42220102", decoded.rawCanSample)
        assertEquals("t42220102", decoded.rawCanSamplesById[AimCanFrameIds.CONTROLS])
        assertNull(decoded.brakePressureRaw)
    }

    @Test
    fun `decoder caps raw CAN health maps while preserving mapped AiM ids`() {
        var sample: AimCanSample? = null

        repeat(100) { index ->
            val frameId = 0x100 + index
            val frame = SlcanFrame(
                id = frameId,
                dlc = 2,
                data = byteArrayOf(index.toByte(), 0),
                receivedAtElapsedMs = 4_000L + index,
                raw = "t${frameId.toString(16).padStart(3, '0')}2AA00",
            )
            sample = AimCanDecoder.applyFrame(sample, frame)
        }
        val mapped = AimCanSlcanParser().append("t42220102\r", receivedAtElapsedMs = 4_200L).single()
        val decoded = AimCanDecoder.applyFrame(sample, mapped)

        assertTrue(decoded.rawCanSamplesById.size <= 64)
        assertTrue(decoded.channelUpdatedAtElapsedMs.size <= 64)
        assertEquals("t42220102", decoded.rawCanSamplesById[AimCanFrameIds.CONTROLS])
    }

    private fun i32le(value: Int): ByteArray {
        return byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )
    }
}
