package com.trustableai.koru.service

import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.Track
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

interface TelemetrySource {
    suspend fun start()

    suspend fun stop()

    fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame
}

class RaceBoxBleSource : TelemetrySource {
    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        error("RaceBox BLE source is a stub; use SyntheticTrackSource until hardware integration lands.")
    }
}

class ObdBluetoothSource : TelemetrySource {
    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        error("OBD Bluetooth source is a stub; use SyntheticTrackSource until hardware integration lands.")
    }
}

class SyntheticTrackSource : TelemetrySource {
    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        val angle = (step % 360) * (PI / 180.0)
        val phase = step % 120
        val braking = phase in 0..16
        val turnIn = phase in 17..28
        val midCorner = phase in 29..46
        val exit = phase in 47..70
        val accelerate = phase in 71..95

        val brake = when {
            braking -> 78.0 - phase
            turnIn -> 20.0
            else -> 0.0
        }.coerceAtLeast(0.0)

        val throttle = when {
            midCorner -> 8.0
            exit -> 42.0 + (phase - 47) * 1.9
            accelerate -> 78.0 + (phase - 71) * 0.8
            else -> 14.0
        }.coerceIn(0.0, 100.0)

        val gLat = when {
            midCorner -> 1.05
            turnIn -> 0.58
            exit -> 0.42
            else -> 0.12
        } * if (step / 120 % 2 == 0) 1 else -1

        val gLong = when {
            braking -> -1.25
            turnIn -> -0.35
            accelerate -> 0.48
            else -> 0.05
        }

        val speedMph = when {
            braking -> 102.0 - phase * 1.4
            turnIn -> 70.0 - (phase - 17) * 0.8
            midCorner -> 55.0 + sin(angle) * 2.0
            exit -> 58.0 + (phase - 47) * 1.2
            accelerate -> 86.0 + (phase - 71) * 1.3
            else -> 94.0
        }

        val latitude = track.centerLat + sin(angle) * 0.0018
        val longitude = track.centerLon + cos(angle) * 0.0015
        return TelemetryFrame(
            timeSeconds = elapsedSeconds,
            latitude = latitude,
            longitude = longitude,
            altitude = 38.0 + sin(angle) * 6.0,
            speedMph = speedMph,
            rpm = 4200 + (speedMph * 42).toInt(),
            throttle = throttle,
            brake = brake,
            steering = if (midCorner || turnIn) gLat * 19.0 else 0.0,
            gLat = gLat,
            gLong = gLong,
            gear = when {
                speedMph > 95.0 -> 5
                speedMph > 75.0 -> 4
                speedMph > 55.0 -> 3
                else -> 2
            },
            distanceMeters = elapsedSeconds * (speedMph * 0.44704),
        )
    }
}

class TelemetryFusionEngine {
    fun fuse(frame: TelemetryFrame): TelemetryFrame {
        return frame.copy(
            gLat = frame.gLat.coerceIn(-3.0, 3.0),
            gLong = frame.gLong.coerceIn(-3.0, 3.0),
            throttle = frame.throttle.coerceIn(0.0, 100.0),
            brake = frame.brake.coerceIn(0.0, 100.0),
            speedMph = abs(frame.speedMph),
        )
    }
}
