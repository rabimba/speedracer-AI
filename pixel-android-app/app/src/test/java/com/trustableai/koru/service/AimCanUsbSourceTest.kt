package com.trustableai.koru.service

import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceHealth
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.model.AimCanBitrate
import com.trustableai.koru.runtime.TrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AimCanUsbSourceTest {
    @Test
    fun `fresh required CAN frames emit aim can full telemetry`() {
        val now = 10_000L
        val source = AimCanUsbSource(
            canClient = FakeAimCanClient(fullCanSample(now)),
            elapsedRealtimeMs = { now },
        )

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 2.0)

        assertEquals(TelemetrySourceKind.AIM_CAN_USB, frame.telemetrySource)
        assertEquals("aim_can_full", frame.sourceHealth?.fallbackStage)
        assertEquals("aim_can", frame.sourceHealth?.motionSource)
        assertEquals(92.4, frame.speedMph, 0.01)
        assertEquals(6400, frame.rpm)
        assertEquals(48.5, frame.throttle, 0.01)
        assertEquals(50.0, frame.brake, 0.01)
        assertEquals(38.16272, frame.latitude, 0.000001)
        assertEquals(-122.455, frame.longitude, 0.000001)
        assertEquals(0.82, frame.gLat, 0.01)
        assertEquals(-0.41, frame.gLong, 0.01)
        assertEquals(600.0, frame.canVehicleDiagnostics?.brakePressurePsi ?: -1.0, 0.01)
        assertEquals(true, frame.sourceHealth?.canConnected)
        assertEquals(true, frame.sourceHealth?.signUnverified)
    }

    @Test
    fun `stale CAN GPS falls back to RaceBox motion while preserving fresh CAN vehicle channels`() {
        val now = 20_000L
        val staleGpsCan = fullCanSample(now).copy(
            gpsLatitude = 38.16272,
            gpsLongitude = -122.455,
            channelUpdatedAtElapsedMs = mapOf(
                AimCanFrameIds.CORE to now - 40,
                AimCanFrameIds.CONTROLS to now - 40,
                AimCanFrameIds.MOTION to now - AIM_CAN_FAST_STALE_MS - 1,
                AimCanFrameIds.GPS_POSITION to now - AIM_CAN_SLOW_STALE_MS - 1,
            ),
        )
        val source = AimCanUsbSource(
            canClient = FakeAimCanClient(staleGpsCan),
            raceBoxClient = FakeRaceBoxClient(raceBoxSample(now)),
            elapsedRealtimeMs = { now },
        )

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 3.0)

        assertEquals("aim_can_racebox_motion", frame.sourceHealth?.fallbackStage)
        assertEquals("racebox", frame.sourceHealth?.motionSource)
        assertEquals(38.25, frame.latitude, 0.000001)
        assertEquals(-122.30, frame.longitude, 0.000001)
        assertEquals(6400, frame.rpm)
        assertEquals(48.5, frame.throttle, 0.01)
        assertEquals(50.0, frame.brake, 0.01)
        assertEquals(600.0, frame.canVehicleDiagnostics?.brakePressurePsi ?: -1.0, 0.01)
    }

    @Test
    fun `stale CAN and missing RaceBox uses phone motion with CAN enrichment when vehicle channels are fresh`() {
        val now = 30_000L
        val staleGpsCan = fullCanSample(now).copy(
            channelUpdatedAtElapsedMs = mapOf(
                AimCanFrameIds.CORE to now - 40,
                AimCanFrameIds.CONTROLS to now - 40,
                AimCanFrameIds.GPS_POSITION to now - AIM_CAN_SLOW_STALE_MS - 1,
                AimCanFrameIds.MOTION to now - AIM_CAN_FAST_STALE_MS - 1,
            ),
        )
        val source = AimCanUsbSource(
            canClient = FakeAimCanClient(staleGpsCan),
            raceBoxClient = FakeRaceBoxClient(null),
            phoneFallbackSource = FakePhoneSource(phoneFrame()),
            elapsedRealtimeMs = { now },
        )

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 4.0)

        assertEquals("aim_can_phone_motion", frame.sourceHealth?.fallbackStage)
        assertEquals("phone", frame.sourceHealth?.motionSource)
        assertEquals(44.0, frame.speedMph, 0.01)
        assertEquals(6400, frame.rpm)
        assertEquals(48.5, frame.throttle, 0.01)
        assertEquals(50.0, frame.brake, 0.01)
    }

    @Test
    fun `missing all real data emits no live data`() {
        val now = 40_000L
        val source = AimCanUsbSource(
            canClient = FakeAimCanClient(null),
            raceBoxClient = FakeRaceBoxClient(null),
            elapsedRealtimeMs = { now },
        )

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 1.0)

        assertEquals(0.0, frame.speedMph, 0.0)
        assertNull(frame.rpm)
        assertEquals("no_live_data", frame.sourceHealth?.fallbackStage)
        assertEquals(false, frame.sourceHealth?.motionConnected)
    }

    @Test
    fun `raw CAN status is surfaced before mapped AiM frames are decoded`() {
        val now = 45_000L
        val source = AimCanUsbSource(
            canClient = FakeAimCanClient(
                sample = null,
                status = AimCanClientStatus(
                    connected = true,
                    detail = "CANable connected; waiting for mapped frames",
                    usbDeviceName = "CANable2",
                    bitrate = AimCanBitrate.S6_500KBPS,
                    waitingForFrames = false,
                    rawCanSample = "t55520102",
                    rawCanSamplesById = mapOf(0x555 to "t55520102"),
                    frameRatesHz = mapOf(0x555 to 19.5),
                ),
            ),
            elapsedRealtimeMs = { now },
        )

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 1.0)

        assertEquals("no_live_data", frame.sourceHealth?.fallbackStage)
        assertEquals(true, frame.sourceHealth?.canConnected)
        assertEquals("S6 500 kbps", frame.sourceHealth?.canBitrate)
        assertEquals("t55520102", frame.sourceHealth?.rawCanSample)
        assertEquals("t55520102", frame.sourceHealth?.rawCanSamplesById?.get("0x555"))
        assertEquals(19.5, frame.sourceHealth?.canFrameRatesHz?.get("0x555") ?: -1.0, 0.01)
    }

    private fun fullCanSample(now: Long): AimCanSample {
        return AimCanSample(
            receivedAtElapsedMs = now - 40,
            rpm = 6400,
            gpsSpeedMph = 92.4,
            gearRaw = 0,
            waterTempC = 91.0,
            brakePressurePsi = 600.0,
            pedalPositionPercent = 48.5,
            brakeSwitchApplied = true,
            steeringAngleDeg = -8.0,
            yawRateDegPerSec = 12.0,
            lateralG = 0.82,
            inlineG = -0.41,
            gpsLatitude = 38.16272,
            gpsLongitude = -122.455,
            oilFilterTempC = 105.0,
            batteryVoltage = 13.8,
            channelUpdatedAtElapsedMs = mapOf(
                AimCanFrameIds.CORE to now - 40,
                AimCanFrameIds.CONTROLS to now - 40,
                AimCanFrameIds.MOTION to now - 40,
                AimCanFrameIds.GPS_POSITION to now - 40,
                AimCanFrameIds.PRESSURE_RATES to now - 40,
                AimCanFrameIds.AUX to now - 40,
            ),
            frameRatesHz = mapOf(
                AimCanFrameIds.CORE to 10.0,
                AimCanFrameIds.CONTROLS to 50.0,
                AimCanFrameIds.MOTION to 50.0,
                AimCanFrameIds.GPS_POSITION to 20.0,
            ),
            rawCanSample = "t4238B0FF78005200D7FF",
        )
    }

    private fun raceBoxSample(now: Long): RaceBoxSample {
        return RaceBoxSample(
            receivedAtElapsedMs = now - 20,
            iTowMs = 1_000L,
            latitude = 38.25,
            longitude = -122.30,
            altitudeMeters = 44.0,
            speedMps = 41.0,
            headingDegrees = 90.0,
            gLong = -0.2,
            gLat = 0.4,
            gVert = 0.0,
            fixStatus = 3,
            fixOk = true,
            satellites = 12,
            horizontalAccuracyMeters = 0.8,
            batteryPercent = 90,
        )
    }

    private fun phoneFrame(): TelemetryFrame {
        return TelemetryFrame(
            timeSeconds = 0.0,
            latitude = 38.15,
            longitude = -122.45,
            speedMph = 44.0,
            throttle = 20.0,
            brake = 2.0,
            steering = 1.0,
            gLat = 0.2,
            gLong = 0.1,
            telemetrySource = TelemetrySourceKind.PHONE_IMU_GPS,
            sourceHealth = TelemetrySourceHealth(
                status = "Phone GPS/IMU live",
                motionSource = "phone",
                motionConnected = true,
                motionFixGood = true,
                motionSampleAgeMs = 80L,
                fallbackStage = "phone_only",
                phoneMotionConnected = true,
                phoneMotionFixGood = true,
                phoneMotionSampleAgeMs = 80L,
            ),
        )
    }

    private class FakeAimCanClient(
        private val sample: AimCanSample?,
        private val status: AimCanClientStatus? = null,
    ) : AimCanDataClient {
        override suspend fun start() = Unit

        override suspend fun stop() = Unit

        override fun latestSample(): AimCanSample? = sample

        override fun status(): AimCanClientStatus = status ?: AimCanClientStatus(
            connected = sample != null,
            detail = if (sample == null) "fake CAN unavailable" else "fake CAN live",
            usbDeviceName = "RH-02 PRO",
            decodeErrors = sample?.decodeErrors ?: 0,
        )
    }

    private class FakeRaceBoxClient(
        private val sample: RaceBoxSample?,
    ) : RaceBoxDataClient {
        override suspend fun start() = Unit

        override suspend fun stop() = Unit

        override fun latestSample(): RaceBoxSample? = sample

        override fun status(): RaceBoxClientStatus = RaceBoxClientStatus(
            connected = sample != null,
            scanning = false,
            detail = if (sample == null) "fake RaceBox unavailable" else "fake RaceBox live",
        )
    }

    private class FakePhoneSource(
        private val frame: TelemetryFrame,
    ) : TelemetrySource {
        override val kind: TelemetrySourceKind = TelemetrySourceKind.PHONE_IMU_GPS

        override suspend fun start() = Unit

        override suspend fun stop() = Unit

        override fun nextFrame(step: Int, track: com.trustableai.koru.model.Track, elapsedSeconds: Double): TelemetryFrame {
            return frame.copy(timeSeconds = elapsedSeconds)
        }
    }
}
