package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.OnScreenControlsVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProfileVisibilityTest {
    @Test
    fun savedHiddenChoiceWinsOverAutomaticShowingAtStartupAndAfterDeviceChanges() {
        assertFalse(shouldShowInputControls(OnScreenControlsVisibility.HIDDEN, true))
        assertFalse(shouldShowInputControls(OnScreenControlsVisibility.HIDDEN, false))
    }

    @Test
    fun savedShownChoiceWinsOverAutomaticHidingForConnectedDevicesOrTouchscreenMode() {
        assertTrue(shouldShowInputControls(OnScreenControlsVisibility.SHOWN, false))
    }

    @Test
    fun automaticGamesContinueToFollowDeviceAndInputModeDecisions() {
        assertTrue(shouldShowInputControls(OnScreenControlsVisibility.AUTOMATIC, true))
        assertFalse(shouldShowInputControls(OnScreenControlsVisibility.AUTOMATIC, false))
    }

    @Test
    fun editorTemporarilyShowsHiddenControlsThenRestoresTheSavedChoice() {
        assertTrue(shouldShowInputControls(OnScreenControlsVisibility.HIDDEN, true, isEditing = true))
        assertFalse(shouldShowInputControls(OnScreenControlsVisibility.HIDDEN, true, isEditing = false))
    }

    @Test
    fun profileLeavingTouchscreenModeCannotOverrideASavedHiddenChoice() {
        val automaticVisibility = shouldShowControlsAfterProfileApply(false, true, false, true, false)
        assertTrue(automaticVisibility)
        assertFalse(shouldShowInputControls(OnScreenControlsVisibility.HIDDEN, automaticVisibility))
    }

    @Test
    fun leavingTouchscreenModeRestoresButtonsWithoutAnotherInputDevice() {
        assertTrue(shouldShowControlsAfterProfileApply(false, true, false, false, false))
    }

    @Test
    fun leavingTouchscreenModeDoesNotForceButtonsOverAnotherInputDevice() {
        assertFalse(shouldShowControlsAfterProfileApply(false, true, false, false, true))
    }

    @Test
    fun unrelatedProfileApplicationPreservesManuallyHiddenButtons() {
        assertFalse(shouldShowControlsAfterProfileApply(false, false, false, false, false))
    }

    @Test
    fun allOtherVisibilityCombinationsKeepThePreviousBehavior() {
        for (bits in 0 until 32) {
            val visible = bits and 1 != 0
            val wasTouchscreen = bits and 2 != 0
            val touchscreen = bits and 4 != 0
            val shooter = bits and 8 != 0
            val otherInput = bits and 16 != 0
            val restoringAfterTouchscreen = wasTouchscreen && !touchscreen && !otherInput
            val previousBehavior = !touchscreen && (visible || shooter)
            assertEquals(
                "Visibility combination $bits",
                previousBehavior || restoringAfterTouchscreen,
                shouldShowControlsAfterProfileApply(visible, wasTouchscreen, touchscreen, shooter, otherInput),
            )
        }
    }
}
