package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingObjective
import com.trustableai.koru.model.Corner
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.DriverState
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.Track

data class CoachingIntentEvaluation(
    val suppressed: Boolean = false,
    val action: CoachAction,
    val objective: CoachingObjective,
    val priority: Int,
    val text: String? = null,
    val causeId: String? = null,
)

object CornerDoctrineCatalog {
    private val brakingCorners = setOf(1, 2, 31, 11)
    private val exitPriorityCorners = setOf(3, 7, 11, 12)
    private val maintenanceCorners = setOf(6, 7)
    private val brakeActions = setOf(
        CoachAction.BRAKE,
        CoachAction.THRESHOLD,
        CoachAction.TRAIL_BRAKE,
        CoachAction.SPIKE_BRAKE,
    )
    private val throttleActions = setOf(
        CoachAction.THROTTLE,
        CoachAction.PUSH,
        CoachAction.FULL_THROTTLE,
        CoachAction.COMMIT,
        CoachAction.HUSTLE,
    )

    fun evaluateHotAction(
        track: Track?,
        corner: Corner?,
        phase: CornerPhase,
        action: CoachAction,
        frame: TelemetryFrame,
        driverState: DriverState,
        isEmergencyBrake: Boolean,
    ): CoachingIntentEvaluation {
        if (action == CoachAction.OVERSTEER_RECOVERY) {
            return CoachingIntentEvaluation(
                action = action,
                objective = CoachingObjective.SAFETY_RECOVERY,
                priority = 0,
                causeId = "oversteer_recovery",
            )
        }

        val objective = objectiveFor(action, corner, phase)
        val basePriority = priorityFor(action, objective, isEmergencyBrake)
        if (!isSonoma(track)) {
            return CoachingIntentEvaluation(action = action, objective = objective, priority = basePriority)
        }

        if (action == CoachAction.BRAKE && !allowsEmergencyBrake(track, corner, phase, frame, isEmergencyBrake)) {
            return suppressed(action, objective)
        }

        val text = sonomaText(corner, phase, action, objective, driverState.skillLevel)
        val suppress = shouldSuppressSonomaAction(corner, phase, action, objective, isEmergencyBrake, driverState)
        return if (suppress) {
            suppressed(action, objective)
        } else {
            CoachingIntentEvaluation(
                action = action,
                objective = objective,
                priority = basePriority,
                text = text,
                causeId = causeFor(action, objective, corner),
            )
        }
    }

    fun edgeTactic(
        track: Track?,
        corner: Corner?,
        phase: CornerPhase,
        frame: TelemetryFrame,
        driverState: DriverState,
    ): CoachingIntentEvaluation? {
        if (corner == null || phase != CornerPhase.BRAKE_ZONE || frame.speedMph <= 70.0) return null
        if (!isSonoma(track)) {
            return CoachingIntentEvaluation(
                action = CoachAction.WAIT,
                objective = CoachingObjective.LINE_VISION,
                priority = 2,
                text = "Look through the entry. Trim speed only while straight.",
                causeId = "corner_tactic",
            )
        }

        return when (corner.id) {
            3 -> CoachingIntentEvaluation(
                action = CoachAction.WAIT,
                objective = CoachingObjective.ROTATE_WAIT,
                priority = 2,
                text = "Turn 3: late apex first. Do not chase entry speed.",
                causeId = "sonoma_t3_late_apex",
            )
            6 -> CoachingIntentEvaluation(
                action = CoachAction.MAINTAIN,
                objective = CoachingObjective.MAINTENANCE_THROTTLE,
                priority = 2,
                text = "Carousel: stay tight and hold calm maintenance throttle.",
                causeId = "sonoma_carousel_distance",
            )
            7 -> CoachingIntentEvaluation(
                action = CoachAction.ROTATE,
                objective = CoachingObjective.LINE_VISION,
                priority = 2,
                text = "Turn 7: first apex, rotate, second apex.",
                causeId = "sonoma_t7_double_apex",
            )
            12 -> CoachingIntentEvaluation(
                action = CoachAction.THROTTLE,
                objective = CoachingObjective.EXIT_THROTTLE,
                priority = 2,
                text = "Turn 12: unwind early and build the straight.",
                causeId = "sonoma_t12_exit",
            )
            11, 31 -> {
                val emergency = allowsEmergencyBrake(track, corner, phase, frame, isEmergencyBrake = true)
                if (emergency) {
                    CoachingIntentEvaluation(
                        action = CoachAction.BRAKE,
                        objective = CoachingObjective.BRAKE_ENTRY,
                        priority = 1,
                        text = sonomaText(corner, phase, CoachAction.BRAKE, CoachingObjective.BRAKE_ENTRY, driverState.skillLevel),
                        causeId = "sonoma_brake_entry",
                    )
                } else {
                    CoachingIntentEvaluation(
                        action = CoachAction.WAIT,
                        objective = CoachingObjective.BRAKE_RELEASE,
                        priority = 2,
                        text = sonomaText(corner, phase, CoachAction.WAIT, CoachingObjective.BRAKE_RELEASE, driverState.skillLevel),
                        causeId = "sonoma_end_of_braking",
                    )
                }
            }
            1, 2, 910 -> CoachingIntentEvaluation(
                action = CoachAction.WAIT,
                objective = CoachingObjective.LINE_VISION,
                priority = 2,
                text = sonomaText(corner, phase, CoachAction.WAIT, CoachingObjective.LINE_VISION, driverState.skillLevel),
                causeId = "sonoma_line_setup",
            )
            else -> null
        }
    }

    fun allowsEmergencyBrake(
        track: Track?,
        corner: Corner?,
        phase: CornerPhase,
        frame: TelemetryFrame,
        isEmergencyBrake: Boolean = true,
    ): Boolean {
        if (!isEmergencyBrake || phase != CornerPhase.BRAKE_ZONE) return false
        val target = corner?.targetSpeed ?: 60.0
        val notActuallyBraking = frame.brake < 12.0 && frame.gLong > -0.25
        if (!notActuallyBraking) return false
        if (!isSonoma(track)) return frame.speedMph >= target + 25.0 || frame.speedMph >= 85.0

        val cornerId = corner?.id ?: return frame.speedMph >= 95.0
        if (cornerId in brakingCorners || cornerId == 910) {
            return frame.speedMph >= target + 25.0 || frame.speedMph >= 85.0
        }
        return frame.speedMph >= target + 40.0 || frame.speedMph >= 105.0
    }

    fun priorityFor(action: CoachAction, objective: CoachingObjective, isEmergencyBrake: Boolean = false): Int {
        return when {
            action == CoachAction.OVERSTEER_RECOVERY -> 0
            action == CoachAction.BRAKE && isEmergencyBrake -> 0
            action == CoachAction.BRAKE -> 1
            action == CoachAction.SPIKE_BRAKE -> 1
            action == CoachAction.EARLY_THROTTLE || action == CoachAction.LIFT_MID_CORNER -> 1
            objective == CoachingObjective.BRAKE_RELEASE -> 1
            action == CoachAction.COGNITIVE_OVERLOAD -> 2
            objective == CoachingObjective.LINE_VISION || objective == CoachingObjective.ROTATE_WAIT -> 2
            else -> 3
        }
    }

    fun objectiveFor(action: CoachAction, corner: Corner?, phase: CornerPhase): CoachingObjective {
        return when (action) {
            CoachAction.OVERSTEER_RECOVERY -> CoachingObjective.SAFETY_RECOVERY
            CoachAction.BRAKE, CoachAction.THRESHOLD -> CoachingObjective.BRAKE_ENTRY
            CoachAction.TRAIL_BRAKE, CoachAction.SPIKE_BRAKE -> CoachingObjective.BRAKE_RELEASE
            CoachAction.WAIT, CoachAction.ROTATE -> CoachingObjective.ROTATE_WAIT
            CoachAction.TURN_IN, CoachAction.APEX, CoachAction.PUSH -> CoachingObjective.LINE_VISION
            CoachAction.THROTTLE, CoachAction.FULL_THROTTLE, CoachAction.HUSTLE -> {
                if (corner?.id in maintenanceCorners && phase in setOf(CornerPhase.MID_CORNER, CornerPhase.APEX)) {
                    CoachingObjective.MAINTENANCE_THROTTLE
                } else {
                    CoachingObjective.EXIT_THROTTLE
                }
            }
            CoachAction.COMMIT -> if (phase == CornerPhase.EXIT || phase == CornerPhase.ACCELERATION) {
                CoachingObjective.EXIT_THROTTLE
            } else {
                CoachingObjective.LINE_VISION
            }
            CoachAction.MAINTAIN, CoachAction.COAST, CoachAction.LIFT_MID_CORNER -> {
                if (corner?.id in maintenanceCorners || phase == CornerPhase.MID_CORNER || phase == CornerPhase.APEX) {
                    CoachingObjective.MAINTENANCE_THROTTLE
                } else {
                    CoachingObjective.SMOOTHNESS
                }
            }
            CoachAction.STABILIZE, CoachAction.HESITATION, CoachAction.COGNITIVE_OVERLOAD -> CoachingObjective.SMOOTHNESS
            CoachAction.EARLY_THROTTLE -> CoachingObjective.ROTATE_WAIT
        }
    }

    private fun shouldSuppressSonomaAction(
        corner: Corner?,
        phase: CornerPhase,
        action: CoachAction,
        objective: CoachingObjective,
        isEmergencyBrake: Boolean,
        driverState: DriverState,
    ): Boolean {
        if (driverState.cognitiveLoad > 0.72 && action in setOf(CoachAction.HUSTLE, CoachAction.PUSH, CoachAction.FULL_THROTTLE)) {
            return true
        }
        if (action == CoachAction.BRAKE) return !isEmergencyBrake
        return when (corner?.id) {
            3 -> {
                action in brakeActions ||
                    (action in throttleActions && phase != CornerPhase.EXIT && phase != CornerPhase.ACCELERATION)
            }
            6 -> action in brakeActions || action in setOf(CoachAction.PUSH, CoachAction.HUSTLE, CoachAction.FULL_THROTTLE)
            7 -> action in setOf(CoachAction.BRAKE, CoachAction.THRESHOLD, CoachAction.PUSH) ||
                (action in setOf(CoachAction.FULL_THROTTLE, CoachAction.HUSTLE) && phase != CornerPhase.EXIT && phase != CornerPhase.ACCELERATION)
            12 -> action in brakeActions && !isEmergencyBrake
            else -> objective == CoachingObjective.NO_CUE
        }
    }

    private fun sonomaText(
        corner: Corner?,
        phase: CornerPhase,
        action: CoachAction,
        objective: CoachingObjective,
        skillLevel: SkillLevel,
    ): String? {
        return when (corner?.id) {
            3 -> when (objective) {
                CoachingObjective.ROTATE_WAIT -> "Turn 3: late apex first. Wait, then throttle."
                CoachingObjective.EXIT_THROTTLE -> "Turn 3: apex done. Unwind and commit out."
                CoachingObjective.MAINTENANCE_THROTTLE -> "Turn 3: no floating. Patient, then full throttle."
                else -> null
            }
            6 -> when (objective) {
                CoachingObjective.MAINTENANCE_THROTTLE -> "Carousel: hug inside, hold maintenance throttle."
                CoachingObjective.LINE_VISION -> "Carousel: distance is king. Stay tight to curb."
                else -> null
            }
            7 -> when (objective) {
                CoachingObjective.MAINTENANCE_THROTTLE -> "Turn 7: maintenance throttle through the middle."
                CoachingObjective.LINE_VISION -> "Turn 7: first apex, rotate, second apex."
                CoachingObjective.EXIT_THROTTLE -> "Turn 7: second apex done. Unwind and power."
                else -> null
            }
            910 -> when (objective) {
                CoachingObjective.LINE_VISION, CoachingObjective.ROTATE_WAIT ->
                    "Turns 9-10: open 9, straighten, then set up 10."
                CoachingObjective.BRAKE_RELEASE ->
                    "Turns 9-10: straighten first, then squeeze and release."
                else -> null
            }
            11 -> when (objective) {
                CoachingObjective.BRAKE_ENTRY ->
                    if (action == CoachAction.BRAKE) "Turn 11: brake now, straight and firm." else "Turn 11: big squeeze, then taper cleanly."
                CoachingObjective.BRAKE_RELEASE -> "Turn 11: end braking clean. Eyes to exit."
                CoachingObjective.EXIT_THROTTLE -> "Turn 11: free the hands, then commit down straight."
                else -> null
            }
            12 -> when (objective) {
                CoachingObjective.EXIT_THROTTLE, CoachingObjective.LINE_VISION -> "Turn 12: unwind early and build front-straight speed."
                else -> null
            }
            31 -> when (objective) {
                CoachingObjective.BRAKE_ENTRY -> "Turn 3A: finish the brake straight, then rotate."
                CoachingObjective.BRAKE_RELEASE -> "Turn 3A: release smoothly and let it rotate."
                CoachingObjective.ROTATE_WAIT -> "Turn 3A: patience over crest. Let it rotate."
                else -> null
            }
            2 -> when (objective) {
                CoachingObjective.LINE_VISION -> "Turn 2: stay wide, eyes to curb."
                CoachingObjective.BRAKE_RELEASE -> "Turn 2: trail to the curb, release smooth."
                CoachingObjective.ROTATE_WAIT -> "Turn 2: wait for the curb before throttle."
                else -> null
            }
            1 -> when (objective) {
                CoachingObjective.BRAKE_ENTRY, CoachingObjective.BRAKE_RELEASE -> "Turn 1: settle early, brake straight, quiet hands."
                CoachingObjective.LINE_VISION -> "Turn 1: eyes up and clean turn-in."
                else -> null
            }
            else -> if (skillLevel == SkillLevel.BEGINNER && phase == CornerPhase.APEX) {
                "Eyes up. One clean change at a time."
            } else {
                null
            }
        }
    }

    private fun causeFor(action: CoachAction, objective: CoachingObjective, corner: Corner?): String? {
        return when {
            action == CoachAction.OVERSTEER_RECOVERY -> "oversteer_recovery"
            action == CoachAction.BRAKE -> "emergency_brake_entry"
            objective == CoachingObjective.BRAKE_RELEASE -> "brake_release_shape"
            objective == CoachingObjective.MAINTENANCE_THROTTLE -> "maintenance_throttle_gap"
            objective == CoachingObjective.EXIT_THROTTLE -> "exit_throttle_commitment"
            objective == CoachingObjective.ROTATE_WAIT -> "apex_patience"
            objective == CoachingObjective.LINE_VISION && corner?.id != null -> "corner_line_objective"
            objective == CoachingObjective.SMOOTHNESS -> "input_smoothness"
            else -> null
        }
    }

    private fun suppressed(action: CoachAction, objective: CoachingObjective): CoachingIntentEvaluation {
        return CoachingIntentEvaluation(
            suppressed = true,
            action = action,
            objective = objective,
            priority = priorityFor(action, objective),
        )
    }

    private fun isSonoma(track: Track?): Boolean = track?.name == TrackCatalog.sonomaRaceway.name
}
