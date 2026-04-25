package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.CornerPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachingQueueTest {
    @Test
    fun `higher priority decision dequeues first`() {
        val queue = CoachingQueue()
        val gate = TimingGate()
        queue.enqueue(
            CoachingQueue.QueuedDecision(null, "hot", "slow", 3, CornerPhase.STRAIGHT, 100L),
            100L,
        )
        queue.enqueue(
            CoachingQueue.QueuedDecision(null, "hot", "fast", 1, CornerPhase.STRAIGHT, 200L),
            200L,
        )

        val result = queue.dequeue(300L, gate)
        assertEquals("fast", result?.text)
    }
}
