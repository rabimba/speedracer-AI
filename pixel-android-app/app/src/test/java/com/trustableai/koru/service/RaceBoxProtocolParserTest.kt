package com.trustableai.koru.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class RaceBoxProtocolParserTest {
    @Test
    fun `parses split RaceBox data packet after checksum validation`() {
        val packet = raceBoxPacket(
            latitude = 38.16272,
            longitude = -122.45500,
            speedMps = 44.704,
            gLong = -0.98,
            gLat = 0.35,
        )
        val parser = RaceBoxProtocolParser()

        assertTrue(parser.append(packet.copyOfRange(0, 17), receivedAtElapsedMs = 10_000L).isEmpty())
        val samples = parser.append(packet.copyOfRange(17, packet.size), receivedAtElapsedMs = 10_040L)

        assertEquals(1, samples.size)
        val sample = samples.single()
        assertEquals(38.16272, sample.latitude, 0.000001)
        assertEquals(-122.45500, sample.longitude, 0.000001)
        assertEquals(100.0, sample.speedMph, 0.02)
        assertEquals(-0.98, sample.gLong, 0.001)
        assertEquals(0.35, sample.gLat, 0.001)
        assertEquals(11, sample.satellites)
        assertEquals(true, sample.fixOk)
        assertEquals(10_040L, sample.receivedAtElapsedMs)
    }

    @Test
    fun `drops bad checksum packet`() {
        val packet = raceBoxPacket(
            latitude = 38.0,
            longitude = -122.0,
            speedMps = 10.0,
            gLong = 0.1,
            gLat = 0.2,
        )
        packet[packet.lastIndex] = (packet.last() + 1).toByte()

        val samples = RaceBoxProtocolParser().append(packet, receivedAtElapsedMs = 1L)

        assertTrue(samples.isEmpty())
    }

    private fun raceBoxPacket(
        latitude: Double,
        longitude: Double,
        speedMps: Double,
        gLong: Double,
        gLat: Double,
    ): ByteArray {
        val payload = ByteArray(80)
        payload.putU32(0, 123_456L)
        payload[20] = 3
        payload[21] = 1
        payload[23] = 11
        payload.putI32(24, (longitude * 10_000_000).roundToInt())
        payload.putI32(28, (latitude * 10_000_000).roundToInt())
        payload.putI32(36, 52_000)
        payload.putU32(40, 1_200L)
        payload.putI32(48, (speedMps * 1000.0).roundToInt())
        payload.putI32(52, 12_345_678)
        payload[66] = 0
        payload[67] = 88
        payload.putI16(68, (gLong * 1000.0).roundToInt())
        payload.putI16(70, (gLat * 1000.0).roundToInt())
        payload.putI16(72, 15)

        val packet = ByteArray(88)
        packet[0] = 0xB5.toByte()
        packet[1] = 0x62
        packet[2] = 0xFF.toByte()
        packet[3] = 0x01
        packet[4] = 80
        packet[5] = 0
        payload.copyInto(packet, destinationOffset = 6)
        var ckA = 0
        var ckB = 0
        for (index in 2 until packet.size - 2) {
            ckA = (ckA + (packet[index].toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }
        packet[86] = ckA.toByte()
        packet[87] = ckB.toByte()
        return packet
    }

    private fun ByteArray.putI16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putI32(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }
}
