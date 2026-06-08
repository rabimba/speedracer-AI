package com.trustableai.koru.runtime

import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionGoalFocus
import com.trustableai.koru.model.SessionGoalSource
import com.trustableai.koru.model.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachRecommenderTest {
    @Test
    fun `defaults to superaj when no goals selected`() {
        assertEquals(
            "superaj",
            CoachRecommender.recommendCoachId(emptyList(), SessionMode.TELEMETRY),
        )
    }

    @Test
    fun `braking goals favor rachel`() {
        val goals = listOf(
            SessionGoal(
                id = "goal-braking",
                focus = SessionGoalFocus.BRAKING,
                description = "Brake harder",
                source = SessionGoalSource.PRE_RACE_CHAT,
                prioritizedActions = emptyList(),
            ),
        )

        assertEquals("rachel", CoachRecommender.recommendCoachId(goals, SessionMode.TELEMETRY))
    }

    @Test
    fun `throttle goals favor tony`() {
        val goals = listOf(
            SessionGoal(
                id = "goal-throttle",
                focus = SessionGoalFocus.THROTTLE,
                description = "Commit on exit",
                source = SessionGoalSource.PRE_RACE_CHAT,
                prioritizedActions = emptyList(),
            ),
        )

        assertEquals("tony", CoachRecommender.recommendCoachId(goals, SessionMode.TELEMETRY))
    }
}
