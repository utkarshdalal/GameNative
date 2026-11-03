package com.winlator.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Test

class OnScreenControlsVisibilityTest {
    @Test
    fun persistedValuesRoundTrip() {
        for (visibility in OnScreenControlsVisibility.values()) {
            assertEquals(visibility, OnScreenControlsVisibility.fromValue(visibility.value))
        }
    }

    @Test
    fun absentAndUnrecognizedValuesUseAutomatic() {
        for (value in listOf(null, "", "true", "HIDDEN", "future-option")) {
            assertEquals(OnScreenControlsVisibility.AUTOMATIC, OnScreenControlsVisibility.fromValue(value))
        }
    }
}
