package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.bridgeValue
import com.trustableai.koru.runtime.deterministic.CoachingQueue
import com.trustableai.koru.runtime.reasoner.OnDeviceReasoner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class EdgeReasonerQueue(
    private val reasonerProvider: () -> OnDeviceReasoner,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val resultTimeoutMs: Long = DEFAULT_RESULT_TIMEOUT_MS,
    private val staleResultTtlMs: Long = DEFAULT_STALE_RESULT_TTL_MS,
) {
    private val lock = Any()
    private val ready = mutableListOf<ReadyDecision>()
    private var nextRequestId = 0L
    private var activeRequest: ActiveRequest? = null

    fun submit(window: EdgeReasoningWindow, phase: CornerPhase, nowMs: Long) {
        val requestId: Long
        synchronized(lock) {
            expireReadyLocked(nowMs)
            val active = activeRequest
            if (active != null && nowMs - active.startedAtMs <= resultTimeoutMs && active.job.isActive) {
                return
            }
            if (active != null) {
                active.job.cancel()
                scope.launch { runCatching { active.reasoner.close() } }
                activeRequest = null
            }
            requestId = ++nextRequestId
        }

        val reasoner = reasonerProvider()
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val result =
                    runCatching {
                        withTimeoutOrNull(resultTimeoutMs) {
                            reasoner.reason(window)
                        }
                    }.getOrNull()
                if (result?.speak == true) {
                    val completedAtMs = System.currentTimeMillis()
                    val decision =
                        CoachingQueue.QueuedDecision(
                            action = result.action,
                            path = CoachingPath.EDGE.bridgeValue(),
                            text = result.text,
                            priority = result.priority,
                            cornerPhase = phase,
                            timestampMs = completedAtMs,
                            confidence = result.confidence,
                            phraseId = result.phraseId,
                            backend = reasoner.backend,
                    )
                    synchronized(lock) {
                        if (activeRequest?.requestId == requestId) {
                            ready += ReadyDecision(decision, requestedAtMs = nowMs)
                        }
                    }
                }
                synchronized(lock) {
                    if (activeRequest?.requestId == requestId) {
                        activeRequest = null
                    }
                }
            }
        synchronized(lock) {
            activeRequest = ActiveRequest(requestId, nowMs, job, reasoner)
        }
        job.start()
    }

    fun drainReady(nowMs: Long): List<CoachingQueue.QueuedDecision> {
        synchronized(lock) {
            if (ready.isEmpty()) return emptyList()
            val fresh = ready.filter { nowMs - it.requestedAtMs <= staleResultTtlMs }
            ready.clear()
            return fresh.map { it.decision }
        }
    }

    fun close() {
        val active = synchronized(lock) {
            val active = activeRequest
            activeRequest = null
            ready.clear()
            active
        }
        active?.job?.cancel()
        active?.let { runBlocking { runCatching { it.reasoner.close() } } }
        scope.cancel()
    }

    private fun expireReady(nowMs: Long) {
        synchronized(lock) {
            expireReadyLocked(nowMs)
        }
    }

    private fun expireReadyLocked(nowMs: Long) {
        ready.removeAll { nowMs - it.requestedAtMs > staleResultTtlMs }
    }

    private data class ReadyDecision(
        val decision: CoachingQueue.QueuedDecision,
        val requestedAtMs: Long,
    )

    private data class ActiveRequest(
        val requestId: Long,
        val startedAtMs: Long,
        val job: Job,
        val reasoner: OnDeviceReasoner,
    )

    companion object {
        const val DEFAULT_RESULT_TIMEOUT_MS = 750L
        const val DEFAULT_STALE_RESULT_TTL_MS = 1500L
    }
}
