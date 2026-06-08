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
import com.trustableai.koru.model.TelemetrySourceHealth
import com.trustableai.koru.runtime.reasoner.OnDeviceReasoner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class KoruRealtimeEngineSafetyTest {
    @Test
    fun `p0 brake bypass survives hanging edge reasoner`() = runBlocking {
        val engine = KoruRealtimeEngine(
            track = TrackCatalog.sonomaRaceway,
            phraseCatalog = FakePhraseRenderer,
            reasonerProvider = { HangingReasoner },
        )
        val frame = TelemetryFrame(
            timeSeconds = 12.0,
            latitude = 38.16272,
            longitude = -122.45500,
            speedMph = 95.0,
            throttle = 0.0,
            brake = 0.0,
            gLat = 0.0,
            gLong = 0.0,
            sourceMode = SessionMode.TELEMETRY,
        )

        var decisions = emptyList<com.trustableai.koru.model.CoachingDecision>()
        val elapsedMs = measureTimeMillis {
            decisions = engine.processFrame(frame, nowMs = 10_000L)
        }
        engine.close()

        assertTrue("engine should not wait for the hanging edge reasoner", elapsedMs < 200L)
        val decision = decisions.firstOrNull()
        assertNotNull(decision)
        assertEquals(CoachAction.BRAKE, decision?.action)
        assertEquals(0, decision?.priority)
        assertEquals("Brake now", decision?.text)
        assertTrue((decision?.latencyMs ?: Long.MAX_VALUE) < LatencyProbe.HOT_PATH_BUDGET_MS)
    }

    @Test
    fun `no live data stage produces no coaching decisions`() = runBlocking {
        val engine = KoruRealtimeEngine(
            track = TrackCatalog.sonomaRaceway,
            phraseCatalog = FakePhraseRenderer,
            reasonerProvider = { HangingReasoner },
        )
        val frame = TelemetryFrame(
            timeSeconds = 12.0,
            latitude = TrackCatalog.sonomaRaceway.centerLat,
            longitude = TrackCatalog.sonomaRaceway.centerLon,
            speedMph = 100.0,
            throttle = 100.0,
            brake = 0.0,
            gLat = 1.1,
            gLong = 0.4,
            sourceMode = SessionMode.TELEMETRY,
            sourceHealth = TelemetrySourceHealth(
                status = "No real telemetry source available",
                motionConnected = false,
                motionFixGood = false,
                fallbackStage = "no_live_data",
                degradedReason = "racebox_and_phone_motion_unavailable",
            ),
        )

        val decisions = engine.processFrame(frame, nowMs = 10_000L)
        engine.close()

        assertTrue(decisions.isEmpty())
    }

    @Test
    fun `p0 brake is not re-fired within cooldown for same corner`() = runBlocking {
        val engine = KoruRealtimeEngine(
            track = TrackCatalog.sonomaRaceway,
            phraseCatalog = FakePhraseRenderer,
            reasonerProvider = { HangingReasoner },
        )
        val frame = TelemetryFrame(
            timeSeconds = 12.0,
            latitude = 38.16272,
            longitude = -122.45500,
            speedMph = 95.0,
            throttle = 0.0,
            brake = 0.0,
            gLat = 0.0,
            gLong = 0.0,
            sourceMode = SessionMode.TELEMETRY,
        )

        val first = engine.processFrame(frame, nowMs = 10_000L)
        val second = engine.processFrame(frame, nowMs = 12_000L)
        val third = engine.processFrame(frame, nowMs = 15_000L)
        engine.close()

        assertEquals(CoachAction.BRAKE, first.singleOrNull()?.action)
        assertTrue(second.isEmpty())
        assertEquals(CoachAction.BRAKE, third.singleOrNull()?.action)
    }

    @Test
    fun `contextual brake is suppressed below speed gate`() = runBlocking {
        val engine = KoruRealtimeEngine(
            track = TrackCatalog.sonomaRaceway,
            phraseCatalog = FakePhraseRenderer,
            reasonerProvider = { HangingReasoner },
        )
        val frame = TelemetryFrame(
            timeSeconds = 12.0,
            latitude = 38.16272,
            longitude = -122.45500,
            speedMph = 20.0,
            throttle = 0.0,
            brake = 0.0,
            gLat = 0.0,
            gLong = 0.0,
            sourceMode = SessionMode.TELEMETRY,
        )

        val decisions = engine.processFrame(frame, nowMs = 10_000L)
        engine.close()

        assertTrue(decisions.none { it.action == CoachAction.BRAKE && it.priority == 0 })
    }

    private object FakePhraseRenderer : PhraseRenderer {
        override fun phraseIdFor(action: CoachAction, skillLevel: SkillLevel, coachId: String): String {
            return "test/${action.name}"
        }

        override fun render(action: CoachAction, skillLevel: SkillLevel, coachId: String): String {
            return action.name.lowercase()
        }
    }

    private object HangingReasoner : OnDeviceReasoner {
        override val backend: RuntimeBackend = RuntimeBackend.LITERTLM

        override suspend fun warmup(): LiveBackendStatus {
            return LiveBackendStatus(
                backend = backend,
                state = LiveBackendState.READY,
                detail = "test reasoner",
                usesOnDeviceModel = true,
                supportedPaths = listOf(CoachingPath.EDGE),
            )
        }

        override suspend fun reason(window: EdgeReasoningWindow): ReasonerDecision? {
            delay(5_000L)
            return null
        }
    }
}
