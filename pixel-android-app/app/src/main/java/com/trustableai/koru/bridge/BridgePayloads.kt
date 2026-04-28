package com.trustableai.koru.bridge

import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.bridgeValue
import org.json.JSONArray
import org.json.JSONObject

object BridgePayloads {
    fun telemetryFrame(frame: TelemetryFrame): String {
        return JSONObject()
            .put("type", "telemetry_frame")
            .put(
                "frame",
                JSONObject()
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
                    .put("sourceMode", frame.sourceMode.bridgeValue())
                    .put("telemetrySource", frame.telemetrySource?.bridgeValue())
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
                    ),
            )
            .toString()
    }

    fun sessionSaved(artifact: RecordedSessionArtifact): String {
        return JSONObject()
            .put("type", "session_saved")
            .put(
                "session",
                JSONObject()
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
                            .put("durationSeconds", artifact.summary.durationSeconds),
                    )
                    .put("sessionGoals", JSONArray(artifact.sessionGoals.map(::sessionGoalObject)))
                    .put("frames", JSONArray(artifact.frames.map { frame -> telemetryFrameObject(frame) }))
                    .put("decisions", JSONArray(artifact.decisions.map { decision -> coachingDecisionObject(decision) })),
            )
            .toString()
    }

    fun coachingDecision(decision: CoachingDecision): String {
        return JSONObject()
            .put("type", "coaching_decision")
            .put("decision", coachingDecisionObject(decision))
            .toString()
    }

    fun backendStatus(status: LiveBackendStatus): String {
        return JSONObject()
            .put("type", "backend_status")
            .put(
                "status",
                JSONObject()
                    .put("backend", status.backend.bridgeValue())
                    .put("state", status.state.bridgeValue())
                    .put("detail", status.detail)
                    .put("lastUpdated", status.lastUpdatedMs)
                    .put("model", status.model)
                    .put("usesOnDeviceModel", status.usesOnDeviceModel)
                    .put(
                        "supportedPaths",
                        JSONArray(status.supportedPaths.map { path -> path.bridgeValue() }),
                    ),
            )
            .toString()
    }

    private fun telemetryFrameObject(frame: TelemetryFrame): JSONObject {
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
            .put("sourceMode", frame.sourceMode.bridgeValue())
            .put("telemetrySource", frame.telemetrySource?.bridgeValue())
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

    private fun coachingDecisionObject(decision: CoachingDecision): JSONObject {
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
    }

    private fun sessionGoalObject(goal: SessionGoal): JSONObject {
        return JSONObject()
            .put("id", goal.id)
            .put("focus", goal.focus.bridgeValue())
            .put("description", goal.description)
            .put("source", goal.source.bridgeValue())
            .put("prioritizedActions", JSONArray(goal.prioritizedActions.map { action -> action.name }))
    }
}
