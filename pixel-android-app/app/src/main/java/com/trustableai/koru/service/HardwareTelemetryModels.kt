package com.trustableai.koru.service

import com.trustableai.koru.model.TelemetrySourceHealth
import com.trustableai.koru.model.VehicleDiagnostics
import kotlin.math.abs

const val METERS_PER_SECOND_TO_MPH = 2.2369362920544

enum class ObdTransportKind(val bridgeValue: String) {
    BLUETOOTH("bluetooth"),
    USB("usb"),
}

data class RaceBoxClientStatus(
    val connected: Boolean,
    val scanning: Boolean,
    val detail: String,
)

data class ObdClientStatus(
    val connected: Boolean,
    val detail: String,
    val transport: ObdTransportKind? = null,
    val supportedPids: Set<Int> = emptySet(),
    val reconnectCount: Int = 0,
)

data class AimCanClientStatus(
    val connected: Boolean,
    val detail: String,
    val usbDeviceName: String? = null,
    val reconnectCount: Int = 0,
    val decodeErrors: Int = 0,
)

data class RaceBoxSample(
    val receivedAtElapsedMs: Long,
    val iTowMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMps: Double,
    val headingDegrees: Double,
    val gLong: Double,
    val gLat: Double,
    val gVert: Double,
    val fixStatus: Int,
    val fixOk: Boolean,
    val satellites: Int,
    val horizontalAccuracyMeters: Double?,
    val batteryPercent: Int?,
) {
    val speedMph: Double
        get() = speedMps * METERS_PER_SECOND_TO_MPH
}

data class ObdSample(
    val receivedAtElapsedMs: Long,
    val rpm: Int? = null,
    val speedMph: Double? = null,
    val throttlePercent: Double? = null,
    val coolantTempC: Double? = null,
    val oilTempC: Double? = null,
    val engineLoadPercent: Double? = null,
    val mafGramsPerSecond: Double? = null,
    val intakeTempC: Double? = null,
    val timingAdvanceDegrees: Double? = null,
    val shortFuelTrim1Percent: Double? = null,
    val longFuelTrim1Percent: Double? = null,
    val shortFuelTrim2Percent: Double? = null,
    val longFuelTrim2Percent: Double? = null,
    val o2Bank1Sensor1Volts: Double? = null,
    val o2Bank2Sensor1Volts: Double? = null,
    val channelUpdatedAtElapsedMs: Map<ObdPid, Long> = emptyMap(),
) {
    fun isFresh(pid: ObdPid, nowElapsedMs: Long, staleMs: Long): Boolean {
        val updatedAt = channelUpdatedAtElapsedMs[pid] ?: return false
        return nowElapsedMs - updatedAt <= staleMs
    }

    fun ageMs(pid: ObdPid, nowElapsedMs: Long): Long? {
        return channelUpdatedAtElapsedMs[pid]?.let { updatedAt -> nowElapsedMs - updatedAt }
    }

    fun diagnostics(nowElapsedMs: Long, staleMs: Long): VehicleDiagnostics? {
        val diagnostics = VehicleDiagnostics(
            engineLoadPercent = engineLoadPercent.takeIfFresh(ObdPid.ENGINE_LOAD, nowElapsedMs, staleMs),
            mafGramsPerSecond = mafGramsPerSecond.takeIfFresh(ObdPid.MAF, nowElapsedMs, staleMs),
            intakeTempC = intakeTempC.takeIfFresh(ObdPid.INTAKE_TEMP, nowElapsedMs, staleMs),
            timingAdvanceDegrees = timingAdvanceDegrees.takeIfFresh(ObdPid.TIMING_ADVANCE, nowElapsedMs, staleMs),
            shortFuelTrim1Percent = shortFuelTrim1Percent.takeIfFresh(ObdPid.SHORT_FUEL_TRIM_1, nowElapsedMs, staleMs),
            longFuelTrim1Percent = longFuelTrim1Percent.takeIfFresh(ObdPid.LONG_FUEL_TRIM_1, nowElapsedMs, staleMs),
            shortFuelTrim2Percent = shortFuelTrim2Percent.takeIfFresh(ObdPid.SHORT_FUEL_TRIM_2, nowElapsedMs, staleMs),
            longFuelTrim2Percent = longFuelTrim2Percent.takeIfFresh(ObdPid.LONG_FUEL_TRIM_2, nowElapsedMs, staleMs),
            o2Bank1Sensor1Volts = o2Bank1Sensor1Volts.takeIfFresh(ObdPid.O2_B1S1, nowElapsedMs, staleMs),
            o2Bank2Sensor1Volts = o2Bank2Sensor1Volts.takeIfFresh(ObdPid.O2_B2S1, nowElapsedMs, staleMs),
        )
        return diagnostics.takeIf {
            listOfNotNull(
                it.engineLoadPercent,
                it.mafGramsPerSecond,
                it.intakeTempC,
                it.timingAdvanceDegrees,
                it.shortFuelTrim1Percent,
                it.longFuelTrim1Percent,
                it.shortFuelTrim2Percent,
                it.longFuelTrim2Percent,
                it.o2Bank1Sensor1Volts,
                it.o2Bank2Sensor1Volts,
            ).isNotEmpty()
        }
    }

    private fun Double?.takeIfFresh(pid: ObdPid, nowElapsedMs: Long, staleMs: Long): Double? {
        return this?.takeIf { isFresh(pid, nowElapsedMs, staleMs) }
    }
}

data class AimCanSample(
    val receivedAtElapsedMs: Long,
    val rpm: Int? = null,
    val gpsSpeedMph: Double? = null,
    val gearRaw: Int? = null,
    val waterTempC: Double? = null,
    val waterPressurePsi: Double? = null,
    val rollRateDegPerSec: Double? = null,
    val oilFilterTempC: Double? = null,
    val oilPressurePsi: Double? = null,
    val brakePressureRaw: Int? = null,
    val brakePressurePsi: Double? = null,
    val brakePressureZeroOffsetRaw: Int? = null,
    val brakePressureCalibratedPsi: Double? = null,
    val brakePressureZeroOffsetPsi: Double? = null,
    val pedalPositionRaw: Int? = null,
    val pedalPositionPercent: Double? = null,
    val brakeSwitchRaw: Int? = null,
    val brakeSwitchApplied: Boolean? = null,
    val pitchRateDegPerSec: Double? = null,
    val steeringAngleDeg: Double? = null,
    val yawRateDegPerSec: Double? = null,
    val lateralG: Double? = null,
    val inlineG: Double? = null,
    val fuelLevelGal: Double? = null,
    val batteryVoltage: Double? = null,
    val verticalG: Double? = null,
    val wheelSpeedFrontLeftMph: Double? = null,
    val wheelSpeedFrontRightMph: Double? = null,
    val wheelSpeedRearLeftMph: Double? = null,
    val wheelSpeedRearRightMph: Double? = null,
    val ecuSpeedMph: Double? = null,
    val outsideTempC: Double? = null,
    val engineOilTempC: Double? = null,
    val dscRegActive: Boolean? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val channelUpdatedAtElapsedMs: Map<Int, Long> = emptyMap(),
    val frameRatesHz: Map<Int, Double> = emptyMap(),
    val rawCanSample: String? = null,
    val rawCanSamplesById: Map<Int, String> = emptyMap(),
    val decodeErrors: Int = 0,
) {
    fun isFresh(frameId: Int, nowElapsedMs: Long, staleMs: Long): Boolean {
        val updatedAt = channelUpdatedAtElapsedMs[frameId] ?: return false
        return nowElapsedMs - updatedAt <= staleMs
    }

    fun ageMs(frameId: Int, nowElapsedMs: Long): Long? {
        return channelUpdatedAtElapsedMs[frameId]?.let { updatedAt -> nowElapsedMs - updatedAt }
    }
}

interface RaceBoxDataClient {
    suspend fun start()

    suspend fun stop()

    fun latestSample(): RaceBoxSample?

    fun status(): RaceBoxClientStatus
}

interface ObdDataClient {
    suspend fun start()

    suspend fun stop()

    fun latestSample(): ObdSample?

    fun status(): ObdClientStatus
}

interface AimCanDataClient {
    suspend fun start()

    suspend fun stop()

    fun latestSample(): AimCanSample?

    fun status(): AimCanClientStatus
}

object HardwareTelemetryMath {
    fun estimateBrakeFromLongitudinalG(gLong: Double): Double {
        if (gLong >= -0.04) return 0.0
        return (((-gLong - 0.04) / 1.15) * 100.0).coerceIn(0.0, 100.0)
    }

    fun estimateThrottleFromMotion(speedMph: Double, gLat: Double, gLong: Double, brake: Double): Double {
        if (brake > 2.0) return 0.0
        return when {
            gLong > 0.05 -> (18.0 + (gLong / 0.5) * 82.0).coerceIn(0.0, 100.0)
            abs(gLat) > 0.45 && speedMph > 30.0 -> 14.0
            speedMph > 55.0 -> 22.0
            speedMph > 20.0 -> 14.0
            else -> 0.0
        }
    }

    fun estimateGear(speedMph: Double): Int {
        return when {
            speedMph > 110.0 -> 6
            speedMph > 88.0 -> 5
            speedMph > 64.0 -> 4
            speedMph > 42.0 -> 3
            speedMph > 18.0 -> 2
            else -> 1
        }
    }

    fun sourceHealth(
        nowElapsedMs: Long,
        raceBox: RaceBoxSample?,
        raceBoxStatus: RaceBoxClientStatus,
        obd: ObdSample?,
        obdStatus: ObdClientStatus,
        obdStaleMs: Long,
        obdDiagnosticStaleMs: Long,
        raceBoxStaleMs: Long,
    ): TelemetrySourceHealth {
        val raceBoxAge = raceBox?.let { nowElapsedMs - it.receivedAtElapsedMs }
        val obdAge = obd?.let { nowElapsedMs - it.receivedAtElapsedMs }
        val raceBoxFresh = raceBoxAge != null && raceBoxAge <= raceBoxStaleMs
        val hotChannelFresh = HOT_OBD_PIDS.any { pid -> obd?.isFresh(pid, nowElapsedMs, obdStaleMs) == true }
        val obdStale = !hotChannelFresh
        val raceBoxPart = when {
            raceBoxStatus.connected && raceBoxFresh && raceBox?.fixOk == true -> "RaceBox fix OK"
            raceBoxStatus.connected && raceBoxFresh -> "RaceBox connected, waiting for 3D fix"
            raceBoxStatus.connected -> "RaceBox connected, stale sample"
            raceBoxStatus.scanning -> "RaceBox scanning"
            else -> raceBoxStatus.detail
        }
        val obdPart = when {
            obdStatus.connected && !obdStale -> "OBDLink live"
            obdStatus.connected -> "OBDLink connected, stale sample"
            else -> obdStatus.detail
        }
        val speedDelta = if (raceBoxFresh && !obdStale && obd?.speedMph != null) {
            obd.speedMph - raceBox.speedMph
        } else {
            null
        }
        val channelAges = obd?.channelUpdatedAtElapsedMs.orEmpty()
            .mapKeys { (pid, _) -> pid.request }
            .mapValues { (_, updatedAt) -> nowElapsedMs - updatedAt }
        val channelStale = obd?.channelUpdatedAtElapsedMs.orEmpty()
            .map { (pid, updatedAt) ->
                val staleMs = if (pid in HOT_OBD_PIDS) obdStaleMs else obdDiagnosticStaleMs
                pid.request to (nowElapsedMs - updatedAt > staleMs)
            }
            .toMap()
        return TelemetrySourceHealth(
            status = "$raceBoxPart | $obdPart",
            raceBoxConnected = raceBoxStatus.connected,
            raceBoxFixGood = raceBox?.fixOk,
            raceBoxFixStatus = raceBox?.fixStatus,
            raceBoxSatellites = raceBox?.satellites,
            raceBoxSampleAgeMs = raceBoxAge,
            obdConnected = obdStatus.connected,
            obdSampleAgeMs = obdAge,
            obdStale = obdStale,
            obdSpeedDeltaMph = speedDelta,
            obdTransport = obdStatus.transport?.bridgeValue,
            obdSupportedPids = obdStatus.supportedPids
                .sorted()
                .map { pid -> "01%02X".format(pid) },
            obdReconnectCount = obdStatus.reconnectCount,
            obdChannelAgesMs = channelAges,
            obdChannelStale = channelStale,
        )
    }
}
