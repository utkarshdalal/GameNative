package com.winlator.inputcontrols

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RadialMenuTest {
    @Test
    fun `legacy single binding still loads`() {
        val slot = RadialMenu.Slot.fromJSONObject(
            JSONObject()
                .put("label", "Inventory")
                .put("binding", "KEY_I"),
        )

        assertEquals("Inventory", slot.label)
        assertEquals(BindingCombo.of(Binding.KEY_I), slot.bindingCombo)
    }

    @Test
    fun `simultaneous radial binding round trips`() {
        val expected = BindingCombo.fromBindings(listOf(Binding.KEY_CTRL_L, Binding.KEY_1))
        val slot = RadialMenu.Slot("Action", expected)

        val json = slot.toJSONObject()
        val restored = RadialMenu.Slot.fromJSONObject(json)

        assertEquals(Binding.KEY_1.name, json.getString("binding"))
        assertEquals(expected, restored.bindingCombo)
    }

    @Test
    fun `radial sequence preserves order and delay`() {
        val expected = BindingCombo.fromBindings(
            listOf(Binding.KEY_E, Binding.MOUSE_LEFT_BUTTON),
            BindingCombo.Mode.SEQUENCE,
            240,
        )
        val slot = RadialMenu.Slot("Interact", expected)

        val json = slot.toJSONObject()
        val restored = RadialMenu.Slot.fromJSONObject(json)

        assertEquals(JSONArray(listOf("KEY_E", "MOUSE_LEFT_BUTTON")).toString(), json.getJSONArray("bindings").toString())
        assertEquals("sequence", json.getString("mode"))
        assertEquals(240, json.getInt("sequenceDelayMs"))
        assertEquals(expected, restored.bindingCombo)
    }

    @Test
    fun `legacy radial sequence fields still load`() {
        val slot = RadialMenu.Slot.fromJSONObject(
            JSONObject()
                .put("label", "Interact")
                .put("binding", "MOUSE_LEFT_BUTTON")
                .put("bindings", JSONArray(listOf("KEY_E", "MOUSE_LEFT_BUTTON")))
                .put("bindingMode", "sequence")
                .put("bindingDelayMs", 240),
        )

        assertEquals(BindingCombo.Mode.SEQUENCE, slot.bindingCombo.mode)
        assertEquals(240, slot.bindingCombo.sequenceDelayMs)
    }
}
