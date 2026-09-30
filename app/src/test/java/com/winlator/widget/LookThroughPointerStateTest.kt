package com.winlator.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookThroughPointerStateTest {
    @Test
    fun `drag starts only after touch slop and reports incremental movement`() {
        val state = LookThroughPointerState()

        assertTrue(state.tryStart(3, 10f, 10f, false))
        assertNull(state.move(3, 15f, 10f, 8f))

        val first = state.move(3, 19f, 10f, 8f)!!
        assertEquals(9f, first.x, 0f)
        assertEquals(0f, first.y, 0f)

        val second = state.move(3, 22f, 14f, 8f)!!
        assertEquals(3f, second.x, 0f)
        assertEquals(4f, second.y, 0f)
    }

    @Test
    fun `normal touch ownership prevents look-through from interrupting a gesture`() {
        val state = LookThroughPointerState()

        assertFalse(state.tryStart(4, 0f, 0f, true))
        assertFalse(state.isActive)
    }

    @Test
    fun `active pointer keeps ownership until release`() {
        val state = LookThroughPointerState()

        assertTrue(state.tryStart(1, 0f, 0f, false))
        assertFalse(state.tryStart(2, 10f, 10f, false))
        state.release(1)
        assertFalse(state.isActive)
        assertTrue(state.tryStart(2, 10f, 10f, false))
        assertTrue(state.owns(2))
    }

    @Test
    fun `cancel clears the owned pointer`() {
        val state = LookThroughPointerState()
        state.tryStart(5, 0f, 0f, false)

        state.clear()

        assertFalse(state.isActive)
        assertFalse(state.owns(5))
    }
}
