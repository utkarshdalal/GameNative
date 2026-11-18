package com.winlator.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlElementMouseSpeedTest {
    @Test
    fun `default trackpad speed accumulates transformed subpixels`() {
        val positive = ControlElement.MouseDeltaAccumulator()
        val negative = ControlElement.MouseDeltaAccumulator()

        assertEquals(listOf(0, 0, 0, 0, 1), List(5) { positive.scale(0.2f, 1f) })
        assertEquals(listOf(0, 0, 0, 0, -1), List(5) { negative.scale(-0.2f, 1f) })
    }

    @Test
    fun `sub one trackpad speed remains proportional`() {
        val accumulator = ControlElement.MouseDeltaAccumulator()

        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 1),
            List(8) { accumulator.scale(0.25f, 0.5f) },
        )
    }

    @Test
    fun `above one trackpad speed remains proportional`() {
        val accumulator = ControlElement.MouseDeltaAccumulator()

        assertEquals(
            listOf(0, 1, 1, 1),
            List(4) { accumulator.scale(0.25f, 3f) },
        )
    }

    @Test
    fun `direction reversal cancels the pending subpixel`() {
        val accumulator = ControlElement.MouseDeltaAccumulator()

        assertEquals(0, accumulator.scale(0.75f, 1f))
        assertEquals(0, accumulator.scale(-0.75f, 1f))
        assertEquals(0, accumulator.scale(-0.5f, 1f))
        assertEquals(-1, accumulator.scale(-0.5f, 1f))
    }

    @Test
    fun `reset discards a pending subpixel`() {
        val accumulator = ControlElement.MouseDeltaAccumulator()

        assertEquals(0, accumulator.scale(0.75f, 1f))
        accumulator.reset()
        assertEquals(0, accumulator.scale(0.5f, 1f))
        assertEquals(1, accumulator.scale(0.5f, 1f))
    }
}
