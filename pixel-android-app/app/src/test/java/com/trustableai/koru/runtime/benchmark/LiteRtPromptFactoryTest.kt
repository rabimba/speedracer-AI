package com.trustableai.koru.runtime.benchmark

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.SkillLevel
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtPromptFactoryTest {

    @Test
    fun `compact prompt reads speed and decel from production feature keys`() {
        val window = EdgeReasoningWindow(
            triggerId = "exit_hesitation",
            actionHint = CoachAction.THROTTLE,
            priority = 2,
            suggestedText = "Commit on exit throttle.",
            phase = CornerPhase.EXIT,
            skillLevel = SkillLevel.INTERMEDIATE,
            cornerName = "T4",
            features = mapOf(
                "speed_mph" to 58.0,
                "throttle" to 35.0,
                "brake" to 0.0,
                "g_lat" to 0.1,
                "g_long" to -0.8,
                "coasting_ratio" to 0.1,
                "cognitive_load" to 0.3,
            ),
        )

        val prompt = LiteRtPromptFactory.promptFor(window, LiteRtPromptStyle.COMPACT)

        assertTrue("compact prompt should show actual speed, not '?'", prompt.contains("speed=58"))
        assertTrue("compact prompt should show actual decel, not '?'", prompt.contains("decel=-0.8"))
    }

    @Test
    fun `compact prompt shows question marks when feature keys are missing`() {
        val window = EdgeReasoningWindow(
            triggerId = "exit_hesitation",
            actionHint = CoachAction.THROTTLE,
            priority = 2,
            suggestedText = "Commit on exit throttle.",
            phase = CornerPhase.EXIT,
            skillLevel = SkillLevel.INTERMEDIATE,
            cornerName = "T4",
            features = emptyMap(),
        )

        val prompt = LiteRtPromptFactory.promptFor(window, LiteRtPromptStyle.COMPACT)

        assertTrue("missing speed should show '?'", prompt.contains("speed=?"))
        assertTrue("missing decel should show '?'", prompt.contains("decel=?"))
    }

    @Test
    fun `sample window uses production feature keys so compact prompt renders real values`() {
        val window = LiteRtPromptFactory.sampleWindow()
        val prompt = LiteRtPromptFactory.promptFor(window, LiteRtPromptStyle.COMPACT)

        assertTrue("sample window compact prompt should show real speed", !prompt.contains("speed=?"))
        assertTrue("sample window compact prompt should show real decel", !prompt.contains("decel=?"))
    }

    @Test
    fun `full prompt includes all sorted features`() {
        val window = EdgeReasoningWindow(
            triggerId = "brake_trace_anomaly",
            actionHint = CoachAction.SPIKE_BRAKE,
            priority = 1,
            suggestedText = "Squeeze, don't stab.",
            phase = CornerPhase.BRAKE_ZONE,
            skillLevel = SkillLevel.INTERMEDIATE,
            cornerName = "T11",
            features = mapOf(
                "speed_mph" to 82.0,
                "brake" to 85.0,
                "g_long" to -1.0,
                "g_lat" to 0.12,
            ),
        )

        val prompt = LiteRtPromptFactory.promptFor(window, LiteRtPromptStyle.FULL)

        assertTrue("full prompt should include speed_mph", prompt.contains("speed_mph=82.00"))
        assertTrue("full prompt should include brake", prompt.contains("brake=85.00"))
        assertTrue("full prompt should include trigger", prompt.contains("brake_trace_anomaly"))
    }
}
