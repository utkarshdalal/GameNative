package com.winlator.widget

import app.gamenative.data.GyroSettings
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock

class GyroBindingInspectorTest {
    @Test
    fun onScreenBinding_isUsableForHold() {
        val profile = mock<ControlsProfile>()
        val element = mock<ControlElement>()
        `when`(element.bindingCount).thenReturn(1)
        `when`(element.getBindingComboAt(0)).thenReturn(BindingCombo.of(Binding.GYRO_MODIFIER))
        `when`(profile.elements).thenReturn(listOf(element))
        `when`(profile.controllers).thenReturn(arrayListOf())

        assertTrue(GyroBindingInspector.hasUsableBinding(profile, GyroSettings.ACTIVATION_HOLD))
        assertTrue(GyroBindingInspector.hasModifierBinding(profile))
    }

    @Test
    fun physicalControllerBinding_isDetected() {
        val profile = mock<ControlsProfile>()
        val controller = mock<ExternalController>()
        val externalBinding = ExternalControllerBinding().apply {
            bindingCombo = BindingCombo.fromBindings(
                listOf(Binding.KEY_SHIFT_L, Binding.GYRO_MODIFIER),
            )
        }
        `when`(profile.elements).thenReturn(emptyList())
        `when`(profile.controllers).thenReturn(arrayListOf(controller))
        `when`(controller.controllerBindings).thenReturn(arrayListOf(externalBinding))

        assertTrue(GyroBindingInspector.hasUsableBinding(profile, GyroSettings.ACTIVATION_HOLD))
    }

    @Test
    fun profileWithoutModifierBinding_isDetected() {
        val profile = mock<ControlsProfile>()
        val element = mock<ControlElement>()
        `when`(element.bindingCount).thenReturn(1)
        `when`(element.getBindingComboAt(0)).thenReturn(BindingCombo.of(Binding.GAMEPAD_BUTTON_A))
        `when`(profile.elements).thenReturn(listOf(element))
        `when`(profile.controllers).thenReturn(arrayListOf())

        assertFalse(GyroBindingInspector.hasModifierBinding(profile))
    }
}
