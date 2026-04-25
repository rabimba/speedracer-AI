package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.CornerPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingGateTest {
    @Test
    fun `blackout blocks non safety messages`() {
        val gate = TimingGate()
        gate.update(CornerPhase.MID_CORNER, 1000L)
        assertFalse(gate.canDeliver(2))
        assertTrue(gate.canDeliver(0))
    }

    @Test
    fun `cooldown reopens after delivery window`() {
        val gate = TimingGate()
        gate.startDelivery(0L)
        gate.update(CornerPhase.STRAIGHT, 5000L)
        assertTrue(gate.canDeliver(2))
    }
}
