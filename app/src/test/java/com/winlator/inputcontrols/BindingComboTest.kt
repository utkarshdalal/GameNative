package com.winlator.inputcontrols

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BindingComboTest {
    @Test
    fun `combo normalizes modifiers before primary binding`() {
        val combo = BindingCombo.fromBindings(listOf(Binding.KEY_1, Binding.KEY_CTRL_L))

        assertEquals(listOf(Binding.KEY_CTRL_L, Binding.KEY_1), combo.bindings)
        assertEquals(Binding.KEY_1, combo.primaryBinding)
        assertEquals("L CTRL + 1", combo.toString())
    }

    @Test
    fun `combo reads nested binding arrays`() {
        val combo = BindingCombo.fromJsonValue(JSONArray(listOf("KEY_SHIFT_L", "MOUSE_RIGHT_BUTTON")))

        assertEquals(listOf(Binding.KEY_SHIFT_L, Binding.MOUSE_RIGHT_BUTTON), combo.bindings)
    }

    @Test
    fun `simultaneous mode is the persisted mode name`() {
        assertEquals("simultaneous", BindingCombo.Mode.SIMULTANEOUS.jsonName)
    }

    @Test
    fun `unknown mode loads as simultaneous`() {
        val json = JSONObject()
            .put("mode", "unknown")
            .put("bindings", JSONArray(listOf("KEY_CTRL_L", "KEY_1")))

        val combo = BindingCombo.fromJsonValue(json)

        assertEquals(BindingCombo.Mode.SIMULTANEOUS, combo.mode)
        assertEquals(listOf(Binding.KEY_CTRL_L, Binding.KEY_1), combo.bindings)
    }

    @Test
    fun `sequence preserves selected order`() {
        val combo = BindingCombo.fromBindings(
            listOf(Binding.KEY_E, Binding.MOUSE_LEFT_BUTTON),
            BindingCombo.Mode.SEQUENCE,
            220,
        )

        assertEquals(BindingCombo.Mode.SEQUENCE, combo.mode)
        assertEquals(220, combo.sequenceDelayMs)
        assertEquals(listOf(Binding.KEY_E, Binding.MOUSE_LEFT_BUTTON), combo.bindings)
        assertEquals(Binding.MOUSE_LEFT_BUTTON, combo.primaryBinding)
        assertEquals("E -> LEFT BUTTON", combo.toString())
    }

    @Test
    fun `sequence round trips through json object`() {
        val expected = BindingCombo.fromBindings(
            listOf(Binding.KEY_E, Binding.MOUSE_LEFT_BUTTON),
            BindingCombo.Mode.SEQUENCE,
            220,
        )

        val actual = BindingCombo.fromJsonValue(expected.toJsonValue() as JSONObject)

        assertEquals(BindingCombo.Mode.SEQUENCE, actual.mode)
        assertEquals(220, actual.sequenceDelayMs)
        assertEquals(expected.bindings, actual.bindings)
    }

    @Test
    fun `equivalent combos compare equal`() {
        val first = BindingCombo.fromBindings(listOf(Binding.KEY_1, Binding.KEY_CTRL_L))
        val second = BindingCombo.fromBindings(listOf(Binding.KEY_CTRL_L, Binding.KEY_1))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `simultaneous combo ignores sequence delay and round trips equally`() {
        val expected = BindingCombo.fromBindings(
            listOf(Binding.KEY_CTRL_L, Binding.KEY_1),
            BindingCombo.Mode.SIMULTANEOUS,
            800,
        )

        val actual = BindingCombo.fromJsonValue(expected.toJsonValue())

        assertEquals(BindingCombo.DEFAULT_SEQUENCE_DELAY_MS, expected.sequenceDelayMs)
        assertEquals(expected, actual)
    }

    @Test
    fun `legacy embedded sequence fields still load`() {
        val json = JSONObject()
            .put("bindings", JSONArray(listOf("KEY_E", "MOUSE_LEFT_BUTTON")))
            .put("bindingMode", "sequence")
            .put("bindingDelayMs", 240)

        val combo = BindingCombo.fromJsonValue(json)

        assertEquals(BindingCombo.Mode.SEQUENCE, combo.mode)
        assertEquals(240, combo.sequenceDelayMs)
    }

    @Test
    fun `legacy binding object restores single binding`() {
        val combo = BindingCombo.fromJsonValue(JSONObject().put("binding", "KEY_E"))

        assertEquals(BindingCombo.of(Binding.KEY_E), combo)
    }
}
