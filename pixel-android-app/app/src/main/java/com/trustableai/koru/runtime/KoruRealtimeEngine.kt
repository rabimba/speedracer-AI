package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.Track
import com.trustableai.koru.runtime.deterministic.CoachingQueue
import com.trustableai.koru.runtime.deterministic.CornerPhaseDetector
import com.trustableai.koru.runtime.deterministic.DecisionMatrix
import com.trustableai.koru.runtime.deterministic.DriverModel
import com.trustableai.koru.runtime.deterministic.EdgeTriggerEvaluator
import com.trustableai.koru.runtime.deterministic.TimingGate
import com.trustableai.koru.runtime.reasoner.OnDeviceReasoner

class KoruRealtimeEngine(
    track: Track,
    private val phraseCatalog: PhraseCatalog,
    private val reasonerProvider: () -> OnDeviceReasoner,
) {
    private val cornerDetector = CornerPhaseDetector(track)
    private val timingGate = TimingGate()
    private val queue = CoachingQueue()
    private val driverModel = DriverModel()
    private val edgeTriggerEvaluator = EdgeTriggerEvaluator()
    private var activeCoachId = "superaj"
    private var lastHotAction: CoachAction? = null
    private var lastFeedforwardCornerId: Int? = null
    private var currentPhase: CornerPhase = CornerPhase.STRAIGHT

    fun setActiveCoach(coachId: String) {
        activeCoachId = coachId
    }

    suspend fun processFrame(frame: TelemetryFrame, nowMs: Long = System.currentTimeMillis()): List<CoachingDecision> {
        val detection = cornerDetector.detect(frame)
        currentPhase = detection.phase
        timingGate.update(currentPhase, nowMs)
        driverModel.update(frame)
        val driverState = driverModel.state()

        adaptTiming(driverState.skillLevel)

        DecisionMatrix.evaluate(frame)?.let { action ->
            if (action != lastHotAction) {
                lastHotAction = action
                val decision = hotDecision(action, driverState.skillLevel, nowMs)
                if (decision.priority == 0) {
                    queue.enqueue(queue.preempt(decision), nowMs)
                } else {
                    queue.enqueue(decision, nowMs)
                }
            }
        }

        detection.corner?.takeIf { it.id != lastFeedforwardCornerId }?.let { corner ->
            lastFeedforwardCornerId = corner.id
            queue.enqueue(
                CoachingQueue.QueuedDecision(
                    action = null,
                    path = CoachingPath.FEEDFORWARD.bridgeValue(),
                    text = "${corner.name}: ${corner.advice}",
                    priority = 1,
                    cornerPhase = currentPhase,
                    timestampMs = nowMs,
                ),
                nowMs,
            )
        }

        val reasoner = reasonerProvider()
        edgeTriggerEvaluator.evaluate(frame, currentPhase, driverState, detection.corner, nowMs)?.let { window ->
            reasoner.reason(window)?.let { result ->
                queue.enqueue(
                    CoachingQueue.QueuedDecision(
                        action = result.action,
                        path = CoachingPath.EDGE.bridgeValue(),
                        text = result.text,
                        priority = result.priority,
                        cornerPhase = currentPhase,
                        timestampMs = nowMs,
                        confidence = result.confidence,
                        phraseId = result.phraseId,
                    ),
                    nowMs,
                )
            }
        }

        val next = queue.dequeue(nowMs, timingGate) ?: return emptyList()
        timingGate.startDelivery(nowMs)
        val backend = when (next.path) {
            CoachingPath.EDGE.bridgeValue() -> reasoner.backend
            else -> RuntimeBackend.DETERMINISTIC
        }
        return listOf(
            CoachingDecision(
                path = CoachingPath.valueOf(next.path.uppercase()),
                action = next.action,
                text = next.text,
                priority = next.priority,
                cornerPhase = next.cornerPhase,
                timestampMs = nowMs,
                backend = backend,
                confidence = next.confidence,
                phraseId = next.phraseId,
            ),
        )
    }

    private fun hotDecision(action: CoachAction, skillLevel: com.trustableai.koru.model.SkillLevel, nowMs: Long): CoachingQueue.QueuedDecision {
        val priority = actionPriority(action)
        return CoachingQueue.QueuedDecision(
            action = action,
            path = CoachingPath.HOT.bridgeValue(),
            text = phraseCatalog.render(action, skillLevel, activeCoachId),
            priority = priority,
            cornerPhase = currentPhase,
            timestampMs = nowMs,
            phraseId = phraseCatalog.phraseIdFor(action, skillLevel, activeCoachId),
        )
    }

    private fun adaptTiming(skillLevel: com.trustableai.koru.model.SkillLevel) {
        when (skillLevel) {
            com.trustableai.koru.model.SkillLevel.BEGINNER ->
                timingGate.updateConfig(3000L, setOf(CornerPhase.MID_CORNER, CornerPhase.APEX))
            com.trustableai.koru.model.SkillLevel.INTERMEDIATE ->
                timingGate.updateConfig(1500L, setOf(CornerPhase.APEX))
            com.trustableai.koru.model.SkillLevel.ADVANCED ->
                timingGate.updateConfig(1000L, emptySet())
        }
    }

    private fun actionPriority(action: CoachAction): Int {
        return when (action) {
            CoachAction.OVERSTEER_RECOVERY, CoachAction.BRAKE -> 0
            CoachAction.EARLY_THROTTLE, CoachAction.LIFT_MID_CORNER, CoachAction.SPIKE_BRAKE -> 1
            CoachAction.COGNITIVE_OVERLOAD -> 2
            else -> 3
        }
    }
}
