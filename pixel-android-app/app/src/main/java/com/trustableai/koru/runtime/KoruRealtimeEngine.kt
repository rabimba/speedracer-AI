package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.Track
import com.trustableai.koru.model.bridgeValue
import com.trustableai.koru.runtime.deterministic.CoachingQueue
import com.trustableai.koru.runtime.deterministic.CornerPhaseDetector
import com.trustableai.koru.runtime.deterministic.DecisionMatrix
import com.trustableai.koru.runtime.deterministic.DriverModel
import com.trustableai.koru.runtime.deterministic.EdgeTriggerEvaluator
import com.trustableai.koru.runtime.deterministic.TimingGate
import com.trustableai.koru.runtime.reasoner.OnDeviceReasoner

class KoruRealtimeEngine(
    track: Track?,
    private val phraseCatalog: PhraseCatalog,
    private var sessionGoals: List<SessionGoal> = emptyList(),
    private val reasonerProvider: () -> OnDeviceReasoner,
) {
    private val track = track
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

    fun setSessionGoals(goals: List<SessionGoal>) {
        sessionGoals = goals.take(3)
    }

    suspend fun processFrame(frame: TelemetryFrame, nowMs: Long = System.currentTimeMillis()): List<CoachingDecision> {
        val detection = cornerDetector.detect(frame)
        currentPhase = detection.phase
        timingGate.update(currentPhase, nowMs)
        driverModel.update(frame)
        val driverState = driverModel.state()

        adaptTiming(driverState.skillLevel)

        DecisionMatrix.evaluateAll(frame)
            .mapIndexedNotNull { index, action ->
                val suppress =
                    TrackExpertiseCatalog.shouldSuppressHotAction(
                        track = track,
                        corner = detection.corner,
                        action = action,
                        skillLevel = driverState.skillLevel,
                        phase = currentPhase,
                        timeSeconds = frame.timeSeconds,
                        cognitiveLoad = driverState.cognitiveLoad,
                    )
                if (action == lastHotAction || suppress) {
                    null
                } else {
                    HotActionCandidate(
                        action = action,
                        order = index,
                        priority = actionPriority(action),
                        goalBoost = goalBoostForAction(action),
                    )
                }
            }
            .sortedWith(
                compareBy<HotActionCandidate> { it.priority }
                    .thenByDescending { it.goalBoost }
                    .thenBy { it.order },
            )
            .firstOrNull()
            ?.let { candidate ->
                lastHotAction = candidate.action
                val decision =
                    hotDecision(
                        action = candidate.action,
                        corner = detection.corner,
                        skillLevel = driverState.skillLevel,
                        cognitiveLoad = driverState.cognitiveLoad,
                        timeSeconds = frame.timeSeconds,
                        nowMs = nowMs,
                    )
                if (decision.priority == 0) {
                    queue.enqueue(queue.preempt(decision), nowMs)
                } else {
                    queue.enqueue(decision, nowMs)
                }
            }

        detection.corner?.takeIf { it.id != lastFeedforwardCornerId }?.let { corner ->
            lastFeedforwardCornerId = corner.id
            queue.enqueue(
                CoachingQueue.QueuedDecision(
                    action = null,
                    path = CoachingPath.FEEDFORWARD.bridgeValue(),
                    text = TrackExpertiseCatalog.feedforwardText(track = track, corner = corner, skillLevel = driverState.skillLevel),
                    priority = 1,
                    cornerPhase = currentPhase,
                    timestampMs = nowMs,
                ),
                nowMs,
            )
        }

        val reasoner = reasonerProvider()
        edgeTriggerEvaluator.evaluate(frame, currentPhase, driverState, track, detection.corner, nowMs)?.let { window ->
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

    private fun hotDecision(
        action: CoachAction,
        corner: com.trustableai.koru.model.Corner?,
        skillLevel: com.trustableai.koru.model.SkillLevel,
        cognitiveLoad: Double,
        timeSeconds: Double,
        nowMs: Long,
    ): CoachingQueue.QueuedDecision {
        val priority = actionPriority(action)
        val doctrineText =
            TrackExpertiseCatalog.hotActionText(
                track = track,
                corner = corner,
                action = action,
                skillLevel = skillLevel,
                phase = currentPhase,
                timeSeconds = timeSeconds,
                cognitiveLoad = cognitiveLoad,
            )
        return CoachingQueue.QueuedDecision(
            action = action,
            path = CoachingPath.HOT.bridgeValue(),
            text = doctrineText ?: phraseCatalog.render(action, skillLevel, activeCoachId),
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

    private fun goalBoostForAction(action: CoachAction): Int {
        return sessionGoals.count { goal -> goal.prioritizedActions.contains(action) }
    }

    private data class HotActionCandidate(
        val action: CoachAction,
        val order: Int,
        val priority: Int,
        val goalBoost: Int,
    )
}
