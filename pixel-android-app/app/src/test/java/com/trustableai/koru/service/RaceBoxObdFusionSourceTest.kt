package com.trustableai.koru.service

import com.trustableai.koru.runtime.TrackCatalog
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceHealth
import com.trustableai.koru.model.TelemetrySourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceBoxObdFusionSourceTest {
    @Test
    fun `fuses fresh RaceBox motion with fresh OBD enrichment`() = runBlocking {
        val now = 10_000L
        val raceBox = FakeRaceBoxClient(
            RaceBoxSample(
                receivedAtElapsedMs = now - 20,
                iTowMs = 500_000,
                latitude = 38.16272,
                longitude = -122.455,
                altitudeMeters = 52.0,
                speedMps = 44.704,
                headingDegrees = 120.0,
                gLong = -0.72,
                gLat = 0.45,
                gVert = 0.01,
                fixStatus = 3,
                fixOk = true,
                satellites = 12,
                horizontalAccuracyMeters = 0.8,
                batteryPercent = 86,
            ),
        )
        val obd = FakeObdClient(
            ObdSample(
                receivedAtElapsedMs = now - 80,
                rpm = 6200,
                speedMph = 99.0,
                throttlePercent = 12.5,
                coolantTempC = 91.0,
                oilTempC = 102.0,
                engineLoadPercent = 82.0,
                mafGramsPerSecond = 98.4,
                channelUpdatedAtElapsedMs = mapOf(
                    ObdPid.ENGINE_RPM to now - 80,
                    ObdPid.VEHICLE_SPEED to now - 80,
                    ObdPid.THROTTLE_POSITION to now - 80,
                    ObdPid.COOLANT_TEMP to now - 80,
                    ObdPid.ENGINE_OIL_TEMP to now - 80,
                    ObdPid.ENGINE_LOAD to now - 80,
                    ObdPid.MAF to now - 80,
                ),
            ),
        )
        val source = RaceBoxObdFusionSource(raceBox, obd) { now }

        source.start()
        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 3.0)

        assertTrue(raceBox.started)
        assertTrue(obd.started)
        assertEquals(RACEBOX_FRAME_INTERVAL_NANOS, source.frameIntervalNanos)
        assertEquals(100.0, frame.speedMph, 0.02)
        assertEquals(6200, frame.rpm)
        assertEquals(12.5, frame.throttle, 0.01)
        assertEquals(91.0, frame.coolantTempC ?: -1.0, 0.01)
        assertEquals(102.0, frame.oilTempC ?: -1.0, 0.01)
        assertEquals(82.0, frame.vehicleDiagnostics?.engineLoadPercent ?: -1.0, 0.01)
        assertEquals(98.4, frame.vehicleDiagnostics?.mafGramsPerSecond ?: -1.0, 0.01)
        assertTrue(frame.brake > 50.0)
        assertNull(frame.steering)
        assertEquals(false, frame.sourceHealth?.obdStale)
        assertEquals(true, frame.sourceHealth?.raceBoxFixGood)
        assertEquals("bluetooth", frame.sourceHealth?.obdTransport)
        assertEquals(false, frame.sourceHealth?.obdChannelStale?.get("010C"))
        assertEquals("full", frame.sourceHealth?.fallbackStage)
        assertEquals("racebox", frame.sourceHealth?.motionSource)
    }

    @Test
    fun `stale OBD emits RaceBox only stage and does not trust OBD values`() {
        val now = 20_000L
        val source = RaceBoxObdFusionSource(
            raceBoxClient = FakeRaceBoxClient(
                RaceBoxSample(
                    receivedAtElapsedMs = now - 10,
                    iTowMs = 900_000,
                    latitude = 38.0,
                    longitude = -122.0,
                    altitudeMeters = null,
                    speedMps = 30.0,
                    headingDegrees = 90.0,
                    gLong = 0.20,
                    gLat = 0.10,
                    gVert = 0.0,
                    fixStatus = 3,
                    fixOk = true,
                    satellites = 10,
                    horizontalAccuracyMeters = 1.0,
                    batteryPercent = 70,
                ),
            ),
            obdClient = FakeObdClient(
                ObdSample(
                    receivedAtElapsedMs = now - OBD_STALE_MS - 1,
                    rpm = 7000,
                    speedMph = 70.0,
                    throttlePercent = 95.0,
                    channelUpdatedAtElapsedMs = mapOf(
                        ObdPid.ENGINE_RPM to now - OBD_STALE_MS - 1,
                        ObdPid.VEHICLE_SPEED to now - OBD_STALE_MS - 1,
                        ObdPid.THROTTLE_POSITION to now - OBD_STALE_MS - 1,
                    ),
                ),
            ),
        ) { now }

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 1.0)

        assertNull(frame.rpm)
        assertTrue(frame.throttle > 0.0)
        assertTrue(frame.throttle < 95.0)
        assertEquals(true, frame.sourceHealth?.obdStale)
        assertEquals("racebox_only", frame.sourceHealth?.fallbackStage)
        assertEquals("racebox", frame.sourceHealth?.motionSource)
        assertEquals("obd_unavailable_or_stale", frame.sourceHealth?.degradedReason)
    }

    @Test
    fun `missing RaceBox with fresh OBD uses phone OBD fusion`() {
        val now = 25_000L
        val phone = FakePhoneSource(
            phoneFrame().copy(
                rpm = 2500,
                throttle = 21.0,
            ),
        )
        val source = RaceBoxObdFusionSource(
            raceBoxClient = FakeRaceBoxClient(null),
            obdClient = FakeObdClient(
                ObdSample(
                    receivedAtElapsedMs = now - 60,
                    rpm = 6400,
                    speedMph = 82.0,
                    throttlePercent = 72.0,
                    channelUpdatedAtElapsedMs = mapOf(
                        ObdPid.ENGINE_RPM to now - 60,
                        ObdPid.VEHICLE_SPEED to now - 60,
                        ObdPid.THROTTLE_POSITION to now - 60,
                    ),
                ),
            ),
            phoneFallbackSource = phone,
        ) { now }

        val frame = runBlocking {
            source.start()
            source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 4.0)
        }

        assertTrue(phone.started)
        assertEquals(44.0, frame.speedMph, 0.01)
        assertEquals(6400, frame.rpm)
        assertEquals(72.0, frame.throttle, 0.01)
        assertEquals(TelemetrySourceKind.RACEBOX_OBD_FUSION, frame.telemetrySource)
        assertEquals("phone_obd_fusion", frame.sourceHealth?.fallbackStage)
        assertEquals("phone", frame.sourceHealth?.motionSource)
        assertEquals(true, frame.sourceHealth?.phoneMotionConnected)
        assertEquals("racebox_unavailable_using_phone_motion", frame.sourceHealth?.degradedReason)
    }

    @Test
    fun `missing RaceBox with stale OBD uses phone only stage`() {
        val now = 27_000L
        val source = RaceBoxObdFusionSource(
            raceBoxClient = FakeRaceBoxClient(null),
            obdClient = FakeObdClient(
                ObdSample(
                    receivedAtElapsedMs = now - OBD_STALE_MS - 10,
                    rpm = 7000,
                    speedMph = 88.0,
                    throttlePercent = 91.0,
                    channelUpdatedAtElapsedMs = mapOf(
                        ObdPid.ENGINE_RPM to now - OBD_STALE_MS - 10,
                        ObdPid.VEHICLE_SPEED to now - OBD_STALE_MS - 10,
                        ObdPid.THROTTLE_POSITION to now - OBD_STALE_MS - 10,
                    ),
                ),
            ),
            phoneFallbackSource = FakePhoneSource(phoneFrame().copy(throttle = 18.0)),
        ) { now }

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 5.0)

        assertEquals(44.0, frame.speedMph, 0.01)
        assertNull(frame.rpm)
        assertEquals(18.0, frame.throttle, 0.01)
        assertEquals("phone_only", frame.sourceHealth?.fallbackStage)
        assertEquals("phone", frame.sourceHealth?.motionSource)
        assertEquals("racebox_unavailable_obd_unavailable_or_stale", frame.sourceHealth?.degradedReason)
    }

    @Test
    fun `missing RaceBox fix without phone fallback returns no live data`() {
        val now = 30_000L
        val source = RaceBoxObdFusionSource(
            raceBoxClient = FakeRaceBoxClient(
                RaceBoxSample(
                    receivedAtElapsedMs = now - 10,
                    iTowMs = 1_000L,
                    latitude = 38.0,
                    longitude = -122.0,
                    altitudeMeters = null,
                    speedMps = 40.0,
                    headingDegrees = 0.0,
                    gLong = -0.5,
                    gLat = 0.3,
                    gVert = 0.0,
                    fixStatus = 2,
                    fixOk = false,
                    satellites = 5,
                    horizontalAccuracyMeters = 12.0,
                    batteryPercent = 50,
                ),
            ),
            obdClient = FakeObdClient(null),
        ) { now }

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 2.0)

        assertEquals(0.0, frame.speedMph, 0.0)
        assertEquals(TrackCatalog.sonomaRaceway.centerLat, frame.latitude, 0.000001)
        assertEquals(false, frame.sourceHealth?.raceBoxFixGood)
        assertEquals(true, frame.sourceHealth?.raceBoxConnected)
        assertEquals("no_live_data", frame.sourceHealth?.fallbackStage)
        assertEquals(false, frame.sourceHealth?.motionConnected)
        assertEquals("racebox_and_phone_motion_unavailable", frame.sourceHealth?.degradedReason)
    }

    @Test
    fun `no live data source keeps hardware sessions off synthetic telemetry`() {
        val source = NoLiveDataSource(
            kind = TelemetrySourceKind.RACEBOX_OBD_FUSION,
            detail = "No real telemetry source available",
        )

        val frame = source.nextFrame(1, TrackCatalog.sonomaRaceway, elapsedSeconds = 6.0)

        assertEquals(TelemetrySourceKind.RACEBOX_OBD_FUSION, frame.telemetrySource)
        assertEquals("no_live_data", frame.sourceHealth?.fallbackStage)
        assertEquals(false, frame.sourceHealth?.motionConnected)
    }

    private fun phoneFrame(): TelemetryFrame {
        return TelemetryFrame(
            timeSeconds = 0.0,
            latitude = 38.16272,
            longitude = -122.455,
            altitude = 52.0,
            speedMph = 44.0,
            rpm = null,
            throttle = 15.0,
            brake = 0.0,
            steering = 2.0,
            gLat = 0.22,
            gLong = 0.08,
            gear = 3,
            distanceMeters = 12.0,
            telemetrySource = TelemetrySourceKind.PHONE_IMU_GPS,
            sourceHealth = TelemetrySourceHealth(
                status = "Phone GPS/IMU live",
                motionSource = "phone",
                motionConnected = true,
                motionFixGood = true,
                motionSampleAgeMs = 80,
                fallbackStage = "phone_only",
                phoneMotionConnected = true,
                phoneMotionFixGood = true,
                phoneMotionSampleAgeMs = 80,
            ),
        )
    }

    private class FakeRaceBoxClient(
        private val sample: RaceBoxSample?,
    ) : RaceBoxDataClient {
        var started = false

        override suspend fun start() {
            started = true
        }

        override suspend fun stop() {
            started = false
        }

        override fun latestSample(): RaceBoxSample? = sample

        override fun status(): RaceBoxClientStatus = RaceBoxClientStatus(
            connected = sample != null,
            scanning = false,
            detail = "fake RaceBox",
        )
    }

    private class FakePhoneSource(
        private val frame: TelemetryFrame,
    ) : TelemetrySource {
        override val kind: TelemetrySourceKind = TelemetrySourceKind.PHONE_IMU_GPS
        var started = false

        override suspend fun start() {
            started = true
        }

        override suspend fun stop() {
            started = false
        }

        override fun nextFrame(step: Int, track: com.trustableai.koru.model.Track, elapsedSeconds: Double): TelemetryFrame {
            return frame.copy(timeSeconds = elapsedSeconds)
        }
    }

    private class FakeObdClient(
        private val sample: ObdSample?,
    ) : ObdDataClient {
        var started = false

        override suspend fun start() {
            started = true
        }

        override suspend fun stop() {
            started = false
        }

        override fun latestSample(): ObdSample? = sample

        override fun status(): ObdClientStatus = ObdClientStatus(
            connected = sample != null,
            detail = if (sample == null) "fake OBD waiting" else "fake OBD live",
            transport = ObdTransportKind.BLUETOOTH,
            supportedPids = sample?.channelUpdatedAtElapsedMs?.keys.orEmpty().map { pid -> pid.mode01Pid }.toSet(),
        )
    }
}
