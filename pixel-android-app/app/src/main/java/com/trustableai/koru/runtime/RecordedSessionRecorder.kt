package com.trustableai.koru.runtime

import android.content.Context
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.RecordedSessionSummary
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.bridgeValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class RecordedSessionRecorder(private val context: Context) {
    private var activeSession: ActiveSession? = null

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
        )
    }

    fun recordFrame(frame: TelemetryFrame) {
        activeSession?.frames?.add(frame)
    }

    fun recordDecision(decision: CoachingDecision) {
        activeSession?.decisions?.add(decision)
    }

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
        )
        persist(artifact)
        return artifact
    }

    fun discard() {
        activeSession = null
    }

    private fun persist(artifact: RecordedSessionArtifact) {
        val sessionDir = File(context.filesDir, "recorded_sessions").apply { mkdirs() }
        val file = File(sessionDir, "${artifact.id}.json")
        file.writeText(artifactJson(artifact).toString())
    }

    private fun artifactJson(artifact: RecordedSessionArtifact): JSONObject {
        return JSONObject()
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
    )
}
