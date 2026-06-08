package com.trustableai.koru.runtime.benchmark

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.SkillLevel

enum class LiteRtPromptStyle {
    FULL,
    COMPACT,
    MINIMAL,
}

data class LiteRtInferenceConfig(
    val name: String,
    val backend: LlmInference.Backend = LlmInference.Backend.GPU,
    val maxTokens: Int = 32,
    val maxTopK: Int = 16,
    val sessionTopK: Int = 4,
    val sessionTopP: Float = 0.75f,
    val sessionTemperature: Float = 0.10f,
    val promptStyle: LiteRtPromptStyle = LiteRtPromptStyle.COMPACT,
    val runWarmupPass: Boolean = true,
    val urgentThreadPriority: Boolean = true,
) {
    companion object {
        val PRODUCTION =
            LiteRtInferenceConfig(
                name = "production",
                backend = LlmInference.Backend.GPU,
                maxTokens = 32,
                maxTopK = 16,
                sessionTopK = 4,
                sessionTopP = 0.75f,
                sessionTemperature = 0.10f,
                promptStyle = LiteRtPromptStyle.COMPACT,
                runWarmupPass = true,
                urgentThreadPriority = true,
            )

        fun benchmarkMatrix(): List<LiteRtInferenceConfig> =
            listOf(
                baselineGpuFull(),
                gpuCompactPrompt(),
                gpuLowTopK(),
                gpuLowMaxTokens(),
                gpuFastSampling(),
                cpuFull(),
                gpuWarmupOnly(),
                gpuThreadPriority(),
                gpuCombinedBest(),
            )

        private fun baselineGpuFull() =
            LiteRtInferenceConfig(
                name = "baseline_gpu_full",
                backend = LlmInference.Backend.GPU,
                maxTokens = 48,
                maxTopK = 32,
                sessionTopK = 8,
                sessionTopP = 0.80f,
                sessionTemperature = 0.15f,
                promptStyle = LiteRtPromptStyle.FULL,
                runWarmupPass = false,
                urgentThreadPriority = false,
            )

        private fun gpuCompactPrompt() =
            baselineGpuFull().copy(
                name = "gpu_compact_prompt",
                promptStyle = LiteRtPromptStyle.COMPACT,
            )

        private fun gpuLowTopK() =
            baselineGpuFull().copy(
                name = "gpu_low_topk",
                maxTopK = 16,
                sessionTopK = 4,
            )

        private fun gpuLowMaxTokens() =
            baselineGpuFull().copy(
                name = "gpu_low_max_tokens",
                maxTokens = 24,
            )

        private fun gpuFastSampling() =
            baselineGpuFull().copy(
                name = "gpu_fast_sampling",
                sessionTopK = 4,
                sessionTopP = 0.70f,
                sessionTemperature = 0.10f,
            )

        private fun cpuFull() =
            baselineGpuFull().copy(
                name = "cpu_full",
                backend = LlmInference.Backend.CPU,
            )

        private fun gpuWarmupOnly() =
            baselineGpuFull().copy(
                name = "gpu_warmup",
                runWarmupPass = true,
            )

        private fun gpuThreadPriority() =
            baselineGpuFull().copy(
                name = "gpu_thread_priority",
                urgentThreadPriority = true,
            )

        private fun gpuCombinedBest() = PRODUCTION.copy(name = "gpu_combined_best")
    }
}

object LiteRtPromptFactory {
    fun promptFor(
        window: EdgeReasoningWindow,
        style: LiteRtPromptStyle,
    ): String =
        when (style) {
            LiteRtPromptStyle.FULL -> fullPrompt(window)
            LiteRtPromptStyle.COMPACT -> compactPrompt(window)
            LiteRtPromptStyle.MINIMAL -> minimalPrompt(window)
        }

    private fun fullPrompt(window: EdgeReasoningWindow): String {
        val features =
            window.features.entries
                .sortedBy { it.key }
                .joinToString(",") { (key, value) -> "$key=${"%.2f".format(value)}" }
        return """
            You are Koru EDGE, a non-safety driving coach.
            Return only JSON: {"speak":true,"action":"${window.actionHint.name}","priority":${window.priority},"text":"under 14 words","confidence":0.0}
            Never output priority 0. P0 safety is handled by HOT.
            Explain root cause over symptom when useful.
            Trigger=${window.triggerId}; cause=${window.causeHint ?: "none"}; phase=${window.phase.name}; corner=${window.cornerName ?: "none"}; skill=${window.skillLevel.name}; features={$features}
            Suggested=${window.suggestedText}
        """.trimIndent()
    }

    private fun compactPrompt(window: EdgeReasoningWindow): String {
        val speed = window.features["speedMps"]?.let { "%.0f".format(it) } ?: "?"
        val decel = window.features["longitudinalAccel"]?.let { "%.1f".format(it) } ?: "?"
        return """
            Koru EDGE coach. JSON only: {"speak":true,"action":"${window.actionHint.name}","priority":${window.priority},"text":"<=14 words","confidence":0.0}
            trigger=${window.triggerId}; phase=${window.phase.name}; corner=${window.cornerName ?: "none"}; speed=$speed; decel=$decel
            hint=${window.suggestedText}
        """.trimIndent()
    }

    private fun minimalPrompt(window: EdgeReasoningWindow): String =
        """
        JSON: {"speak":true,"action":"${window.actionHint.name}","priority":${window.priority},"text":"<=14 words","confidence":0.0}
        ${window.triggerId}; ${window.phase.name}; ${window.suggestedText}
        """.trimIndent()

    fun sampleWindow(): EdgeReasoningWindow =
        EdgeReasoningWindow(
            triggerId = "exit_hesitation",
            actionHint = CoachAction.THROTTLE,
            priority = 2,
            suggestedText = "Commit on exit throttle.",
            phase = CornerPhase.EXIT,
            skillLevel = SkillLevel.INTERMEDIATE,
            cornerName = "T4",
            features =
                mapOf(
                    "speedMps" to 18.4,
                    "longitudinalAccel" to -0.8,
                    "lateralAccel" to 0.6,
                ),
            causeHint = "late_throttle",
        )
}
