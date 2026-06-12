package com.trustableai.koru.runtime

import com.trustableai.koru.model.AudioDispatchEvent
import com.trustableai.koru.model.AudioDispatchStatus
import com.trustableai.koru.model.CanVehicleDiagnostics
import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceHealth
import com.trustableai.koru.model.TelemetrySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedSessionRecorderTest {
    @Test
    fun `schema v2 json includes audio events linked to decision ids`() {
        val recorder = RecordedSessionRecorder(persistArtifacts = false)
        recorder.start(SessionMode.TELEMETRY, "Sonoma Raceway", "superaj")
        recorder.recordFrame(
            TelemetryFrame(
                timeSeconds = 1.0,
                latitude = 38.16272,
                longitude = -122.455,
                speedMph = 54.0,
                throttle = 32.0,
                brake = 0.0,
                gLat = 0.4,
                gLong = 0.1,
                telemetrySource = TelemetrySourceKind.RACEBOX_OBD_FUSION,
                sourceHealth = TelemetrySourceHealth(
                    status = "RaceBox unavailable | OBDLink live",
                    motionSource = "phone",
                    motionConnected = true,
                    motionFixGood = true,
                    motionSampleAgeMs = 120L,
                    fallbackStage = "phone_obd_fusion",
                    degradedReason = "racebox_unavailable_using_phone_motion",
                    phoneMotionConnected = true,
                    phoneMotionFixGood = true,
                    phoneMotionSampleAgeMs = 120L,
                    raceBoxConnected = false,
                    obdConnected = true,
                    obdStale = false,
                ),
            ),
        )
        val decision =
            CoachingDecision(
                path = CoachingPath.HOT,
                action = CoachAction.BRAKE,
                text = "Brake now",
                priority = 0,
                cornerPhase = CornerPhase.BRAKE_ZONE,
                timestampMs = 123L,
                backend = RuntimeBackend.DETERMINISTIC,
                id = "decision-p0",
            )
        recorder.recordDecision(decision)
        recorder.recordAudioEvent(
            AudioDispatchEvent(
                decisionId = decision.id,
                utteranceId = "hot-123",
                action = CoachAction.BRAKE,
                priority = 0,
                requestedAtMs = 123L,
                dispatchLatencyMs = 6L,
                status = AudioDispatchStatus.CLIP_STARTED,
            ),
        )

        val artifact = recorder.finish() ?: error("expected artifact")
        val json = recorder.artifactJson(artifact)
        val audioEvents = json.getJSONArray("audioEvents")

        assertEquals(2, json.getInt("schemaVersion"))
        assertEquals("completed", json.getString("endedReason"))
        assertEquals(1, json.getInt("totalFrameCount"))
        assertEquals(1, json.getInt("embeddedFrameCount"))
        assertEquals("decision-p0", json.getJSONArray("decisions").getJSONObject(0).getString("id"))
        val sourceHealth = json.getJSONArray("frames").getJSONObject(0).getJSONObject("sourceHealth")
        assertEquals("phone_obd_fusion", sourceHealth.getString("fallbackStage"))
        assertEquals("phone", sourceHealth.getString("motionSource"))
        assertEquals("racebox_unavailable_using_phone_motion", sourceHealth.getString("degradedReason"))
        assertEquals(120L, sourceHealth.getLong("phoneMotionSampleAgeMs"))
        assertEquals("decision-p0", audioEvents.getJSONObject(0).getString("decisionId"))
        assertEquals("CLIP_STARTED", audioEvents.getJSONObject(0).getString("status"))
        assertTrue(audioEvents.getJSONObject(0).getLong("dispatchLatencyMs") < 100L)
    }

    @Test
    fun `schema v2 json includes CAN diagnostics and CAN source health`() {
        val recorder = RecordedSessionRecorder(persistArtifacts = false)
        recorder.start(SessionMode.TELEMETRY, "Sonoma Raceway", "superaj")
        recorder.recordFrame(
            TelemetryFrame(
                timeSeconds = 1.0,
                latitude = 38.16272,
                longitude = -122.455,
                speedMph = 92.0,
                rpm = 6400,
                throttle = 48.5,
                brake = 50.0,
                steering = -8.0,
                gLat = 0.82,
                gLong = -0.41,
                telemetrySource = TelemetrySourceKind.AIM_CAN_USB,
                canVehicleDiagnostics = CanVehicleDiagnostics(
                    brakePressureRaw = 6000,
                    brakePressurePsi = 600.0,
                    brakePressureZeroOffsetRaw = 10,
                    brakePressureCalibratedPsi = 599.0,
                    brakePressureZeroOffsetPsi = 1.0,
                    pedalPositionRaw = 4850,
                    pedalPositionPercent = 48.5,
                    brakeSwitchRaw = 1,
                    brakeSwitchApplied = true,
                    batteryVoltage = 13.8,
                    frameAgesMs = mapOf("0x420" to 40L, "0x423" to 35L),
                    frameStale = mapOf("0x420" to false, "0x452" to false),
                    rawFrameSamples = mapOf("0x422" to "t42287017621201000000"),
                ),
                sourceHealth = TelemetrySourceHealth(
                    status = "AiM CAN USB live",
                    motionSource = "aim_can",
                    motionConnected = true,
                    motionFixGood = true,
                    motionSampleAgeMs = 40L,
                    fallbackStage = "aim_can_full",
                    canConnected = true,
                    canFrameAgesMs = mapOf("0x420" to 40L, "0x423" to 35L),
                    canFrameStale = mapOf("0x420" to false, "0x452" to false),
                    canFrameRatesHz = mapOf("0x420" to 10.0, "0x423" to 50.0),
                    canDecodeErrors = 1,
                    canBitrate = "S8 1 Mbps",
                    canWaitingForFrames = false,
                    usbDeviceName = "RH-02 PRO",
                    rawCanSample = "t4238B0FF78005200D7FF",
                    rawCanSamplesById = mapOf("0x422" to "t42287017621201000000"),
                    signUnverified = true,
                ),
            ),
        )

        val artifact = recorder.finish() ?: error("expected artifact")
        val frame = recorder.artifactJson(artifact).getJSONArray("frames").getJSONObject(0)
        val canDiagnostics = frame.getJSONObject("canVehicleDiagnostics")
        val sourceHealth = frame.getJSONObject("sourceHealth")

        assertEquals("aim_can_usb", frame.getString("telemetrySource"))
        assertEquals(6000, canDiagnostics.getInt("brakePressureRaw"))
        assertEquals(600.0, canDiagnostics.getDouble("brakePressurePsi"), 0.01)
        assertEquals(10, canDiagnostics.getInt("brakePressureZeroOffsetRaw"))
        assertEquals(599.0, canDiagnostics.getDouble("brakePressureCalibratedPsi"), 0.01)
        assertEquals(4850, canDiagnostics.getInt("pedalPositionRaw"))
        assertEquals(true, canDiagnostics.getBoolean("brakeSwitchApplied"))
        assertEquals(40L, canDiagnostics.getJSONObject("frameAgesMs").getLong("0x420"))
        assertEquals("t42287017621201000000", canDiagnostics.getJSONObject("rawFrameSamples").getString("0x422"))
        assertEquals(true, sourceHealth.getBoolean("canConnected"))
        assertEquals("RH-02 PRO", sourceHealth.getString("usbDeviceName"))
        assertEquals("t4238B0FF78005200D7FF", sourceHealth.getString("rawCanSample"))
        assertEquals("t42287017621201000000", sourceHealth.getJSONObject("rawCanSamplesById").getString("0x422"))
        assertEquals(true, sourceHealth.getBoolean("signUnverified"))
        assertEquals(50.0, sourceHealth.getJSONObject("canFrameRatesHz").getDouble("0x423"), 0.01)
        assertEquals("S8 1 Mbps", sourceHealth.getString("canBitrate"))
        assertEquals(false, sourceHealth.getBoolean("canWaitingForFrames"))
    }

    @Test
    fun `recorder keeps bounded preview for long field runs`() {
        val recorder = RecordedSessionRecorder(persistArtifacts = false)
        recorder.start(SessionMode.TELEMETRY, "Sonoma Raceway", "superaj")

        repeat(30_000) { index ->
            recorder.recordFrame(
                TelemetryFrame(
                    timeSeconds = index * 0.02,
                    latitude = 38.16272,
                    longitude = -122.455,
                    speedMph = 60.0,
                    throttle = 20.0,
                    brake = 0.0,
                    gLat = 0.1,
                    gLong = 0.0,
                    telemetrySource = TelemetrySourceKind.PHONE_IMU_GPS,
                ),
            )
        }

        val activeStatus = recorder.status()
        assertEquals(true, activeStatus.active)
        assertEquals(30_000, activeStatus.frameCount)

        val artifact = recorder.finish() ?: error("expected artifact")
        assertEquals(30_000, artifact.totalFrameCount)
        assertEquals(30_000, artifact.summary.frameCount)
        assertTrue("preview should stay bounded", artifact.frames.size <= 600)
        assertTrue("preview should keep useful samples", artifact.frames.size > 100)
    }

    @Test
    fun `suppressed audio events are serialized for field debugging`() {
        val recorder = RecordedSessionRecorder(persistArtifacts = false)
        recorder.start(SessionMode.TELEMETRY, "Sonoma Raceway", "superaj")

        recorder.recordAudioEvent(
            AudioDispatchEvent(
                decisionId = "decision-1",
                utteranceId = "hot-1",
                action = CoachAction.WAIT,
                priority = 2,
                requestedAtMs = 200L,
                dispatchLatencyMs = 0L,
                status = AudioDispatchStatus.SUPPRESSED,
                fallbackReason = "phase_gate",
            ),
        )

        val artifact = recorder.finish() ?: error("expected artifact")
        val audioEvent = recorder.artifactJson(artifact)
            .getJSONArray("audioEvents")
            .getJSONObject(0)

        assertEquals("SUPPRESSED", audioEvent.getString("status"))
        assertEquals("phase_gate", audioEvent.getString("fallbackReason"))
        assertEquals("DECISION", audioEvent.getString("scope"))
    }
}
