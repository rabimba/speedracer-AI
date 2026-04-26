package com.trustableai.koru.bridge

import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.LiveBackendStatus
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

    fun coachingDecision(decision: CoachingDecision): String {
        return JSONObject()
            .put("type", "coaching_decision")
            .put(
                "decision",
                JSONObject()
                    .put("path", decision.path.bridgeValue())
                    .put("action", decision.action?.name)
                    .put("text", decision.text)
                    .put("priority", decision.priority)
                    .put("cornerPhase", decision.cornerPhase.name)
                    .put("timestamp", decision.timestampMs)
                    .put("backend", decision.backend.bridgeValue())
                    .put("latencyMs", decision.latencyMs)
                    .put("confidence", decision.confidence)
                    .put("phraseId", decision.phraseId),
            )
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
}
