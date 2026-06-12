package com.trustableai.koru.service

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.trustableai.koru.model.AimCanBitrate
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.LiveSessionConfig
import com.trustableai.koru.runtime.TrackCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoruTelemetryServiceSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun stopService() {
        context.startService(KoruTelemetryService.stopIntent(context))
    }

    @Test
    fun canInterfaceCheckStartsServiceWithoutCameraLocationOrBluetooth() = runBlocking {
        KoruSessionBus.resetLiveState()
        val config = LiveSessionConfig(
            coachId = "superaj",
            audioEnabled = false,
            trackName = TrackCatalog.sonomaRaceway.name,
            sessionMode = SessionMode.CAN_INTERFACE_CHECK,
            telemetrySource = TelemetrySourceKind.AIM_CAN_USB,
            obdTransportPreference = ObdTransportPreference.AUTO,
            aimCanBitrate = AimCanBitrate.S8_1MBPS,
            cameraFusionEnabled = false,
            sessionGoals = emptyList(),
            sourceUrl = null,
        )

        ContextCompat.startForegroundService(context, KoruTelemetryService.startIntent(context, config.toJson()))

        val status = waitForStatus()
        assertNotEquals(LiveBackendState.IDLE, status.state)
        assertTrue(status.detail.contains("CAN", ignoreCase = true) || status.detail.contains("AiM", ignoreCase = true))
        val frame = waitForFrame()
        assertEquals(TelemetrySourceKind.AIM_CAN_USB, frame?.telemetrySource)
        assertEquals("S8 1 Mbps", frame?.sourceHealth?.canBitrate)
    }

    private suspend fun waitForStatus(): com.trustableai.koru.model.LiveBackendStatus {
        repeat(30) {
            val status = KoruSessionBus.status.value
            if (status.state != LiveBackendState.IDLE) return status
            kotlinx.coroutines.delay(200L)
        }
        return KoruSessionBus.status.value
    }

    private suspend fun waitForFrame(): com.trustableai.koru.model.TelemetryFrame? {
        repeat(40) {
            KoruSessionBus.latestTelemetry.value?.let { return it }
            kotlinx.coroutines.delay(200L)
        }
        return KoruSessionBus.latestTelemetry.value
    }
}
