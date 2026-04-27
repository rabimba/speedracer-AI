package com.trustableai.koru.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.model.Track
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class PhoneImuGpsSource(context: Context) : TelemetrySource, SensorEventListener {
    override val kind: TelemetrySourceKind = TelemetrySourceKind.PHONE_IMU_GPS

    private val tag = "PhoneImuGpsSource"
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val stateLock = Any()
    private val rotationMatrix = FloatArray(9)
    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                updateFromLocation(location)
            }
        }

    private var started = false
    private var hasRotationMatrix = false
    private var lastLocation: Location? = null
    private var latestLatitude = 0.0
    private var latestLongitude = 0.0
    private var latestAltitude: Double? = null
    private var latestSpeedMps = 0.0
    private var latestHeadingDegrees = 0.0
    private var latestDistanceMeters = 0.0
    private var latestGpsLatG = 0.0
    private var latestGpsLongG = 0.0
    private var latestImuLatG = 0.0
    private var latestImuLongG = 0.0
    private var latestWorldEastMps2 = 0.0
    private var latestWorldNorthMps2 = 0.0

    override suspend fun start() {
        synchronized(stateLock) {
            if (started) return
            started = true
        }
        registerSensors()
        registerLocationUpdates()
        seedLastKnownLocation()
    }

    override suspend fun stop() {
        synchronized(stateLock) {
            if (!started) return
            started = false
        }
        sensorManager.unregisterListener(this)
        runCatching { locationManager.removeUpdates(locationListener) }
            .onFailure { Log.w(tag, "Failed to remove location updates", it) }
    }

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        val snapshot =
            synchronized(stateLock) {
                Snapshot(
                    latitude = latestLatitude,
                    longitude = latestLongitude,
                    altitude = latestAltitude,
                    speedMps = latestSpeedMps,
                    distanceMeters = latestDistanceMeters,
                    gpsLatG = latestGpsLatG,
                    gpsLongG = latestGpsLongG,
                    imuLatG = latestImuLatG,
                    imuLongG = latestImuLongG,
                )
            }

        val speedMph = snapshot.speedMps * METERS_PER_SECOND_TO_MPH
        val gLong = blendLongitudinalG(snapshot.gpsLongG, snapshot.imuLongG).coerceIn(-2.5, 2.5)
        val gLat = blendLateralG(snapshot.gpsLatG, snapshot.imuLatG).coerceIn(-2.5, 2.5)
        val brake = estimateBrake(gLong)
        val throttle = estimateThrottle(speedMph, gLat, gLong, brake)
        val gear = estimateGear(speedMph)

        return TelemetryFrame(
            timeSeconds = elapsedSeconds,
            latitude = snapshot.latitude,
            longitude = snapshot.longitude,
            altitude = snapshot.altitude,
            speedMph = speedMph,
            rpm = estimateRpm(speedMph, gear, throttle),
            throttle = throttle,
            brake = brake,
            steering = if (speedMph > 12.0) (gLat * 18.0).coerceIn(-35.0, 35.0) else 0.0,
            gLat = gLat,
            gLong = gLong,
            gear = gear,
            distanceMeters = snapshot.distanceMeters.coerceAtLeast(elapsedSeconds * snapshot.speedMps),
            telemetrySource = kind,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(stateLock) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    hasRotationMatrix = true
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (!hasRotationMatrix) return
                    val worldEast =
                        rotationMatrix[0] * event.values[0] +
                            rotationMatrix[1] * event.values[1] +
                            rotationMatrix[2] * event.values[2]
                    val worldNorth =
                        rotationMatrix[3] * event.values[0] +
                            rotationMatrix[4] * event.values[1] +
                            rotationMatrix[5] * event.values[2]
                    latestWorldEastMps2 = worldEast.toDouble()
                    latestWorldNorthMps2 = worldNorth.toDouble()
                    updateImuProjectionLocked()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerSensors() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccelerationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationUpdates() {
        if (!hasFineLocationPermission(appContext)) {
            Log.w(tag, "Location permission missing; phone telemetry cannot start.")
            return
        }

        val providers = mutableListOf<String>()
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            providers += LocationManager.GPS_PROVIDER
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers += LocationManager.NETWORK_PROVIDER
        }

        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    250L,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
            }.onFailure {
                Log.w(tag, "Failed to request $provider updates", it)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocation() {
        if (!hasFineLocationPermission(appContext)) return
        val lastKnown =
            sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        if (lastKnown != null) {
            updateFromLocation(lastKnown)
        }
    }

    private fun updateFromLocation(location: Location) {
        synchronized(stateLock) {
            val previousLocation = lastLocation
            val timestampNanos = location.elapsedRealtimeNanos.takeIf { it > 0L } ?: System.nanoTime()
            val deltaSeconds =
                previousLocation?.let { previous ->
                    val previousNanos = previous.elapsedRealtimeNanos.takeIf { it > 0L } ?: timestampNanos
                    ((timestampNanos - previousNanos) / 1_000_000_000.0).takeIf { it in 0.1..5.0 }
                }
            val deltaDistanceMeters =
                previousLocation?.distanceTo(location)?.toDouble()?.takeIf { it >= 0.0 } ?: 0.0
            val derivedSpeedMps =
                deltaSeconds?.let { seconds ->
                    if (seconds > 0.0) deltaDistanceMeters / seconds else 0.0
                }
            val previousSpeedMps = latestSpeedMps
            val previousHeadingDegrees = latestHeadingDegrees

            val speedMps = when {
                location.hasSpeed() && location.speed.isFinite() -> location.speed.toDouble()
                derivedSpeedMps != null -> derivedSpeedMps
                else -> previousSpeedMps
            }
            val headingDegrees = when {
                location.hasBearing() -> location.bearing.toDouble()
                previousLocation != null && deltaDistanceMeters > 2.0 ->
                    bearingDegrees(
                        previousLocation.latitude,
                        previousLocation.longitude,
                        location.latitude,
                        location.longitude,
                    )
                else -> previousHeadingDegrees
            }

            if (deltaSeconds != null && deltaSeconds > 0.0) {
                latestGpsLongG =
                    ((speedMps - previousSpeedMps) / deltaSeconds / STANDARD_GRAVITY_METERS_PER_SECOND)
                        .coerceIn(-2.5, 2.5)
                if (speedMps > 2.5 && deltaDistanceMeters > 0.5) {
                    val deltaHeadingRadians =
                        normalizeRadians(Math.toRadians(headingDegrees - previousHeadingDegrees))
                    val yawRateRadiansPerSecond = deltaHeadingRadians / deltaSeconds
                    latestGpsLatG =
                        (speedMps * yawRateRadiansPerSecond / STANDARD_GRAVITY_METERS_PER_SECOND)
                            .coerceIn(-2.5, 2.5)
                }
            }

            latestLatitude = location.latitude
            latestLongitude = location.longitude
            latestAltitude = if (location.hasAltitude()) location.altitude else latestAltitude
            latestDistanceMeters += deltaDistanceMeters
            latestSpeedMps = speedMps
            latestHeadingDegrees = headingDegrees
            lastLocation = Location(location)
            updateImuProjectionLocked()
        }
    }

    private fun updateImuProjectionLocked() {
        val headingRadians = Math.toRadians(latestHeadingDegrees)
        val forwardEast = sin(headingRadians)
        val forwardNorth = cos(headingRadians)
        val rightEast = cos(headingRadians)
        val rightNorth = -sin(headingRadians)
        val projectedLongitudinal =
            latestWorldEastMps2 * forwardEast + latestWorldNorthMps2 * forwardNorth
        val projectedLateral =
            latestWorldEastMps2 * rightEast + latestWorldNorthMps2 * rightNorth

        latestImuLongG = smooth(latestImuLongG, projectedLongitudinal / STANDARD_GRAVITY_METERS_PER_SECOND)
        latestImuLatG = smooth(latestImuLatG, projectedLateral / STANDARD_GRAVITY_METERS_PER_SECOND)
    }

    private fun blendLongitudinalG(gpsLongG: Double, imuLongG: Double): Double {
        return if (abs(gpsLongG) > 0.01) gpsLongG * 0.7 + imuLongG * 0.3 else imuLongG
    }

    private fun blendLateralG(gpsLatG: Double, imuLatG: Double): Double {
        return if (abs(gpsLatG) > 0.01) gpsLatG * 0.65 + imuLatG * 0.35 else imuLatG
    }

    private fun estimateBrake(gLong: Double): Double {
        if (gLong >= -0.04) return 0.0
        return (((-gLong - 0.04) / 1.15) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun estimateThrottle(speedMph: Double, gLat: Double, gLong: Double, brake: Double): Double {
        if (brake > 2.0) return 0.0
        return when {
            gLong > 0.05 -> (18.0 + (gLong / 0.5) * 82.0).coerceIn(0.0, 100.0)
            abs(gLat) > 0.45 && speedMph > 30.0 -> 14.0
            speedMph > 55.0 -> 22.0
            speedMph > 20.0 -> 14.0
            else -> 8.0
        }
    }

    private fun estimateGear(speedMph: Double): Int {
        return when {
            speedMph > 110.0 -> 6
            speedMph > 88.0 -> 5
            speedMph > 64.0 -> 4
            speedMph > 42.0 -> 3
            speedMph > 18.0 -> 2
            else -> 1
        }
    }

    private fun estimateRpm(speedMph: Double, gear: Int, throttle: Double): Int {
        val baseRpm = 1400.0 + speedMph * (78.0 - gear * 5.0)
        return (baseRpm + throttle * 18.0).roundToInt().coerceIn(1100, 8800)
    }

    private fun smooth(previous: Double, sample: Double): Double {
        return (previous * 0.7 + sample * 0.3).coerceIn(-2.5, 2.5)
    }

    private fun bearingDegrees(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val fromLatRad = Math.toRadians(fromLat)
        val toLatRad = Math.toRadians(toLat)
        val deltaLonRad = Math.toRadians(toLon - fromLon)
        val y = sin(deltaLonRad) * cos(toLatRad)
        val x = cos(fromLatRad) * sin(toLatRad) - sin(fromLatRad) * cos(toLatRad) * cos(deltaLonRad)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun normalizeRadians(value: Double): Double {
        var angle = value
        while (angle > Math.PI) angle -= Math.PI * 2.0
        while (angle < -Math.PI) angle += Math.PI * 2.0
        return angle
    }

    private data class Snapshot(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val speedMps: Double,
        val distanceMeters: Double,
        val gpsLatG: Double,
        val gpsLongG: Double,
        val imuLatG: Double,
        val imuLongG: Double,
    )

    companion object {
        private const val STANDARD_GRAVITY_METERS_PER_SECOND = 9.80665
        private const val METERS_PER_SECOND_TO_MPH = 2.2369362920544

        fun hasFineLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
