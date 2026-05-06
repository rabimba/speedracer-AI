package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ReasonerDecision
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.runtime.reasoner.OnDeviceReasoner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class EdgeReasonerQueueTest {
    @Test
    fun `submit does not block while reasoner works`() = runBlocking {
        val queue = EdgeReasonerQueue(
            reasonerProvider = { DelayedReasoner(delayMs = 100L, result = edgeResult()) },
            resultTimeoutMs = 500L,
            staleResultTtlMs = 1500L,
        )

        val elapsedMs = measureTimeMillis {
            queue.submit(window(), CornerPhase.EXIT, nowMs = 1_000L)
        }
        queue.close()

        assertTrue(elapsedMs < 20L)
    }

    @Test
    fun `timed out edge result is dropped`() = runBlocking {
        val queue = EdgeReasonerQueue(
            reasonerProvider = { DelayedReasoner(delayMs = 100L, result = edgeResult()) },
            resultTimeoutMs = 25L,
            staleResultTtlMs = 1500L,
        )

        queue.submit(window(), CornerPhase.EXIT, nowMs = 1_000L)
        delay(80L)

        assertTrue(queue.drainReady(nowMs = 1_080L).isEmpty())
        queue.close()
    }

    @Test
    fun `fresh edge result drains as queued decision`() = runBlocking {
        val queue = EdgeReasonerQueue(
            reasonerProvider = { DelayedReasoner(delayMs = 10L, result = edgeResult()) },
            resultTimeoutMs = 500L,
            staleResultTtlMs = 1500L,
        )

        queue.submit(window(), CornerPhase.EXIT, nowMs = 1_000L)
        delay(40L)
        val drained = queue.drainReady(nowMs = 1_040L)
        queue.close()

        assertEquals(1, drained.size)
        assertEquals(CoachAction.HUSTLE, drained.single().action)
        assertEquals(RuntimeBackend.LITERTLM, drained.single().backend)
    }

    @Test
    fun `expired blocking reasoner does not pin single flight slot`() = runBlocking {
        val blockingReasoner = BusyReasoner(blockMs = 250L, result = edgeResult("Late blocked output."))
        val freshReasoner = DelayedReasoner(delayMs = 10L, result = edgeResult("Fresh EDGE output."))
        var reasonerCalls = 0
        val queue = EdgeReasonerQueue(
            reasonerProvider = {
                if (reasonerCalls++ == 0) blockingReasoner else freshReasoner
            },
            resultTimeoutMs = 25L,
            staleResultTtlMs = 1500L,
        )

        queue.submit(window(), CornerPhase.EXIT, nowMs = 1_000L)
        delay(60L)
        queue.submit(window(), CornerPhase.EXIT, nowMs = 1_060L)
        delay(80L)
        val drained = queue.drainReady(nowMs = 1_140L)
        queue.close()

        assertTrue(blockingReasoner.closeCalled)
        assertEquals(1, drained.size)
        assertEquals("Fresh EDGE output.", drained.single().text)
    }

    private fun window(): EdgeReasoningWindow {
        return EdgeReasoningWindow(
            triggerId = "exit_hesitation",
            actionHint = CoachAction.HUSTLE,
            priority = 2,
            suggestedText = "Exit hesitation. Commit harder on throttle.",
            phase = CornerPhase.EXIT,
            skillLevel = SkillLevel.BEGINNER,
            cornerName = "Turn 11",
            features = mapOf("speed_mph" to 50.0),
        )
    }

    private fun edgeResult(text: String = "Commit to throttle at exit."): ReasonerDecision {
        return ReasonerDecision(
            speak = true,
            action = CoachAction.HUSTLE,
            priority = 2,
            phraseId = "test/hustle",
            confidence = 0.8,
            text = text,
        )
    }

    private class DelayedReasoner(
        private val delayMs: Long,
        private val result: ReasonerDecision,
    ) : OnDeviceReasoner {
        override val backend: RuntimeBackend = RuntimeBackend.LITERTLM

        override suspend fun warmup(): LiveBackendStatus {
            return LiveBackendStatus(
                backend = backend,
                state = LiveBackendState.READY,
                detail = "test",
                usesOnDeviceModel = true,
                supportedPaths = listOf(CoachingPath.EDGE),
            )
        }

        override suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision {
            delay(delayMs)
            return result
        }
    }

    private class BusyReasoner(
        private val blockMs: Long,
        private val result: ReasonerDecision,
    ) : OnDeviceReasoner {
        override val backend: RuntimeBackend = RuntimeBackend.LITERTLM
        @Volatile var closeCalled = false

        override suspend fun warmup(): LiveBackendStatus {
            return LiveBackendStatus(
                backend = backend,
                state = LiveBackendState.READY,
                detail = "test",
                usesOnDeviceModel = true,
                supportedPaths = listOf(CoachingPath.EDGE),
            )
        }

        override suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision {
            val deadline = System.nanoTime() + blockMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                // Deliberately ignore coroutine cancellation to model a native blocking call.
            }
            return result
        }

        override suspend fun close() {
            closeCalled = true
        }
    }
}
