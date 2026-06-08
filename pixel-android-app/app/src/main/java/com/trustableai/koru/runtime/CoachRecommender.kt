package com.trustableai.koru.runtime

import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionGoalFocus
import com.trustableai.koru.model.SessionMode

object CoachRecommender {
    fun recommendCoachId(
        goals: List<SessionGoal>,
        sessionMode: SessionMode,
    ): String {
        if (goals.isEmpty()) {
            return "superaj"
        }

        val scores = mutableMapOf(
            "tony" to 0,
            "rachel" to 0,
            "aj" to 0,
            "garmin" to 0,
            "superaj" to 1,
        )

        goals.forEach { goal ->
            when (goal.focus) {
                SessionGoalFocus.BRAKING -> {
                    bump(scores, "rachel", 4)
                    bump(scores, "garmin", 2)
                    bump(scores, "superaj", 1)
                }
                SessionGoalFocus.THROTTLE -> {
                    bump(scores, "tony", 4)
                    bump(scores, "superaj", 2)
                    bump(scores, "aj", 1)
                }
                SessionGoalFocus.VISION -> {
                    bump(scores, "superaj", 3)
                    bump(scores, "tony", 1)
                }
                SessionGoalFocus.LINES -> {
                    bump(scores, "garmin", 3)
                    bump(scores, "rachel", 2)
                }
                SessionGoalFocus.SMOOTHNESS -> {
                    bump(scores, "rachel", 3)
                    bump(scores, "superaj", 2)
                }
                SessionGoalFocus.CUSTOM -> bump(scores, "superaj", 2)
            }
        }

        if (sessionMode == SessionMode.CAMERA_DIRECT || sessionMode == SessionMode.DEVICE_TEST) {
            bump(scores, "superaj", 2)
        }
        if (goals.size >= 2) {
            bump(scores, "superaj", 1)
        }

        return scores.maxByOrNull { it.value }?.key ?: "superaj"
    }

    private fun bump(scores: MutableMap<String, Int>, coachId: String, amount: Int) {
        scores[coachId] = (scores[coachId] ?: 0) + amount
    }
}
