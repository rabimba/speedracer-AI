package com.trustableai.koru.runtime

import android.content.Context
import com.trustableai.koru.model.AudioDispatchEvent
import com.trustableai.koru.model.CanVehicleDiagnostics
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.RecordedSessionSummary
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.VehicleDiagnostics
import com.trustableai.koru.model.bridgeValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class RecordedSessionRecorder(
    private val context: Context? = null,
    private val persistArtifacts: Boolean = true,
) {
    private var activeSession: ActiveSession? = null

    @Synchronized
    fun start(mode: SessionMode, trackName: String, coachId: String, sessionGoals: List<SessionGoal> = emptyList()) {
        activeSession = ActiveSession(
            id = "koru-${mode.bridgeValue()}-${System.currentTimeMillis()}",
            mode = mode,
            trackName = trackName,
            coachId = coachId,
            sessionGoals = sessionGoals.take(3),
            startedAtMs = System.currentTimeMillis(),
            frames = mutableListOf(),
            decisions = mutableListOf(),
            audioEvents = mutableListOf(),
        )
    }

    @Synchronized
    fun recordFrame(frame: TelemetryFrame) {
        activeSession?.frames?.add(frame)
    }

    @Synchronized
    fun recordDecision(decision: CoachingDecision) {
        activeSession?.decisions?.add(decision)
    }

    @Synchronized
    fun recordAudioEvent(event: AudioDispatchEvent) {
        activeSession?.audioEvents?.add(event)
    }

    @Synchronized
    fun finish(): RecordedSessionArtifact? {
        val session = activeSession ?: return null
        activeSession = null

        val endedAtMs = System.currentTimeMillis()
        val summary = RecordedSessionSummary(
            sessionId = session.id,
            mode = session.mode,
            trackName = session.trackName,
            coachId = session.coachId,
            frameCount = session.frames.size,
            decisionCount = session.decisions.size,
            durationSeconds = ((endedAtMs - session.startedAtMs) / 1000.0),
        )
        val artifact = RecordedSessionArtifact(
            schemaVersion = 2,
            id = session.id,
            mode = session.mode,
            trackName = session.trackName,
            coachId = session.coachId,
            startedAtMs = session.startedAtMs,
            endedAtMs = endedAtMs,
            summary = summary,
            sessionGoals = session.sessionGoals.toList(),
            frames = session.frames.toList(),
            decisions = session.decisions.toList(),
            audioEvents = session.audioEvents.toList(),
        )
        persist(artifact)
        return artifact
    }

    @Synchronized
    fun discard() {
        activeSession = null
    }

    private fun persist(artifact: RecordedSessionArtifact) {
        if (!persistArtifacts) return
        val appContext = context ?: return
        val sessionDir = File(appContext.filesDir, "recorded_sessions").apply { mkdirs() }
        val file = File(sessionDir, "${artifact.id}.json")
        file.writeText(artifactJson(artifact).toString())
    }

    internal fun artifactJson(artifact: RecordedSessionArtifact): JSONObject {
        return JSONObject()
            .put("schemaVersion", artifact.schemaVersion)
            .put("id", artifact.id)
            .put("mode", artifact.mode.bridgeValue())
            .put("trackName", artifact.trackName)
            .put("coachId", artifact.coachId)
            .put("startedAt", artifact.startedAtMs)
            .put("endedAt", artifact.endedAtMs)
            .put(
                "summary",
                JSONObject()
                    .put("sessionId", artifact.summary.sessionId)
                    .put("mode", artifact.summary.mode.bridgeValue())
                    .put("trackName", artifact.summary.trackName)
                    .put("coachId", artifact.summary.coachId)
                    .put("frameCount", artifact.summary.frameCount)
                    .put("decisionCount", artifact.summary.decisionCount)
                    .put(
                        "durationSeconds",
                        String.format(Locale.US, "%.2f", artifact.summary.durationSeconds).toDouble(),
                    ),
            )
            .put("sessionGoals", JSONArray(artifact.sessionGoals.map(::goalJson)))
            .put("frames", JSONArray(artifact.frames.map(::frameJson)))
            .put("decisions", JSONArray(artifact.decisions.map(::decisionJson)))
            .put("audioEvents", JSONArray(artifact.audioEvents.map(::audioEventJson)))
    }

    private fun goalJson(goal: SessionGoal): JSONObject {
        return JSONObject()
            .put("id", goal.id)
            .put("focus", goal.focus.bridgeValue())
            .put("description", goal.description)
            .put("source", goal.source.bridgeValue())
            .put("prioritizedActions", JSONArray(goal.prioritizedActions.map { action -> action.name }))
    }

    private fun frameJson(frame: TelemetryFrame): JSONObject {
        return JSONObject()
            .put("time", frame.timeSeconds)
            .put("latitude", frame.latitude)
            .put("longitude", frame.longitude)
            .put("altitude", frame.altitude)
            .put("speed", frame.speedMph)
            .put("rpm", frame.rpm)
            .put("throttle", frame.throttle)
            .put("brake", frame.brake)
            .put("steering", frame.steering)
            .put("gLat", frame.gLat)
            .put("gLong", frame.gLong)
            .put("gear", frame.gear)
            .put("distance", frame.distanceMeters)
            .put("coolantTempC", frame.coolantTempC)
            .put("oilTempC", frame.oilTempC)
            .put("vehicleDiagnostics", frame.vehicleDiagnostics?.let(::vehicleDiagnosticsJson))
            .put("canVehicleDiagnostics", frame.canVehicleDiagnostics?.let(::canVehicleDiagnosticsJson))
            .put("sourceMode", frame.sourceMode.bridgeValue())
            .put("telemetrySource", frame.telemetrySource?.bridgeValue())
            .put(
                "sourceHealth",
                frame.sourceHealth?.let { health ->
                    JSONObject()
                        .put("status", health.status)
                        .put("motionSource", health.motionSource)
                        .put("motionConnected", health.motionConnected)
                        .put("motionFixGood", health.motionFixGood)
                        .put("motionSampleAgeMs", health.motionSampleAgeMs)
                        .put("fallbackStage", health.fallbackStage)
                        .put("degradedReason", health.degradedReason)
                        .put("phoneMotionConnected", health.phoneMotionConnected)
                        .put("phoneMotionFixGood", health.phoneMotionFixGood)
                        .put("phoneMotionSampleAgeMs", health.phoneMotionSampleAgeMs)
                        .put("raceBoxConnected", health.raceBoxConnected)
                        .put("raceBoxFixGood", health.raceBoxFixGood)
                        .put("raceBoxFixStatus", health.raceBoxFixStatus)
                        .put("raceBoxSatellites", health.raceBoxSatellites)
                        .put("raceBoxSampleAgeMs", health.raceBoxSampleAgeMs)
                        .put("obdConnected", health.obdConnected)
                        .put("obdSampleAgeMs", health.obdSampleAgeMs)
                        .put("obdStale", health.obdStale)
                        .put("obdSpeedDeltaMph", health.obdSpeedDeltaMph)
                        .put("obdTransport", health.obdTransport)
                        .put("obdSupportedPids", JSONArray(health.obdSupportedPids))
                        .put("obdReconnectCount", health.obdReconnectCount)
                        .put("obdChannelAgesMs", JSONObject(health.obdChannelAgesMs))
                        .put("obdChannelStale", JSONObject(health.obdChannelStale))
                        .put("canConnected", health.canConnected)
                        .put("canFrameAgesMs", JSONObject(health.canFrameAgesMs))
                        .put("canFrameStale", JSONObject(health.canFrameStale))
                        .put("canFrameRatesHz", JSONObject(health.canFrameRatesHz))
                        .put("canDecodeErrors", health.canDecodeErrors)
                        .put("usbDeviceName", health.usbDeviceName)
                        .put("rawCanSample", health.rawCanSample)
                        .put("signUnverified", health.signUnverified)
                },
            )
            .put(
                "vision",
                frame.vision?.let { vision ->
                    JSONObject()
                        .put("timestamp", vision.timestampMs)
                        .put("averageLuma", vision.averageLuma)
                        .put("motionEnergy", vision.motionEnergy)
                        .put("lateralBalance", vision.lateralBalance)
                        .put("verticalBalance", vision.verticalBalance)
                        .put("centerContrast", vision.centerContrast)
                        .put("framesPerSecond", vision.framesPerSecond)
                },
            )
    }

    private fun vehicleDiagnosticsJson(diagnostics: VehicleDiagnostics): JSONObject {
        return JSONObject()
            .put("engineLoadPercent", diagnostics.engineLoadPercent)
            .put("mafGramsPerSecond", diagnostics.mafGramsPerSecond)
            .put("intakeTempC", diagnostics.intakeTempC)
            .put("timingAdvanceDegrees", diagnostics.timingAdvanceDegrees)
            .put("shortFuelTrim1Percent", diagnostics.shortFuelTrim1Percent)
            .put("longFuelTrim1Percent", diagnostics.longFuelTrim1Percent)
            .put("shortFuelTrim2Percent", diagnostics.shortFuelTrim2Percent)
            .put("longFuelTrim2Percent", diagnostics.longFuelTrim2Percent)
            .put("o2Bank1Sensor1Volts", diagnostics.o2Bank1Sensor1Volts)
            .put("o2Bank2Sensor1Volts", diagnostics.o2Bank2Sensor1Volts)
    }

    private fun canVehicleDiagnosticsJson(diagnostics: CanVehicleDiagnostics): JSONObject {
        return JSONObject()
            .put("waterPressurePsi", diagnostics.waterPressurePsi)
            .put("oilPressurePsi", diagnostics.oilPressurePsi)
            .put("brakePressurePsi", diagnostics.brakePressurePsi)
            .put("pedalPositionPercent", diagnostics.pedalPositionPercent)
            .put("brakeSwitchApplied", diagnostics.brakeSwitchApplied)
            .put("rollRateDegPerSec", diagnostics.rollRateDegPerSec)
            .put("pitchRateDegPerSec", diagnostics.pitchRateDegPerSec)
            .put("yawRateDegPerSec", diagnostics.yawRateDegPerSec)
            .put("steeringAngleDeg", diagnostics.steeringAngleDeg)
            .put("lateralG", diagnostics.lateralG)
            .put("inlineG", diagnostics.inlineG)
            .put("verticalG", diagnostics.verticalG)
            .put("fuelLevelGal", diagnostics.fuelLevelGal)
            .put("batteryVoltage", diagnostics.batteryVoltage)
            .put("wheelSpeedFrontLeftMph", diagnostics.wheelSpeedFrontLeftMph)
            .put("wheelSpeedFrontRightMph", diagnostics.wheelSpeedFrontRightMph)
            .put("wheelSpeedRearLeftMph", diagnostics.wheelSpeedRearLeftMph)
            .put("wheelSpeedRearRightMph", diagnostics.wheelSpeedRearRightMph)
            .put("ecuSpeedMph", diagnostics.ecuSpeedMph)
            .put("gpsSpeedMph", diagnostics.gpsSpeedMph)
            .put("outsideTempC", diagnostics.outsideTempC)
            .put("waterTempC", diagnostics.waterTempC)
            .put("engineOilTempC", diagnostics.engineOilTempC)
            .put("oilFilterTempC", diagnostics.oilFilterTempC)
            .put("dscRegActive", diagnostics.dscRegActive)
            .put("gearRaw", diagnostics.gearRaw)
            .put("frameAgesMs", JSONObject(diagnostics.frameAgesMs))
            .put("frameStale", JSONObject(diagnostics.frameStale))
    }

    private fun decisionJson(decision: CoachingDecision): JSONObject {
        return JSONObject()
            .put("path", decision.path.bridgeValue())
            .put("action", decision.action?.name)
            .put("text", decision.text)
            .put("priority", decision.priority)
            .put("cornerPhase", decision.cornerPhase.name)
            .put("timestamp", decision.timestampMs)
            .put("backend", decision.backend.bridgeValue())
            .put("latencyMs", decision.latencyMs)
            .put("confidence", decision.confidence)
            .put("phraseId", decision.phraseId)
            .put("id", decision.id)
    }

    private fun audioEventJson(event: AudioDispatchEvent): JSONObject {
        return JSONObject()
            .put("decisionId", event.decisionId)
            .put("utteranceId", event.utteranceId)
            .put("action", event.action?.name)
            .put("priority", event.priority)
            .put("requestedAt", event.requestedAtMs)
            .put("dispatchLatencyMs", event.dispatchLatencyMs)
            .put("ttsStartLatencyMs", event.ttsStartLatencyMs)
            .put("status", event.status.name)
            .put("fallbackReason", event.fallbackReason)
    }

    private data class ActiveSession(
        val id: String,
        val mode: SessionMode,
        val trackName: String,
        val coachId: String,
        val sessionGoals: List<SessionGoal>,
        val startedAtMs: Long,
        val frames: MutableList<TelemetryFrame>,
        val decisions: MutableList<CoachingDecision>,
        val audioEvents: MutableList<AudioDispatchEvent>,
    )
}
