package com.trustableai.koru.service

import kotlin.math.roundToInt

enum class ObdPid(val mode01Pid: Int, val request: String, val responsePid: String) {
    ENGINE_LOAD(0x04, "0104", "04"),
    COOLANT_TEMP(0x05, "0105", "05"),
    SHORT_FUEL_TRIM_1(0x06, "0106", "06"),
    LONG_FUEL_TRIM_1(0x07, "0107", "07"),
    SHORT_FUEL_TRIM_2(0x08, "0108", "08"),
    LONG_FUEL_TRIM_2(0x09, "0109", "09"),
    ENGINE_RPM(0x0C, "010C", "0C"),
    VEHICLE_SPEED(0x0D, "010D", "0D"),
    TIMING_ADVANCE(0x0E, "010E", "0E"),
    INTAKE_TEMP(0x0F, "010F", "0F"),
    MAF(0x10, "0110", "10"),
    THROTTLE_POSITION(0x11, "0111", "11"),
    O2_B1S1(0x14, "0114", "14"),
    O2_B2S1(0x18, "0118", "18"),
    ENGINE_OIL_TEMP(0x5C, "015C", "5C"),
}

val HOT_OBD_PIDS = listOf(
    ObdPid.ENGINE_RPM,
    ObdPid.VEHICLE_SPEED,
    ObdPid.THROTTLE_POSITION,
    ObdPid.COOLANT_TEMP,
    ObdPid.ENGINE_OIL_TEMP,
)

val DIAGNOSTIC_OBD_PIDS = listOf(
    ObdPid.ENGINE_LOAD,
    ObdPid.MAF,
    ObdPid.INTAKE_TEMP,
    ObdPid.TIMING_ADVANCE,
    ObdPid.SHORT_FUEL_TRIM_1,
    ObdPid.LONG_FUEL_TRIM_1,
    ObdPid.SHORT_FUEL_TRIM_2,
    ObdPid.LONG_FUEL_TRIM_2,
    ObdPid.O2_B1S1,
    ObdPid.O2_B2S1,
)

object ObdLinkProtocol {
    private val unsupportedTokens = listOf(
        "NO DATA",
        "UNABLE TO CONNECT",
        "STOPPED",
        "CAN ERROR",
        "BUS ERROR",
        "?",
    )

    fun parse(pid: ObdPid, response: String): Double? {
        if (unsupportedTokens.any { token -> response.contains(token, ignoreCase = true) }) return null
        val bytes = payloadBytes(pid.responsePid, response) ?: return null
        return when (pid) {
            ObdPid.ENGINE_LOAD -> {
                if (bytes.isEmpty()) return null
                bytes[0] * 100.0 / 255.0
            }
            ObdPid.ENGINE_RPM -> {
                if (bytes.size < 2) return null
                ((bytes[0] * 256 + bytes[1]) / 4.0)
            }
            ObdPid.VEHICLE_SPEED -> {
                if (bytes.isEmpty()) return null
                bytes[0] * KPH_TO_MPH
            }
            ObdPid.THROTTLE_POSITION -> {
                if (bytes.isEmpty()) return null
                bytes[0] * 100.0 / 255.0
            }
            ObdPid.MAF -> {
                if (bytes.size < 2) return null
                (bytes[0] * 256 + bytes[1]) / 100.0
            }
            ObdPid.TIMING_ADVANCE -> {
                if (bytes.isEmpty()) return null
                (bytes[0] / 2.0) - 64.0
            }
            ObdPid.SHORT_FUEL_TRIM_1,
            ObdPid.LONG_FUEL_TRIM_1,
            ObdPid.SHORT_FUEL_TRIM_2,
            ObdPid.LONG_FUEL_TRIM_2 -> {
                if (bytes.isEmpty()) return null
                (bytes[0] - 128) * 100.0 / 128.0
            }
            ObdPid.O2_B1S1,
            ObdPid.O2_B2S1 -> {
                if (bytes.isEmpty()) return null
                bytes[0] / 200.0
            }
            ObdPid.COOLANT_TEMP,
            ObdPid.INTAKE_TEMP,
            ObdPid.ENGINE_OIL_TEMP -> {
                if (bytes.isEmpty()) return null
                bytes[0] - 40.0
            }
        }
    }

    fun parseSupportedMode01Pids(response: String, supportPid: Int): Set<Int> {
        if (unsupportedTokens.any { token -> response.contains(token, ignoreCase = true) }) return emptySet()
        val responsePid = "%02X".format(supportPid)
        val bytes = payloadBytes(responsePid, response)?.takeIf { it.size >= 4 } ?: return emptySet()
        return buildSet {
            bytes.take(4).forEachIndexed { byteIndex, byte ->
                for (bit in 0..7) {
                    if ((byte and (1 shl (7 - bit))) != 0) {
                        add(supportPid + byteIndex * 8 + bit + 1)
                    }
                }
            }
        }
    }

    fun applyReading(sample: ObdSample, pid: ObdPid, response: String, receivedAtElapsedMs: Long): ObdSample {
        val value = parse(pid, response) ?: return sample
        val channels = sample.channelUpdatedAtElapsedMs + (pid to receivedAtElapsedMs)
        return when (pid) {
            ObdPid.ENGINE_LOAD -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                engineLoadPercent = value.coerceIn(0.0, 100.0),
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.ENGINE_RPM -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                rpm = value.roundToInt(),
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.VEHICLE_SPEED -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                speedMph = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.THROTTLE_POSITION -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                throttlePercent = value.coerceIn(0.0, 100.0),
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.COOLANT_TEMP -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                coolantTempC = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.ENGINE_OIL_TEMP -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                oilTempC = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.INTAKE_TEMP -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                intakeTempC = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.MAF -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                mafGramsPerSecond = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.TIMING_ADVANCE -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                timingAdvanceDegrees = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.SHORT_FUEL_TRIM_1 -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                shortFuelTrim1Percent = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.LONG_FUEL_TRIM_1 -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                longFuelTrim1Percent = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.SHORT_FUEL_TRIM_2 -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                shortFuelTrim2Percent = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.LONG_FUEL_TRIM_2 -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                longFuelTrim2Percent = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.O2_B1S1 -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                o2Bank1Sensor1Volts = value,
                channelUpdatedAtElapsedMs = channels,
            )
            ObdPid.O2_B2S1 -> sample.copy(
                receivedAtElapsedMs = receivedAtElapsedMs,
                o2Bank2Sensor1Volts = value,
                channelUpdatedAtElapsedMs = channels,
            )
        }
    }

    private fun payloadBytes(responsePid: String, response: String): List<Int>? {
        val expected = "41$responsePid"
        response
            .replace('\r', '\n')
            .split('\n')
            .asSequence()
            .map { line ->
                line.uppercase()
                    .substringBefore('>')
                    .filter { it in '0'..'9' || it in 'A'..'F' }
            }
            .filter { it.isNotBlank() }
            .forEach { compact ->
                val responseStart = compact.indexOf(expected)
                if (responseStart < 0) return@forEach
                val payloadHex = compact.drop(responseStart + expected.length)
                val bytes = payloadHex.chunked(2)
                    .mapNotNull { byteHex ->
                        if (byteHex.length == 2) byteHex.toIntOrNull(16) else null
                    }
                if (bytes.isNotEmpty()) return bytes
            }
        return null
    }

    private const val KPH_TO_MPH = 0.621371192237334
}
