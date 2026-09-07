package com.winlator.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlElementMouseSpeedTest {
    @Test
    fun `default trackpad speed preserves existing rounding`() {
        val accumulator = ControlElement.MouseDeltaAccumulator()

        assertEquals(1, accumulator.scale(0.2f, 1f))
        assertEquals(-1, accumulator.scale(-0.2f, 1f))
    }

    @Test
    fun `sub one trackpad speed accumulates fractional pixels`() {
        val accumulator = ControlElement.MouseDeltaAccumulator()

        assertEquals(
            listOf(0, 1, 0, 1),
            List(4) { accumulator.scale(0.2f, 0.5f) },
        )
    }

    @Test
    fun `reset discards a partial trackpad pixel`() {
        val accumulator = ControlElement.MouseDeltaAccumulator()

        assertEquals(0, accumulator.scale(0.2f, 0.5f))
        accumulator.reset()
        assertEquals(0, accumulator.scale(0.2f, 0.5f))
    }
}
