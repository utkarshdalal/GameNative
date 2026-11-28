package com.winlator.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Test

class OnScreenControlsVisibilityTest {
    @Test
    fun persistedValuesRoundTrip() {
        val storedValues = mapOf(
            OnScreenControlsVisibility.AUTOMATIC to "automatic",
            OnScreenControlsVisibility.SHOW to "show",
            OnScreenControlsVisibility.HIDE to "hide",
        )
        for ((visibility, value) in storedValues) {
            assertEquals(value, visibility.value)
            assertEquals(visibility, OnScreenControlsVisibility.fromValue(value))
        }
    }

    @Test
    fun absentAndUnrecognizedValuesUseAutomatic() {
        for (value in listOf(null, "", "shown", "hidden", "future-option")) {
            assertEquals(OnScreenControlsVisibility.AUTOMATIC, OnScreenControlsVisibility.fromValue(value))
        }
    }
}
