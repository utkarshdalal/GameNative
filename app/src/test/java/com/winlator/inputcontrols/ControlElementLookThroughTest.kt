package com.winlator.inputcontrols

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlElementLookThroughTest {
    @Test
    fun `legacy profiles remain shooter-only by default`() {
        val element = ControlElement(null)

        assertNull(element.lookThroughSetting)
        assertFalse(element.isLookThrough)
        assertTrue(element.isShooterLookThrough)
    }

    @Test
    fun `explicitly disabling general look-through disables both modes`() {
        val element = ControlElement(null)
        element.lookThroughSetting = false

        assertFalse(element.isLookThrough)
        assertFalse(element.isShooterLookThrough)
    }

    @Test
    fun `general look-through also applies in shooter mode`() {
        val element = ControlElement(null)
        element.setShooterLookThrough(false)
        element.lookThroughSetting = true

        assertTrue(element.isLookThrough)
        assertTrue(element.isShooterLookThrough)
    }

    @Test
    fun `legacy shooter opt-out remains disabled`() {
        val element = ControlElement(null)
        element.setShooterLookThrough(false)

        assertFalse(element.isLookThrough)
        assertFalse(element.isShooterLookThrough)
    }
}
