package com.winlator.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlElementLookThroughTest {
    @Test
    fun `new buttons explicitly disable look-through by default`() {
        val element = ControlElement(null)

        assertEquals(false, element.lookThroughSetting)
        assertFalse(element.isLookThrough)
        assertFalse(element.isShooterLookThrough)
    }

    @Test
    fun `reset restores the explicit look-through default`() {
        val element = ControlElement(null)
        element.lookThroughSetting = true

        element.setType(ControlElement.Type.BUTTON)

        assertEquals(false, element.lookThroughSetting)
        assertFalse(element.isLookThrough)
        assertFalse(element.isShooterLookThrough)
    }

    @Test
    fun `legacy profiles remain shooter-only by default`() {
        val element = ControlElement(null)
        element.lookThroughSetting = null

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
