package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.Corner
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.model.Track

object TrackExpertiseCatalog {
    private fun isSonoma(track: Track?): Boolean = track?.name == TrackCatalog.sonomaRaceway.name

    fun feedforwardText(track: Track?, corner: Corner, skillLevel: SkillLevel): String {
        if (!isSonoma(track)) {
            return "${corner.name}: ${corner.advice}"
        }
        return when (corner.id) {
            1 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turn 1: eyes up, brake straight, then clean turn-in.",
                standard = "Turn 1: settle the platform early and keep the hands quiet over the first direction change.",
            )
            2 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turn 2: eyes to the curb, stay wide, trail brake in.",
                standard = "Turn 2: stay wide, trail brake to the curb, and do not go to throttle until you reach the apex.",
            )
            3 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turn 3: late apex. Wait longer, then commit hard on exit.",
                standard = "Turn 3: slow in, fast out. This is a patience corner. Late apex it and make the exit speed count.",
            )
            31 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turn 3A: finish the hard brake straight, then be patient.",
                standard = "Turn 3A: brake in a straight line, let the car rotate over the crest, and do not rush the throttle.",
            )
            6 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turn 6 Carousel: hug the inside and keep maintenance throttle.",
                standard = "Turn 6 Carousel: distance is king. Stay tight, use the curb, and keep a calm maintenance throttle.",
            )
            7 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turn 7: double apex. First apex, rotate, second apex.",
                standard = "Turn 7: double-apex it. Cut the first distance, rotate in the middle, then drive to the second apex.",
            )
            910 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turns 9-10: eyes up for the bridge mark, open 9, then straighten.",
                standard = "Turns 9-10: sacrifice Turn 9, straighten the car, and build the best possible run into the final braking sequence.",
            )
            11 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turn 11: eyes to exit first. Big squeeze, clean release.",
                standard = "Turn 11: finish the heavy brake work in a straight line, taper the release, and protect the exit over hero entry speed.",
            )
            12 -> beginnerAware(
                skillLevel = skillLevel,
                beginner = "Turn 12: look down the straight, unwind early.",
                standard = "Turn 12: free the hands early and commit to front-straight exit speed.",
            )
            else -> "${corner.name}: ${corner.advice}"
        }
    }

    fun edgeText(
        track: Track?,
        corner: Corner?,
        triggerId: String,
        defaultText: String,
        skillLevel: SkillLevel,
        phase: CornerPhase,
    ): String {
        if (!isSonoma(track)) return defaultText

        return when (triggerId) {
            "brake_trace_anomaly" -> when (corner?.id) {
                11 -> "Turn 11 brake trace is too sharp. Squeeze it, then taper off as the wheel comes in."
                31 -> "Turn 3A brake release is too abrupt. Finish the hard stop straight, then bleed off smoothly."
                else -> "Brake trace is peaky. Make it a ski-slope release, not a cliff."
            }

            "exit_hesitation" -> when (corner?.id) {
                3 -> "Turn 3 exit is the lap-maker. Wait for the apex, then go full throttle."
                7 -> "Turn 7 second apex is done. Finish the unwind and commit to power."
                11, 12 -> "This exit carries. Free the hands, then commit to throttle."
                else -> "Sonoma rewards exit speed here. Finish the release and go to power."
            }

            "repeated_coasting" -> when (corner?.id) {
                6 -> "Do not coast through the Carousel. Hold maintenance throttle and keep the car loaded."
                7 -> "Stop floating the middle of Turn 7. Pick maintenance throttle through the double apex."
                else -> "Sonoma punishes coasting. Pick a pedal and stay committed."
            }

            "visual_flow_instability" -> when (corner?.id) {
                6, 7 -> "Picture is busy in the middle of the corner. Quiet the hands and keep the platform calm."
                else -> "Visual picture is unstable. Smooth the hands before the next big input."
            }

            "overload_window" ->
                if (skillLevel == SkillLevel.BEGINNER) {
                    "Too much at once. Go back to marks and vision this lap."
                } else {
                    "Mental load is high. One clean change only this lap."
                }

            "corner_tactic" -> when (corner?.id) {
                2 -> "Turn 2: stay wide, trail brake in, and wait for the curb before throttle."
                3 -> "Turn 3: late apex. Keep the release patient so the exit stays open."
                31 -> "Turn 3A: finish the brake zone straight and be patient over the crest."
                6 -> "Carousel: hug the inside and do not give away distance for extra speed."
                7 -> "Turn 7: first apex, rotate, second apex. Stay tight in the middle."
                910 -> "Open Turn 9, straighten the car, then brake cleanly into the final sequence."
                11 -> "Turn 11: heavy brake first, clean release second, exit speed third."
                12 -> "Turn 12: unwind the wheel and build the front-straight run."
                else -> defaultText
            }

            else ->
                if (phase == CornerPhase.EXIT && corner != null) {
                    feedforwardText(track, corner, skillLevel)
                } else {
                    defaultText
                }
        }
    }

    fun shouldSuppressHotAction(
        track: Track?,
        corner: Corner?,
        action: CoachAction,
        skillLevel: SkillLevel,
        phase: CornerPhase,
        timeSeconds: Double,
        cognitiveLoad: Double,
    ): Boolean {
        if (!isSonoma(track)) return false

        if (cognitiveLoad > 0.72 && action in setOf(CoachAction.HUSTLE, CoachAction.PUSH, CoachAction.FULL_THROTTLE, CoachAction.COMMIT)) {
            return true
        }

        val sessionPhase = sessionPhase(skillLevel, timeSeconds)
        if (skillLevel == SkillLevel.BEGINNER && sessionPhase < 3 && action in setOf(CoachAction.HUSTLE, CoachAction.FULL_THROTTLE)) {
            return true
        }
        if (skillLevel == SkillLevel.BEGINNER && sessionPhase == 1 && action in setOf(CoachAction.TRAIL_BRAKE, CoachAction.COMMIT)) {
            return true
        }
        if ((corner?.id == 6 || corner?.id == 7) && action == CoachAction.PUSH) {
            return true
        }
        if (corner?.id == 3 && action in setOf(CoachAction.THROTTLE, CoachAction.FULL_THROTTLE, CoachAction.HUSTLE)) {
            return phase != CornerPhase.EXIT && phase != CornerPhase.ACCELERATION
        }
        return false
    }

    fun hotActionText(
        track: Track?,
        corner: Corner?,
        action: CoachAction,
        skillLevel: SkillLevel,
        phase: CornerPhase,
        timeSeconds: Double,
        cognitiveLoad: Double,
    ): String? {
        if (!isSonoma(track)) return null
        val sessionPhase = sessionPhase(skillLevel, timeSeconds)

        if (action == CoachAction.COGNITIVE_OVERLOAD || cognitiveLoad > 0.72) {
            return if (skillLevel == SkillLevel.BEGINNER) {
                "Too much at once. Eyes up and hit your marks."
            } else {
                "One clean change only. Reset to marks and vision."
            }
        }

        return when (corner?.id) {
            3 -> when {
                action in setOf(CoachAction.THROTTLE, CoachAction.FULL_THROTTLE, CoachAction.HUSTLE) &&
                    (phase == CornerPhase.EXIT || phase == CornerPhase.ACCELERATION) ->
                    "Turn 3: late apex finished. Now commit all the way out."
                action == CoachAction.COAST || action == CoachAction.LIFT_MID_CORNER ->
                    "Turn 3: do not float the exit. Be patient, then full throttle."
                action == CoachAction.EARLY_THROTTLE ->
                    "Turn 3: too early. Wait for the late apex before throttle."
                else -> null
            }

            6 -> when (action) {
                CoachAction.COAST, CoachAction.LIFT_MID_CORNER, CoachAction.THROTTLE ->
                    "Carousel: keep maintenance throttle and stay tight to the curb."
                CoachAction.PUSH ->
                    "Carousel: do not chase speed. Distance is king here."
                else -> null
            }

            7 -> when {
                action == CoachAction.COAST || action == CoachAction.LIFT_MID_CORNER ->
                    "Turn 7: maintenance throttle through the middle, then drive to second apex."
                action in setOf(CoachAction.THROTTLE, CoachAction.FULL_THROTTLE, CoachAction.HUSTLE) &&
                    (phase == CornerPhase.EXIT || phase == CornerPhase.ACCELERATION) ->
                    "Turn 7: second apex is done. Unwind and commit to power."
                else -> null
            }

            910 -> when (action) {
                CoachAction.BRAKE, CoachAction.WAIT, CoachAction.HESITATION ->
                    "Turns 9-10: sacrifice 9, straighten the car, then brake for 10."
                else -> null
            }

            11 -> when {
                action in setOf(CoachAction.BRAKE, CoachAction.SPIKE_BRAKE, CoachAction.TRAIL_BRAKE) ->
                    "Turn 11: big squeeze first, then taper the release cleanly."
                action in setOf(CoachAction.THROTTLE, CoachAction.FULL_THROTTLE, CoachAction.HUSTLE) &&
                    sessionPhase >= 2 ->
                    "Turn 11 exit matters. Free the hands, then commit down the next straight."
                else -> null
            }

            12 -> when {
                action in setOf(CoachAction.THROTTLE, CoachAction.FULL_THROTTLE, CoachAction.HUSTLE) &&
                    sessionPhase >= 2 ->
                    "Turn 12: unwind early and build the front-straight run."
                else -> null
            }

            else -> {
                if (sessionPhase == 1 && skillLevel == SkillLevel.BEGINNER && action in setOf(CoachAction.PUSH, CoachAction.HUSTLE)) {
                    "First get the marks right. Speed comes after the line is clean."
                } else {
                    null
                }
            }
        }
    }

    private fun beginnerAware(skillLevel: SkillLevel, beginner: String, standard: String): String {
        return when (skillLevel) {
            SkillLevel.BEGINNER -> beginner
            SkillLevel.INTERMEDIATE -> standard
            SkillLevel.ADVANCED -> standard
        }
    }

    private fun sessionPhase(skillLevel: SkillLevel, timeSeconds: Double): Int {
        return when {
            skillLevel == SkillLevel.ADVANCED -> 3
            timeSeconds > 180.0 -> 3
            timeSeconds > 60.0 -> 2
            else -> 1
        }
    }
}
