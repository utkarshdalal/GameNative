package com.winlator.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlElementLookThroughTest {
    @Test
    fun `new buttons leave general look-through off but allow shooter drag look`() {
        val element = ControlElement(null)

        assertEquals(false, element.lookThroughSetting)
        assertFalse(element.isLookThrough)
        assertTrue(element.isShooterLookThrough)
    }

    @Test
    fun `reset restores independent look-through defaults`() {
        val element = ControlElement(null)
        element.lookThroughSetting = true

        element.setType(ControlElement.Type.BUTTON)

        assertEquals(false, element.lookThroughSetting)
        assertFalse(element.isLookThrough)
        assertTrue(element.isShooterLookThrough)
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
    fun `explicitly disabling general look-through does not disable shooter drag look`() {
        val element = ControlElement(null)
        element.lookThroughSetting = false

        assertFalse(element.isLookThrough)
        assertTrue(element.isShooterLookThrough)
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

    @Test
    fun `explicit general and shooter opt-outs remain disabled together`() {
        val element = ControlElement(null)
        element.lookThroughSetting = false
        element.setShooterLookThrough(false)

        assertFalse(element.isLookThrough)
        assertFalse(element.isShooterLookThrough)
    }

    @Test
    fun `radial menu anywhere in combo disables look-through`() {
        val element = ControlElement(null).apply {
            setType(ControlElement.Type.BUTTON)
            lookThroughSetting = true
            setBindingComboAt(
                0,
                BindingCombo.fromBindings(listOf(Binding.OPEN_RADIAL_MENU, Binding.KEY_E)),
            )
        }

        assertFalse(element.isLookThrough)
        assertFalse(element.isShooterLookThrough)
    }
}
