package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.model.TelemetryFrame

data class AudioGateResult(
    val allowSpeak: Boolean,
    val reason: String? = null,
)

data class TimingProfile(
    val cooldownMs: Long,
    val deliveryMs: Long,
    val blackoutPhases: Set<CornerPhase>,
)

object LiveAudioPolicy {
    private const val MIN_MOVING_SPEED_MPH = 18.0
    private const val MIN_FEEDFORWARD_SPEED_MPH = 25.0
    private const val MIN_EDGE_CONFIDENCE = 0.68
    private const val MIN_CAMERA_DIRECT_CONFIDENCE = 0.58

    fun timingProfile(skillLevel: SkillLevel): TimingProfile {
        return when (skillLevel) {
            SkillLevel.BEGINNER ->
                TimingProfile(
                    cooldownMs = 3000L,
                    deliveryMs = 2000L,
                    blackoutPhases = setOf(CornerPhase.MID_CORNER, CornerPhase.APEX),
                )
            SkillLevel.INTERMEDIATE ->
                TimingProfile(
                    cooldownMs = 1500L,
                    deliveryMs = 1800L,
                    blackoutPhases = setOf(CornerPhase.APEX),
                )
            SkillLevel.ADVANCED ->
                TimingProfile(
                    cooldownMs = 650L,
                    deliveryMs = 1200L,
                    blackoutPhases = emptySet(),
                )
        }
    }

    fun shouldSpeak(frame: TelemetryFrame, decision: CoachingDecision): AudioGateResult {
        if (decision.priority == 0) return AudioGateResult(true)
        if (decision.text.isBlank()) return AudioGateResult(false, "blank_text")

        if (frame.sourceMode != SessionMode.CAMERA_DIRECT) {
            if (frame.speedMph < MIN_MOVING_SPEED_MPH) {
                return AudioGateResult(false, "below_min_speed")
            }
            if (decision.path == CoachingPath.FEEDFORWARD && frame.speedMph < MIN_FEEDFORWARD_SPEED_MPH) {
                return AudioGateResult(false, "feedforward_speed_gate")
            }
        }

        val confidence = decision.confidence
        if (confidence != null) {
            val threshold = when {
                frame.sourceMode == SessionMode.CAMERA_DIRECT -> MIN_CAMERA_DIRECT_CONFIDENCE
                decision.path == CoachingPath.EDGE -> MIN_EDGE_CONFIDENCE
                else -> 0.0
            }
            if (confidence < threshold) {
                return AudioGateResult(false, "confidence_${"%.2f".format(confidence)}_lt_${"%.2f".format(threshold)}")
            }
        }

        return AudioGateResult(true)
    }
}
