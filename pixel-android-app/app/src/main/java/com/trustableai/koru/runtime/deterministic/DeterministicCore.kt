package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.Corner
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.DriverState
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.Track
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class CornerDetection(
    val phase: CornerPhase,
    val corner: Corner? = null,
)

class CornerPhaseDetector(track: Track?) {
    private var track: Track? = track
    private var cosLat = track?.centerLat?.let { cos(it * Math.PI / 180.0) } ?: cos(38.16 * Math.PI / 180.0)

    fun setTrack(track: Track?) {
        this.track = track
        this.cosLat = track?.centerLat?.let { cos(it * Math.PI / 180.0) } ?: this.cosLat
    }

    fun detect(frame: TelemetryFrame): CornerDetection {
        val localTrack = track
        if (localTrack != null) {
            detectFromGps(frame, localTrack)?.let { return it }
        }
        return detectFromGForces(frame)
    }

    private fun detectFromGps(frame: TelemetryFrame, track: Track): CornerDetection? {
        if (!isValidGps(frame.latitude, frame.longitude)) return null
        for (corner in track.corners) {
            val refLat = corner.entryLat ?: corner.lat
            val refLon = corner.entryLon ?: corner.lon
            val dLat = (frame.latitude - refLat) * 111320.0
            val dLon = (frame.longitude - refLon) * 111320.0 * cosLat
            val fastDistance = sqrt(dLat * dLat + dLon * dLon)
            if (fastDistance > 300.0) continue

            val distanceToEntry = haversineDistance(frame.latitude, frame.longitude, refLat, refLon)
            val distanceToApex = haversineDistance(frame.latitude, frame.longitude, corner.lat, corner.lon)
            if (distanceToEntry < 200.0 || distanceToApex < 150.0) {
                return CornerDetection(
                    phase = classifyPhase(distanceToEntry, distanceToApex),
                    corner = corner,
                )
            }
        }
        return null
    }

    private fun detectFromGForces(frame: TelemetryFrame): CornerDetection {
        val absGLat = abs(frame.gLat)
        val phase = when {
            absGLat > 0.5 && frame.brake < 10.0 && frame.throttle > 30.0 -> CornerPhase.EXIT
            absGLat > 0.5 -> CornerPhase.MID_CORNER
            frame.brake > 30.0 && absGLat < 0.3 -> CornerPhase.BRAKE_ZONE
            frame.brake > 10.0 && absGLat > 0.3 -> CornerPhase.TURN_IN
            frame.throttle > 60.0 && absGLat < 0.2 && frame.gLong > 0.1 -> CornerPhase.ACCELERATION
            else -> CornerPhase.STRAIGHT
        }
        return CornerDetection(phase = phase)
    }

    private fun classifyPhase(distanceToEntry: Double, distanceToApex: Double): CornerPhase {
        return when {
            distanceToEntry < 100.0 && distanceToApex > 80.0 -> CornerPhase.BRAKE_ZONE
            distanceToEntry < 50.0 && distanceToApex > 40.0 -> CornerPhase.TURN_IN
            distanceToApex < 30.0 -> CornerPhase.APEX
            distanceToEntry < 30.0 && distanceToApex < 80.0 -> CornerPhase.MID_CORNER
            distanceToApex in 30.0..99.999 -> CornerPhase.EXIT
            distanceToEntry < 200.0 && distanceToApex > 100.0 -> CornerPhase.BRAKE_ZONE
            else -> CornerPhase.STRAIGHT
        }
    }
}

class TimingGate(
    private var cooldownMs: Long = 1500L,
    private var deliveryMs: Long = 2000L,
    blackoutPhases: Set<CornerPhase> = setOf(CornerPhase.MID_CORNER, CornerPhase.APEX),
) {
    private val blackoutPhases = blackoutPhases.toMutableSet()
    private var state = State.OPEN
    private var lastDeliveryAtMs = 0L

    enum class State {
        OPEN,
        DELIVERING,
        COOLDOWN,
        BLACKOUT,
    }

    fun updateConfig(cooldownMs: Long, blackoutPhases: Set<CornerPhase>) {
        this.cooldownMs = cooldownMs
        this.blackoutPhases.clear()
        this.blackoutPhases.addAll(blackoutPhases)
    }

    fun update(cornerPhase: CornerPhase, nowMs: Long) {
        if (cornerPhase in blackoutPhases && state != State.DELIVERING) {
            state = State.BLACKOUT
            return
        }
        when (state) {
            State.BLACKOUT -> state = State.OPEN
            State.DELIVERING -> if (nowMs - lastDeliveryAtMs >= deliveryMs) state = State.COOLDOWN
            State.COOLDOWN -> if (nowMs - lastDeliveryAtMs >= deliveryMs + cooldownMs) state = State.OPEN
            State.OPEN -> Unit
        }
    }

    fun canDeliver(priority: Int): Boolean = priority == 0 || state == State.OPEN

    fun startDelivery(nowMs: Long) {
        lastDeliveryAtMs = nowMs
        state = State.DELIVERING
    }
}

class CoachingQueue {
    private val queue = mutableListOf<QueuedDecision>()

    fun enqueue(decision: QueuedDecision, nowMs: Long) {
        expireStale(nowMs)
        if (queue.size >= MAX_QUEUE_SIZE) {
            val worst = queue.maxWithOrNull(compareBy<QueuedDecision>({ it.priority }, { -it.timestampMs })) ?: return
            if (decision.priority <= worst.priority) {
                queue.remove(worst)
            } else {
                return
            }
        }
        queue.add(decision)
        queue.sortWith(compareBy<QueuedDecision>({ it.priority }, { it.timestampMs }))
    }

    fun dequeue(nowMs: Long, timingGate: TimingGate): QueuedDecision? {
        expireStale(nowMs)
        val next = queue.firstOrNull { timingGate.canDeliver(it.priority) } ?: return null
        queue.remove(next)
        return next
    }

    fun preempt(decision: QueuedDecision): QueuedDecision {
        queue.removeAll { it.priority != 0 }
        return decision
    }

    data class QueuedDecision(
        val action: CoachAction?,
        val path: String,
        val text: String,
        val priority: Int,
        val cornerPhase: CornerPhase,
        val timestampMs: Long,
        val confidence: Double? = null,
        val phraseId: String? = null,
    )

    private fun expireStale(nowMs: Long) {
        queue.removeAll { nowMs - it.timestampMs >= STALE_MS }
    }

    companion object {
        private const val MAX_QUEUE_SIZE = 5
        private const val STALE_MS = 3000L
    }
}

class DriverModel {
    private val throttleDeltas = mutableListOf<Pair<Double, Double>>()
    private val brakeDeltas = mutableListOf<Pair<Double, Double>>()
    private val coastingFrames = mutableListOf<Pair<Double, Boolean>>()
    private var previousThrottle: Double? = null
    private var previousBrake: Double? = null
    private var currentLevel = SkillLevel.BEGINNER
    private var candidateLevel = SkillLevel.BEGINNER
    private var candidateStartTime = 0.0

    fun update(frame: TelemetryFrame) {
        val time = frame.timeSeconds
        val prevThrottle = previousThrottle
        val prevBrake = previousBrake
        if (prevThrottle != null && prevBrake != null) {
            throttleDeltas += time to abs(frame.throttle - prevThrottle)
            brakeDeltas += time to abs(frame.brake - prevBrake)
        }

        previousThrottle = frame.throttle
        previousBrake = frame.brake
        coastingFrames += time to (frame.throttle < 10.0 && frame.brake < 10.0)

        val cutoff = time - WINDOW_DURATION_SECONDS
        throttleDeltas.removeAll { it.first <= cutoff }
        brakeDeltas.removeAll { it.first <= cutoff }
        coastingFrames.removeAll { it.first <= cutoff }

        if (throttleDeltas.size >= MIN_SAMPLES) {
            val classified = classify()
            if (classified == candidateLevel) {
                if (time - candidateStartTime >= HYSTERESIS_SECONDS) {
                    currentLevel = candidateLevel
                }
            } else {
                candidateLevel = classified
                candidateStartTime = time
            }
        }
    }

    fun state(): DriverState {
        val smoothness = computeSmoothness()
        return DriverState(
            skillLevel = currentLevel,
            cognitiveLoad = 1.0 - smoothness,
            inputSmoothness = smoothness,
            coastingRatio = computeCoastingRatio(),
        )
    }

    private fun classify(): SkillLevel {
        val smoothness = computeSmoothness()
        val coastingRatio = computeCoastingRatio()
        return when {
            smoothness < 0.4 || coastingRatio > 0.3 -> SkillLevel.BEGINNER
            smoothness > 0.7 && coastingRatio < 0.1 -> SkillLevel.ADVANCED
            else -> SkillLevel.INTERMEDIATE
        }
    }

    private fun computeCoastingRatio(): Double {
        if (coastingFrames.isEmpty()) return 0.0
        return coastingFrames.count { it.second }.toDouble() / coastingFrames.size.toDouble()
    }

    private fun computeSmoothness(): Double {
        fun variance(values: List<Pair<Double, Double>>): Double {
            if (values.size < 2) return 0.0
            val mean = values.sumOf { it.second } / values.size.toDouble()
            return values.sumOf { delta -> (delta.second - mean) * (delta.second - mean) } / values.size.toDouble()
        }

        val throttleVariance = min(variance(throttleDeltas) / 2500.0, 1.0)
        val brakeVariance = min(variance(brakeDeltas) / 2500.0, 1.0)
        val combined = (throttleVariance + brakeVariance) * 0.5
        return (1.0 - combined).coerceIn(0.0, 1.0)
    }

    companion object {
        private const val WINDOW_DURATION_SECONDS = 10.0
        private const val HYSTERESIS_SECONDS = 5.0
        private const val MIN_SAMPLES = 20
    }
}

object DecisionMatrix {
    fun evaluate(frame: TelemetryFrame): CoachAction? {
        val absGLat = abs(frame.gLat)
        return when {
            absGLat > 0.7 && frame.gLong < -0.3 && frame.throttle < 5.0 && frame.speedMph > 40.0 ->
                CoachAction.OVERSTEER_RECOVERY
            frame.brake > 50.0 && frame.gLong < -0.8 -> CoachAction.THRESHOLD
            frame.brake > 10.0 && absGLat > 0.4 -> CoachAction.TRAIL_BRAKE
            absGLat > 1.0 && frame.throttle < 20.0 -> CoachAction.COMMIT
            absGLat > 0.6 && frame.throttle < 50.0 -> CoachAction.THROTTLE
            frame.throttle > 80.0 && absGLat < 0.3 -> CoachAction.PUSH
            frame.throttle < 10.0 && frame.brake < 10.0 && frame.speedMph > 60.0 -> CoachAction.COAST
            (frame.brake > 40.0 && frame.speedMph < 45.0) ||
                (frame.throttle < 15.0 && frame.brake < 5.0 && frame.speedMph > 80.0 && absGLat < 0.3) ->
                CoachAction.HESITATION
            absGLat < 0.2 && frame.gLong > 0.1 && frame.throttle > 70.0 -> CoachAction.FULL_THROTTLE
            frame.throttle > 30.0 && absGLat > 0.6 && frame.gLong < -0.1 -> CoachAction.EARLY_THROTTLE
            frame.throttle < 5.0 && absGLat > 0.4 && frame.speedMph > 50.0 -> CoachAction.LIFT_MID_CORNER
            frame.brake > 70.0 && frame.gLong < -1.2 -> CoachAction.SPIKE_BRAKE
            else -> null
        }
    }
}

class EdgeTriggerEvaluator {
    private var lastTriggerAtMs = 0L
    private var repeatedCoastCount = 0

    fun evaluate(
        frame: TelemetryFrame,
        phase: CornerPhase,
        driverState: DriverState,
        corner: Corner?,
        nowMs: Long,
    ): EdgeReasoningWindow? {
        if (nowMs - lastTriggerAtMs < MIN_TRIGGER_INTERVAL_MS) return null

        val trigger = when {
            frame.brake > 70.0 && frame.gLong < -1.0 -> buildWindow(
                triggerId = "brake_trace_anomaly",
                action = CoachAction.SPIKE_BRAKE,
                priority = 1,
                text = "Brake trace spiking. Smooth the release.",
                phase = phase,
                driverState = driverState,
                skillLevel = driverState.skillLevel,
                corner = corner,
                frame = frame,
            )

            (phase == CornerPhase.EXIT || phase == CornerPhase.ACCELERATION) && frame.throttle in 20.0..55.0 ->
                buildWindow(
                    triggerId = "exit_hesitation",
                    action = CoachAction.HUSTLE,
                    priority = 2,
                    text = "Exit hesitation. Commit harder on throttle.",
                    phase = phase,
                    driverState = driverState,
                    skillLevel = driverState.skillLevel,
                    corner = corner,
                    frame = frame,
                )

            frame.throttle < 8.0 && frame.brake < 8.0 && frame.speedMph > 55.0 -> {
                repeatedCoastCount += 1
                if (repeatedCoastCount >= 3) {
                    repeatedCoastCount = 0
                    buildWindow(
                        triggerId = "repeated_coasting",
                        action = CoachAction.COAST,
                        priority = 2,
                        text = "Repeated coasting detected. Pick a pedal.",
                        phase = phase,
                        driverState = driverState,
                        skillLevel = driverState.skillLevel,
                        corner = corner,
                        frame = frame,
                    )
                } else {
                    null
                }
            }

            driverState.cognitiveLoad > 0.72 -> buildWindow(
                triggerId = "overload_window",
                action = CoachAction.COGNITIVE_OVERLOAD,
                priority = 2,
                text = "High input variance. Strip the next lap back down.",
                phase = phase,
                driverState = driverState,
                skillLevel = driverState.skillLevel,
                corner = corner,
                frame = frame,
            )

            corner != null && phase == CornerPhase.BRAKE_ZONE && frame.speedMph > 70.0 -> buildWindow(
                triggerId = "corner_tactic",
                action = CoachAction.BRAKE,
                priority = 1,
                text = "Corner tactic window open. Trim speed before turn-in.",
                phase = phase,
                driverState = driverState,
                skillLevel = driverState.skillLevel,
                corner = corner,
                frame = frame,
            )

            else -> null
        }

        if (trigger != null) {
            lastTriggerAtMs = nowMs
        }
        return trigger
    }

    private fun buildWindow(
        triggerId: String,
        action: CoachAction,
        priority: Int,
        text: String,
        phase: CornerPhase,
        driverState: DriverState,
        skillLevel: SkillLevel,
        corner: Corner?,
        frame: TelemetryFrame,
    ): EdgeReasoningWindow {
        return EdgeReasoningWindow(
            triggerId = triggerId,
            actionHint = action,
            priority = priority,
            suggestedText = text,
            phase = phase,
            skillLevel = skillLevel,
            cornerName = corner?.name,
            features = mapOf(
                "speed_mph" to frame.speedMph,
                "throttle" to frame.throttle,
                "brake" to frame.brake,
                "g_lat" to frame.gLat,
                "g_long" to frame.gLong,
                "coasting_ratio" to driverState.coastingRatio,
                "cognitive_load" to driverState.cognitiveLoad,
            ),
        )
    }

    companion object {
        private const val MIN_TRIGGER_INTERVAL_MS = 1500L
    }
}

private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2.0) * sin(dLon / 2.0)
    return earthRadius * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

private fun isValidGps(lat: Double, lon: Double): Boolean {
    if (lat.isNaN() || lon.isNaN()) return false
    if (lat == 0.0 && lon == 0.0) return false
    if (abs(lat) > 90.0 || abs(lon) > 180.0) return false
    return true
}
