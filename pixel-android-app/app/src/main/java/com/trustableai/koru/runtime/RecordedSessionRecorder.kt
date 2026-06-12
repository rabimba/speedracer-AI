package com.trustableai.koru.runtime

import android.content.Context
import com.trustableai.koru.model.AudioDispatchEvent
import com.trustableai.koru.model.CanVehicleDiagnostics
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.RecordedSessionSummary
import com.trustableai.koru.model.RecordingStatus
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.VehicleDiagnostics
import com.trustableai.koru.model.bridgeValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.lang.StringBuilder
import java.util.Locale

class RecordedSessionRecorder(
    private val context: Context? = null,
    private val persistArtifacts: Boolean = true,
) {
    private var activeSession: ActiveSession? = null

    @Synchronized
    fun start(mode: SessionMode, trackName: String, coachId: String, sessionGoals: List<SessionGoal> = emptyList()) {
        discard()
        val id = "koru-${mode.bridgeValue()}-${System.currentTimeMillis()}"
        val startedAtMs = System.currentTimeMillis()
        val paths = createSessionPaths(id)
        val writers = paths?.let(::openWriters)
        val active = ActiveSession(
            id = id,
            mode = mode,
            trackName = trackName,
            coachId = coachId,
            sessionGoals = sessionGoals.take(3),
            startedAtMs = startedAtMs,
            embeddedFrames = mutableListOf(),
            decisionsPreview = mutableListOf(),
            audioEventsPreview = mutableListOf(),
            paths = paths,
            writers = writers,
        )
        activeSession = active
        writeMeta(active)
        writeCanDumpHeader(active)
        flush(active, force = true)
    }

    @Synchronized
    fun hasActiveSession(): Boolean = activeSession != null

    @Synchronized
    fun recordFrame(frame: TelemetryFrame) {
        val session = activeSession ?: return
        session.totalFrameCount += 1
        if (shouldEmbedFrame(session, frame)) {
            session.embeddedFrames += frame
            session.lastEmbeddedFrameTimeSeconds = frame.timeSeconds
        }
        session.writers?.frames?.let { writer ->
            writer.append(frameJson(frame).toString())
            writer.newLine()
        }
        writeCanDumpRows(session, frame)
        flush(session)
    }

    @Synchronized
    fun recordDecision(decision: CoachingDecision) {
        val session = activeSession ?: return
        session.decisionCount += 1
        session.decisionsPreview += decision
        trimPreview(session.decisionsPreview, MAX_EMBEDDED_DECISIONS)
        session.writers?.decisions?.let { writer ->
            writer.append(decisionJson(decision).toString())
            writer.newLine()
        }
        flush(session)
    }

    @Synchronized
    fun recordAudioEvent(event: AudioDispatchEvent) {
        val session = activeSession ?: return
        session.audioEventCount += 1
        session.audioEventsPreview += event
        trimPreview(session.audioEventsPreview, MAX_EMBEDDED_AUDIO_EVENTS)
        session.writers?.audioEvents?.let { writer ->
            writer.append(audioEventJson(event).toString())
            writer.newLine()
        }
        flush(session)
    }

    @Synchronized
    fun finish(endedReason: String = "completed"): RecordedSessionArtifact? {
        val session = activeSession ?: return null
        activeSession = null
        val endedAtMs = System.currentTimeMillis()
        flush(session, force = true)
        closeWriters(session)
        val artifact = buildArtifact(session, endedAtMs, endedReason)
        persistManifest(artifact)
        session.paths?.activeMarkerPath?.let { File(it).delete() }
        return artifact
    }

    @Synchronized
    fun status(nowMs: Long = System.currentTimeMillis()): RecordingStatus {
        val session = activeSession
            ?: return RecordingStatus(active = false)
        val paths = session.paths
        return RecordingStatus(
            active = true,
            sessionId = session.id,
            artifactPath = paths?.artifactPath,
            framesPath = paths?.framesPath,
            decisionsPath = paths?.decisionsPath,
            audioEventsPath = paths?.audioEventsPath,
            canDumpPath = paths?.canDumpPath,
            frameCount = session.totalFrameCount,
            decisionCount = session.decisionCount,
            audioEventCount = session.audioEventCount,
            lastFlushAtMs = session.lastFlushAtMs,
            flushAgeMs = session.lastFlushAtMs?.let { nowMs - it },
        )
    }

    @Synchronized
    fun discard() {
        val session = activeSession ?: return
        activeSession = null
        runCatching { closeWriters(session) }
    }

    private fun createSessionPaths(sessionId: String): PersistedSessionPaths? {
        if (!persistArtifacts) return null
        val appContext = context ?: return null
        val root = appContext.getExternalFilesDir("recorded_sessions")
            ?: File(appContext.filesDir, "recorded_sessions")
        val sessionDir = File(root, sessionId)
        sessionDir.mkdirs()
        return PersistedSessionPaths(
            directory = sessionDir.absolutePath,
            artifactPath = File(sessionDir, "session.json").absolutePath,
            framesPath = File(sessionDir, "frames.ndjson").absolutePath,
            decisionsPath = File(sessionDir, "decisions.ndjson").absolutePath,
            audioEventsPath = File(sessionDir, "audio-events.ndjson").absolutePath,
            canDumpPath = File(sessionDir, "can-slcan.txt").absolutePath,
            metaPath = File(sessionDir, "session-meta.json").absolutePath,
            activeMarkerPath = File(sessionDir, ".active").absolutePath,
        )
    }

    private fun openWriters(paths: PersistedSessionPaths): ActiveWriters {
        File(paths.activeMarkerPath).writeText("active")
        return ActiveWriters(
            frames = BufferedWriter(FileWriter(paths.framesPath, true)),
            decisions = BufferedWriter(FileWriter(paths.decisionsPath, true)),
            audioEvents = BufferedWriter(FileWriter(paths.audioEventsPath, true)),
            canDump = BufferedWriter(FileWriter(paths.canDumpPath, true)),
        )
    }

    private fun writeMeta(session: ActiveSession) {
        val paths = session.paths ?: return
        File(paths.metaPath).writeText(
            JSONObject()
                .put("id", session.id)
                .put("schemaVersion", 2)
                .put("mode", session.mode.bridgeValue())
                .put("trackName", session.trackName)
                .put("coachId", session.coachId)
                .put("startedAt", session.startedAtMs)
                .put("sessionGoals", JSONArray(session.sessionGoals.map(::goalJson)))
                .toString(),
        )
    }

    private fun writeCanDumpHeader(session: ActiveSession) {
        val writer = session.writers?.canDump ?: return
        writer.append("# Koru AiM CAN raw dump")
        writer.newLine()
        writer.append("# session=").append(session.id)
            .append(" track=").append(session.trackName)
        writer.newLine()
        writer.append("# columns: timeSeconds canId rawSlcan brakeRaw brakePsi brakeZeroOffsetRaw brakeCalPsi pedalRaw pedalPercent brakeSwitchRaw")
        writer.newLine()
    }

    private fun writeCanDumpRows(session: ActiveSession, frame: TelemetryFrame) {
        val writer = session.writers?.canDump ?: return
        val diagnostics = frame.canVehicleDiagnostics
        val samples = frame.sourceHealth?.rawCanSamplesById.orEmpty() + diagnostics?.rawFrameSamples.orEmpty()
        if (samples.isEmpty()) return
        samples.toSortedMap().forEach { (canId, raw) ->
            if (session.lastRawCanById[canId] == raw) return@forEach
            session.lastRawCanById[canId] = raw
            writer.append(String.format(Locale.US, "%.3f", frame.timeSeconds))
                .append(' ').append(canId)
                .append(' ').append(raw)
                .append(' ').append(diagnostics?.brakePressureRaw?.toString().orEmpty())
                .append(' ').append(diagnostics?.brakePressurePsi?.toString().orEmpty())
                .append(' ').append(diagnostics?.brakePressureZeroOffsetRaw?.toString().orEmpty())
                .append(' ').append(diagnostics?.brakePressureCalibratedPsi?.toString().orEmpty())
                .append(' ').append(diagnostics?.pedalPositionRaw?.toString().orEmpty())
                .append(' ').append(diagnostics?.pedalPositionPercent?.toString().orEmpty())
                .append(' ').append(diagnostics?.brakeSwitchRaw?.toString().orEmpty())
            writer.newLine()
        }
    }

    private fun shouldEmbedFrame(session: ActiveSession, frame: TelemetryFrame): Boolean {
        if (session.embeddedFrames.size >= MAX_EMBEDDED_FRAMES) return false
        val last = session.lastEmbeddedFrameTimeSeconds ?: return true
        return frame.timeSeconds - last >= EMBEDDED_FRAME_INTERVAL_SECONDS
    }

    private fun flush(session: ActiveSession, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - session.lastFlushAtMs < FLUSH_INTERVAL_MS) return
        session.writers?.frames?.flush()
        session.writers?.decisions?.flush()
        session.writers?.audioEvents?.flush()
        session.writers?.canDump?.flush()
        session.lastFlushAtMs = now
    }

    private fun closeWriters(session: ActiveSession) {
        session.writers?.frames?.close()
        session.writers?.decisions?.close()
        session.writers?.audioEvents?.close()
        session.writers?.canDump?.close()
        session.writers = null
    }

    private fun buildArtifact(
        session: ActiveSession,
        endedAtMs: Long,
        endedReason: String,
    ): RecordedSessionArtifact {
        val summary = RecordedSessionSummary(
            sessionId = session.id,
            mode = session.mode,
            trackName = session.trackName,
            coachId = session.coachId,
            frameCount = session.totalFrameCount,
            decisionCount = session.decisionCount,
            durationSeconds = ((endedAtMs - session.startedAtMs) / 1000.0),
        )
        val paths = session.paths
        return RecordedSessionArtifact(
            schemaVersion = 2,
            id = session.id,
            mode = session.mode,
            trackName = session.trackName,
            coachId = session.coachId,
            startedAtMs = session.startedAtMs,
            endedAtMs = endedAtMs,
            summary = summary,
            sessionGoals = session.sessionGoals.toList(),
            frames = session.embeddedFrames.toList(),
            decisions = session.decisionsPreview.toList(),
            audioEvents = session.audioEventsPreview.toList(),
            artifactPath = paths?.artifactPath,
            canDumpPath = paths?.canDumpPath,
            endedReason = endedReason,
            framesPath = paths?.framesPath,
            decisionsPath = paths?.decisionsPath,
            audioEventsPath = paths?.audioEventsPath,
            embeddedFrameCount = session.embeddedFrames.size,
            totalFrameCount = session.totalFrameCount,
            lastFlushAt = session.lastFlushAtMs,
        )
    }

    private fun persistManifest(artifact: RecordedSessionArtifact) {
        val path = artifact.artifactPath ?: return
        File(path).writeText(artifactJson(artifact).toString())
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
            .put("endedReason", artifact.endedReason)
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
            .put("artifactPath", artifact.artifactPath)
            .put("canDumpPath", artifact.canDumpPath)
            .put("framesPath", artifact.framesPath)
            .put("decisionsPath", artifact.decisionsPath)
            .put("audioEventsPath", artifact.audioEventsPath)
            .put("embeddedFrameCount", artifact.embeddedFrameCount)
            .put("totalFrameCount", artifact.totalFrameCount)
            .put("lastFlushAt", artifact.lastFlushAt)
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
                        .put("canBitrate", health.canBitrate)
                        .put("canWaitingForFrames", health.canWaitingForFrames)
                        .put("usbDeviceName", health.usbDeviceName)
                        .put("rawCanSample", health.rawCanSample)
                        .put("rawCanSamplesById", JSONObject(health.rawCanSamplesById))
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
            .put("brakePressureRaw", diagnostics.brakePressureRaw)
            .put("brakePressurePsi", diagnostics.brakePressurePsi)
            .put("brakePressureZeroOffsetRaw", diagnostics.brakePressureZeroOffsetRaw)
            .put("brakePressureCalibratedPsi", diagnostics.brakePressureCalibratedPsi)
            .put("brakePressureZeroOffsetPsi", diagnostics.brakePressureZeroOffsetPsi)
            .put("pedalPositionRaw", diagnostics.pedalPositionRaw)
            .put("pedalPositionPercent", diagnostics.pedalPositionPercent)
            .put("brakeSwitchRaw", diagnostics.brakeSwitchRaw)
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
            .put("rawFrameSamples", JSONObject(diagnostics.rawFrameSamples))
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
            .put("scope", event.scope.name)
            .put("clipName", event.clipName)
    }

    private data class ActiveSession(
        val id: String,
        val mode: SessionMode,
        val trackName: String,
        val coachId: String,
        val sessionGoals: List<SessionGoal>,
        val startedAtMs: Long,
        val embeddedFrames: MutableList<TelemetryFrame>,
        val decisionsPreview: MutableList<CoachingDecision>,
        val audioEventsPreview: MutableList<AudioDispatchEvent>,
        val paths: PersistedSessionPaths?,
        var writers: ActiveWriters?,
        var totalFrameCount: Int = 0,
        var decisionCount: Int = 0,
        var audioEventCount: Int = 0,
        var lastFlushAtMs: Long = 0L,
        var lastEmbeddedFrameTimeSeconds: Double? = null,
        val lastRawCanById: MutableMap<String, String> = mutableMapOf(),
    )

    private data class ActiveWriters(
        val frames: BufferedWriter,
        val decisions: BufferedWriter,
        val audioEvents: BufferedWriter,
        val canDump: BufferedWriter,
    )

    private data class PersistedSessionPaths(
        val directory: String,
        val artifactPath: String,
        val framesPath: String,
        val decisionsPath: String,
        val audioEventsPath: String,
        val canDumpPath: String,
        val metaPath: String,
        val activeMarkerPath: String,
    )

    companion object {
        private const val FLUSH_INTERVAL_MS = 1_000L
        private const val MAX_EMBEDDED_FRAMES = 600
        private const val MAX_EMBEDDED_DECISIONS = 1_000
        private const val MAX_EMBEDDED_AUDIO_EVENTS = 1_000
        private const val EMBEDDED_FRAME_INTERVAL_SECONDS = 0.5

        fun recoverIncomplete(context: Context): List<RecordedSessionArtifact> {
            val roots = listOfNotNull(
                File(context.filesDir, "recorded_sessions"),
                context.getExternalFilesDir("recorded_sessions"),
            ).distinctBy { it.absolutePath }
            return roots.flatMap { root ->
                root.listFiles()
                    ?.filter { candidate -> candidate.isDirectory && File(candidate, ".active").exists() }
                    ?.mapNotNull { directory -> recoverDirectory(context, directory) }
                    .orEmpty()
            }
        }

        private fun recoverDirectory(context: Context, directory: File): RecordedSessionArtifact? {
            val metaFile = File(directory, "session-meta.json")
            if (!metaFile.exists()) return null
            val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return null
            val id = meta.optString("id", directory.name)
            val mode = modeFromBridge(meta.optString("mode", SessionMode.TELEMETRY.bridgeValue()))
            val trackName = meta.optString("trackName", "Recovered Session")
            val coachId = meta.optString("coachId", "superaj")
            val startedAtMs = meta.optLong("startedAt", directory.lastModified())
            val endedAtMs = System.currentTimeMillis()
            val framesPath = File(directory, "frames.ndjson").absolutePath
            val decisionsPath = File(directory, "decisions.ndjson").absolutePath
            val audioEventsPath = File(directory, "audio-events.ndjson").absolutePath
            val canDumpPath = File(directory, "can-slcan.txt").absolutePath
            val frameCount = lineCount(File(framesPath))
            val decisionCount = lineCount(File(decisionsPath))
            val summary = RecordedSessionSummary(
                sessionId = id,
                mode = mode,
                trackName = trackName,
                coachId = coachId,
                frameCount = frameCount,
                decisionCount = decisionCount,
                durationSeconds = ((endedAtMs - startedAtMs) / 1000.0).coerceAtLeast(0.0),
            )
            val artifact = RecordedSessionArtifact(
                schemaVersion = 2,
                id = id,
                mode = mode,
                trackName = trackName,
                coachId = coachId,
                startedAtMs = startedAtMs,
                endedAtMs = endedAtMs,
                summary = summary,
                frames = emptyList(),
                decisions = emptyList(),
                audioEvents = emptyList(),
                artifactPath = File(directory, "session.json").absolutePath,
                canDumpPath = canDumpPath,
                endedReason = "process_recovered",
                framesPath = framesPath,
                decisionsPath = decisionsPath,
                audioEventsPath = audioEventsPath,
                embeddedFrameCount = 0,
                totalFrameCount = frameCount,
                lastFlushAt = directory.lastModified().takeIf { it > 0L },
            )
            val recorder = RecordedSessionRecorder(context)
            File(artifact.artifactPath ?: return artifact).writeText(recorder.artifactJson(artifact).toString())
            File(directory, ".active").delete()
            return artifact
        }

        private fun modeFromBridge(value: String): SessionMode {
            return SessionMode.entries.firstOrNull { mode -> mode.bridgeValue() == value } ?: SessionMode.TELEMETRY
        }

        private fun lineCount(file: File): Int {
            if (!file.exists()) return 0
            var count = 0
            file.forEachLine { count += 1 }
            return count
        }

        private fun <T> trimPreview(list: MutableList<T>, maxSize: Int) {
            while (list.size > maxSize) {
                list.removeAt(0)
            }
        }
    }
}
