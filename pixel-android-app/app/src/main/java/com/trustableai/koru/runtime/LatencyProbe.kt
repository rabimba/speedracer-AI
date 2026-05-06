package com.trustableai.koru.runtime

class LatencyProbe private constructor(
    private val startedAtNanos: Long,
) {
    fun elapsedMs(nowNanos: Long = System.nanoTime()): Long {
        return ((nowNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    companion object {
        const val HOT_PATH_BUDGET_MS = 50L
        const val EDGE_REASONER_BUDGET_MS = 30L
        const val P0_AUDIO_DISPATCH_BUDGET_MS = 100L

        fun start(): LatencyProbe = LatencyProbe(System.nanoTime())
    }
}
