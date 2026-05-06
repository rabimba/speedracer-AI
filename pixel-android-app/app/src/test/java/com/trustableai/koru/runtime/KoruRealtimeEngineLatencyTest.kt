package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ReasonerDecision
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.runtime.reasoner.OnDeviceReasoner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

class KoruRealtimeEngineLatencyTest {
    @Test
    fun `hot path p95 stays below five milliseconds in replay`() = runBlocking {
        val engine = KoruRealtimeEngine(
            track = TrackCatalog.sonomaRaceway,
            phraseCatalog = FakePhraseRenderer,
            reasonerProvider = { NullReasoner },
        )
        val latenciesMs = LongArray(1_000)

        repeat(latenciesMs.size) { index ->
            val frame = fastTurnElevenEntryFrame(timeSeconds = index / 10.0)
            latenciesMs[index] =
                measureNanoTime {
                    engine.processFrame(frame, nowMs = 10_000L + index * 100L)
                } / 1_000_000L
        }
        engine.close()

        latenciesMs.sort()
        val p95 = latenciesMs[(latenciesMs.size * 0.95).toInt()]
        assertTrue("HOT path p95 ${p95}ms exceeded 5ms", p95 <= 5L)
    }

    private fun fastTurnElevenEntryFrame(timeSeconds: Double): TelemetryFrame {
        return TelemetryFrame(
            timeSeconds = timeSeconds,
            latitude = 38.16120,
            longitude = -122.45330,
            speedMph = 96.0,
            throttle = 0.0,
            brake = 0.0,
            gLat = 0.0,
            gLong = 0.0,
            sourceMode = SessionMode.TELEMETRY,
        )
    }

    private object FakePhraseRenderer : PhraseRenderer {
        override fun phraseIdFor(action: CoachAction, skillLevel: SkillLevel, coachId: String): String {
            return "test/${action.name}"
        }

        override fun render(action: CoachAction, skillLevel: SkillLevel, coachId: String): String {
            return action.name.lowercase()
        }
    }

    private object NullReasoner : OnDeviceReasoner {
        override val backend: RuntimeBackend = RuntimeBackend.DETERMINISTIC

        override suspend fun warmup(): LiveBackendStatus {
            return LiveBackendStatus(
                backend = backend,
                state = LiveBackendState.READY,
                detail = "test",
                usesOnDeviceModel = false,
                supportedPaths = listOf(CoachingPath.EDGE),
            )
        }

        override suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision? = null
    }
}
