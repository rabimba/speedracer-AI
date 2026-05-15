package com.trustableai.koru.service

import com.trustableai.koru.model.ObdTransportPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdLinkElm327ClientTest {
    @Test
    fun `auto transport falls back from unavailable USB to Bluetooth and polls supported pids`() = runBlocking {
        val usb = FakeElmTransport(ObdTransportKind.USB, failOpen = true)
        val bluetooth = FakeElmTransport(ObdTransportKind.BLUETOOTH)
        val client = ObdLinkElm327Client(
            transportPreference = ObdTransportPreference.AUTO,
            transportFactory = { listOf(usb, bluetooth) },
            elapsedRealtimeMs = { 10_000L + bluetooth.commands.size },
        )

        try {
            client.start()
            repeat(50) {
                if (client.latestSample()?.speedMph != null) return@repeat
                delay(50L)
            }

            val sample = client.latestSample()
            assertTrue(usb.openAttempted)
            assertTrue(bluetooth.commands.containsAll(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100")))
            assertTrue(
                "commands=${bluetooth.commands} status=${client.status()} sample=${client.latestSample()}",
                bluetooth.commands.contains("010C"),
            )
            assertTrue(bluetooth.commands.contains("010D"))
            assertEquals(1726, sample?.rpm)
            assertEquals(62.14, sample?.speedMph ?: -1.0, 0.01)
            assertEquals(false, sample?.channelUpdatedAtElapsedMs?.containsKey(ObdPid.ENGINE_OIL_TEMP))
        } finally {
            client.stop()
        }
    }

    private class FakeElmTransport(
        override val kind: ObdTransportKind,
        private val failOpen: Boolean = false,
    ) : Elm327Transport {
        val commands = mutableListOf<String>()
        var openAttempted = false
        private var open = false

        override suspend fun open() {
            openAttempted = true
            if (failOpen) throw Elm327TransportUnavailable("fake ${kind.bridgeValue} unavailable")
            open = true
        }

        override suspend fun close() {
            open = false
        }

        override suspend fun send(command: String, timeoutMs: Long): String {
            check(open) { "transport not open" }
            val normalized = command.trim().uppercase()
            commands += normalized
            return when (normalized) {
                "ATZ" -> "ELM327 v1.5\r>"
                "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0" -> "OK\r>"
                "0100" -> "41 00 FF FF FF FF\r>"
                "0120" -> "41 20 00 00 00 00\r>"
                "010C" -> "41 0C 1A F8\r>"
                "010D" -> "41 0D 64\r>"
                "0111" -> "41 11 80\r>"
                "0105" -> "41 05 5A\r>"
                else -> "NO DATA\r>"
            }
        }
    }
}
