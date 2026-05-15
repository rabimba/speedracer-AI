package com.trustableai.koru.service

class RaceBoxProtocolParser {
    private val buffer = mutableListOf<Byte>()

    fun append(notification: ByteArray, receivedAtElapsedMs: Long): List<RaceBoxSample> {
        if (notification.isEmpty()) return emptyList()
        notification.forEach(buffer::add)
        val samples = mutableListOf<RaceBoxSample>()

        while (true) {
            val start = indexOfPacketStart()
            if (start < 0) {
                keepPossibleHeaderPrefix()
                break
            }
            if (start > 0) {
                buffer.subList(0, start).clear()
            }
            if (buffer.size < MIN_PACKET_BYTES) break

            val payloadLength = u16At(4)
            if (payloadLength > MAX_PAYLOAD_BYTES) {
                buffer.removeAt(0)
                continue
            }

            val packetLength = HEADER_BYTES + payloadLength + CHECKSUM_BYTES
            if (buffer.size < packetLength) break

            val packet = ByteArray(packetLength) { index -> buffer[index] }
            buffer.subList(0, packetLength).clear()
            if (!hasValidChecksum(packet)) continue

            val messageClass = packet[2].toUnsignedInt()
            val messageId = packet[3].toUnsignedInt()
            if (messageClass == RACEBOX_CLASS && messageId == DATA_MESSAGE_ID && payloadLength == DATA_PAYLOAD_BYTES) {
                samples += parseDataMessage(packet, receivedAtElapsedMs)
            }
        }

        if (buffer.size > MAX_BUFFER_BYTES) {
            buffer.subList(0, buffer.size - MAX_BUFFER_BYTES).clear()
        }
        return samples
    }

    private fun parseDataMessage(packet: ByteArray, receivedAtElapsedMs: Long): RaceBoxSample {
        val payloadOffset = HEADER_BYTES
        val payload = packet.copyOfRange(payloadOffset, payloadOffset + DATA_PAYLOAD_BYTES)
        val fixStatus = payload.u8(20)
        val fixStatusFlags = payload.u8(21)
        val latLonFlags = payload.u8(66)
        val batteryStatus = payload.u8(67)
        val coordinatesValid = (latLonFlags and 0x01) == 0
        val fixOk = coordinatesValid && fixStatus == FIX_3D && (fixStatusFlags and 0x01) != 0

        return RaceBoxSample(
            receivedAtElapsedMs = receivedAtElapsedMs,
            iTowMs = payload.u32(0),
            latitude = payload.i32(28) / 10_000_000.0,
            longitude = payload.i32(24) / 10_000_000.0,
            altitudeMeters = payload.i32(36) / 1000.0,
            speedMps = payload.i32(48) / 1000.0,
            headingDegrees = payload.i32(52) / 100_000.0,
            gLong = payload.i16(68) / 1000.0,
            gLat = payload.i16(70) / 1000.0,
            gVert = payload.i16(72) / 1000.0,
            fixStatus = fixStatus,
            fixOk = fixOk,
            satellites = payload.u8(23),
            horizontalAccuracyMeters = payload.u32(40) / 1000.0,
            batteryPercent = batteryStatus and 0x7F,
        )
    }

    private fun indexOfPacketStart(): Int {
        for (index in 0 until buffer.size - 1) {
            if (buffer[index].toUnsignedInt() == SYNC_1 && buffer[index + 1].toUnsignedInt() == SYNC_2) {
                return index
            }
        }
        return -1
    }

    private fun keepPossibleHeaderPrefix() {
        val keepLast = buffer.lastOrNull()?.toUnsignedInt() == SYNC_1
        buffer.clear()
        if (keepLast) buffer += SYNC_1.toByte()
    }

    private fun u16At(offset: Int): Int {
        return buffer[offset].toUnsignedInt() or (buffer[offset + 1].toUnsignedInt() shl 8)
    }

    companion object {
        private const val SYNC_1 = 0xB5
        private const val SYNC_2 = 0x62
        private const val HEADER_BYTES = 6
        private const val CHECKSUM_BYTES = 2
        private const val MIN_PACKET_BYTES = HEADER_BYTES + CHECKSUM_BYTES
        private const val MAX_PAYLOAD_BYTES = 504
        private const val MAX_BUFFER_BYTES = 1024
        private const val RACEBOX_CLASS = 0xFF
        private const val DATA_MESSAGE_ID = 0x01
        private const val DATA_PAYLOAD_BYTES = 80
        private const val FIX_3D = 3

        fun hasValidChecksum(packet: ByteArray): Boolean {
            if (packet.size < MIN_PACKET_BYTES) return false
            var ckA = 0
            var ckB = 0
            for (index in 2 until packet.size - CHECKSUM_BYTES) {
                ckA = (ckA + packet[index].toUnsignedInt()) and 0xFF
                ckB = (ckB + ckA) and 0xFF
            }
            return ckA == packet[packet.size - 2].toUnsignedInt() &&
                ckB == packet[packet.size - 1].toUnsignedInt()
        }
    }
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

private fun ByteArray.u8(offset: Int): Int = this[offset].toUnsignedInt()

private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

private fun ByteArray.i16(offset: Int): Int {
    val value = u16(offset)
    return if (value >= 0x8000) value - 0x10000 else value
}

private fun ByteArray.i32(offset: Int): Int {
    return u8(offset) or
        (u8(offset + 1) shl 8) or
        (u8(offset + 2) shl 16) or
        (u8(offset + 3) shl 24)
}

private fun ByteArray.u32(offset: Int): Long {
    return (u8(offset).toLong() or
        (u8(offset + 1).toLong() shl 8) or
        (u8(offset + 2).toLong() shl 16) or
        (u8(offset + 3).toLong() shl 24)) and 0xFFFF_FFFFL
}
