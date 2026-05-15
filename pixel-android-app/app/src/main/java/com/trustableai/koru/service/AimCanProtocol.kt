package com.trustableai.koru.service

import java.util.Locale

object AimCanFrameIds {
    const val CORE = 0x420
    const val PRESSURE_RATES = 0x421
    const val CONTROLS = 0x422
    const val MOTION = 0x423
    const val AUX = 0x424
    const val WHEEL_SPEEDS = 0x450
    const val ECU = 0x451
    const val GPS_POSITION = 0x452

    val all: Set<Int> = setOf(
        CORE,
        PRESSURE_RATES,
        CONTROLS,
        MOTION,
        AUX,
        WHEEL_SPEEDS,
        ECU,
        GPS_POSITION,
    )

    fun key(frameId: Int): String = "0x%03X".format(Locale.US, frameId)
}

data class SlcanFrame(
    val id: Int,
    val dlc: Int,
    val data: ByteArray,
    val receivedAtElapsedMs: Long,
    val raw: String,
)

class AimCanSlcanParser(
    private val allowedIds: Set<Int> = AimCanFrameIds.all,
) {
    private val buffer = StringBuilder()

    var decodeErrors: Int = 0
        private set

    fun append(bytes: ByteArray, receivedAtElapsedMs: Long): List<SlcanFrame> {
        return append(String(bytes, Charsets.US_ASCII), receivedAtElapsedMs)
    }

    fun append(ascii: String, receivedAtElapsedMs: Long): List<SlcanFrame> {
        val frames = mutableListOf<SlcanFrame>()
        ascii.forEach { char ->
            when (char) {
                '\r', '\n' -> {
                    val line = buffer.toString()
                    buffer.clear()
                    parseLine(line, receivedAtElapsedMs)?.let(frames::add)
                }

                else -> {
                    buffer.append(char)
                    if (buffer.length > MAX_FRAME_CHARS) {
                        buffer.clear()
                        decodeErrors += 1
                    }
                }
            }
        }
        return frames
    }

    private fun parseLine(line: String, receivedAtElapsedMs: Long): SlcanFrame? {
        val raw = line.trim()
        if (raw.isEmpty()) return null
        if (raw.first() == 'T') return null
        if (raw.first() != 't') {
            decodeErrors += 1
            return null
        }
        if (raw.length < HEADER_CHARS) {
            decodeErrors += 1
            return null
        }

        val id = raw.substring(1, 4).toIntOrNull(16)
        val dlc = raw[4].digitToIntOrNull(16)
        if (id == null || dlc == null || dlc !in 0..8) {
            decodeErrors += 1
            return null
        }
        if (id !in allowedIds) return null
        if (dlc != AIM_DLC_BYTES || raw.length != HEADER_CHARS + dlc * 2) {
            decodeErrors += 1
            return null
        }

        val data = ByteArray(dlc)
        for (index in 0 until dlc) {
            val value = raw.substring(HEADER_CHARS + index * 2, HEADER_CHARS + index * 2 + 2)
                .toIntOrNull(16)
            if (value == null) {
                decodeErrors += 1
                return null
            }
            data[index] = value.toByte()
        }

        return SlcanFrame(
            id = id,
            dlc = dlc,
            data = data,
            receivedAtElapsedMs = receivedAtElapsedMs,
            raw = raw,
        )
    }

    private companion object {
        private const val HEADER_CHARS = 5
        private const val AIM_DLC_BYTES = 8
        private const val MAX_FRAME_CHARS = 96
    }
}

object AimCanDecoder {
    fun applyFrame(previous: AimCanSample?, frame: SlcanFrame, decodeErrors: Int = previous?.decodeErrors ?: 0): AimCanSample {
        val base = previous ?: AimCanSample(receivedAtElapsedMs = frame.receivedAtElapsedMs)
        val updates = base.channelUpdatedAtElapsedMs + (frame.id to frame.receivedAtElapsedMs)
        val common = base.copy(
            receivedAtElapsedMs = frame.receivedAtElapsedMs,
            channelUpdatedAtElapsedMs = updates,
            rawCanSample = frame.raw,
            decodeErrors = decodeErrors,
        )
        return when (frame.id) {
            AimCanFrameIds.CORE -> common.copy(
                rpm = frame.u16(0),
                gpsSpeedMph = frame.u16(2) * 0.1,
                gearRaw = frame.u16(4),
                waterTempC = fahrenheitToCelsius(frame.u16(6) * 0.1),
            )

            AimCanFrameIds.PRESSURE_RATES -> common.copy(
                waterPressurePsi = frame.u16(0) * 0.1,
                rollRateDegPerSec = frame.i16(2) * 0.1,
                oilFilterTempC = fahrenheitToCelsius(frame.u16(4) * 0.1),
                oilPressurePsi = frame.u16(6) * 0.1,
            )

            AimCanFrameIds.CONTROLS -> common.copy(
                brakePressurePsi = frame.u16(0).toDouble(),
                pedalPositionPercent = (frame.u16(2) * 0.01).coerceIn(0.0, 100.0),
                brakeSwitchApplied = frame.u16(4) != 0,
                pitchRateDegPerSec = frame.i16(6) * 0.1,
            )

            AimCanFrameIds.MOTION -> common.copy(
                steeringAngleDeg = frame.i16(0) * 0.1,
                yawRateDegPerSec = frame.i16(2) * 0.1,
                lateralG = frame.i16(4) * 0.01,
                inlineG = frame.i16(6) * 0.01,
            )

            AimCanFrameIds.AUX -> common.copy(
                fuelLevelGal = frame.u16(0) * 0.01,
                batteryVoltage = frame.u16(2) * 0.1,
                verticalG = frame.i16(4) * 0.01,
            )

            AimCanFrameIds.WHEEL_SPEEDS -> common.copy(
                wheelSpeedFrontLeftMph = frame.u16(0) * 0.1,
                wheelSpeedFrontRightMph = frame.u16(2) * 0.1,
                wheelSpeedRearLeftMph = frame.u16(4) * 0.1,
                wheelSpeedRearRightMph = frame.u16(6) * 0.1,
            )

            AimCanFrameIds.ECU -> common.copy(
                ecuSpeedMph = frame.u16(0) * 0.1,
                outsideTempC = fahrenheitToCelsius(frame.u16(2) * 0.1),
                engineOilTempC = fahrenheitToCelsius(frame.u16(4) * 0.1),
                dscRegActive = frame.u16(6) != 0,
            )

            AimCanFrameIds.GPS_POSITION -> common.copy(
                gpsLatitude = frame.i32(0) * GPS_DEG_7,
                gpsLongitude = frame.i32(4) * GPS_DEG_7,
            )

            else -> common
        }
    }

    private fun SlcanFrame.u16(offset: Int): Int {
        return byte(offset) or (byte(offset + 1) shl 8)
    }

    private fun SlcanFrame.i16(offset: Int): Int {
        val raw = u16(offset)
        return if (raw > Short.MAX_VALUE) raw - 0x10000 else raw
    }

    private fun SlcanFrame.i32(offset: Int): Int {
        return byte(offset) or
            (byte(offset + 1) shl 8) or
            (byte(offset + 2) shl 16) or
            (byte(offset + 3) shl 24)
    }

    private fun SlcanFrame.byte(offset: Int): Int = data[offset].toInt() and 0xFF

    private fun fahrenheitToCelsius(valueF: Double): Double = (valueF - 32.0) * (5.0 / 9.0)

    private const val GPS_DEG_7 = 0.0000001
}
