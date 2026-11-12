package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.OnScreenControlsVisibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputControlsVisibilitySessionTest {
    @Test
    fun automaticFollowsDeviceAndModeDecisionsUntilTheUserToggles() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        assertTrue(session.shouldShow(true))
        assertFalse(session.shouldShow(false))
        session.toggle(currentlyVisible = false)
        assertTrue(session.shouldShow(false))
        session.toggle(currentlyVisible = true)
        assertFalse(session.shouldShow(true))
    }

    @Test
    fun manualHideSurvivesResumeScansAndRelaunchUsesTheSavedShownDefault() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN)
        session.toggle(currentlyVisible = true)
        repeat(3) {
            session.onControllersChanged(emptySet())
            assertFalse(session.shouldShow(true))
        }
        assertTrue(InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN).shouldShow(true))
    }

    @Test
    fun manualShowOverridesHiddenForThisSessionAndRelaunchIsHiddenAgain() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.HIDDEN)
        session.toggle(currentlyVisible = false)
        session.onControllersChanged(emptySet())
        assertTrue(session.shouldShow(false))
        assertFalse(InputControlsVisibilitySession(OnScreenControlsVisibility.HIDDEN).shouldShow(true))
    }

    @Test
    fun automaticDetectionResumesOnTheNextLaunch() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        session.toggle(currentlyVisible = true)
        assertFalse(session.shouldShow(true))
        val nextLaunch = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        assertTrue(nextLaunch.shouldShow(true))
        assertFalse(nextLaunch.shouldShow(false))
    }

    @Test
    fun controllerConnectionTemporarilyHidesShownAndDisconnectRestoresIt() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN)
        session.onControllersChanged(setOf(1))
        assertFalse(session.shouldShow(false))
        session.onControllersChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun controllerDisconnectDoesNotUndoAManualHide() {
        for (default in OnScreenControlsVisibility.values()) {
            val session = InputControlsVisibilitySession(default)
            session.toggle(currentlyVisible = true)
            session.onControllersChanged(setOf(1))
            assertFalse(session.shouldShow(false))
            session.onControllersChanged(emptySet())
            assertFalse(session.shouldShow(true))
        }
    }

    @Test
    fun temporarilyHiddenControlsRestoreAManualShowEvenWithHiddenDefault() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.HIDDEN)
        session.toggle(currentlyVisible = false)
        session.onControllersChanged(setOf(1))
        assertFalse(session.shouldShow(false))
        session.onControllersChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun manuallyShowingWithAConnectedControllerSurvivesRepeatedScansAndModeChanges() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        session.onControllersChanged(setOf(1))
        session.toggle(currentlyVisible = false)
        repeat(3) {
            session.onControllersChanged(setOf(1))
            assertTrue(session.shouldShow(false))
        }
        session.onControllersChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun anotherControllerConnectionHidesAnExplicitShowAgain() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN)
        session.onControllersChanged(setOf(1))
        session.toggle(currentlyVisible = false)
        session.onControllersChanged(setOf(1, 2))
        assertFalse(session.shouldShow(false))
        session.onControllersChanged(setOf(2))
        assertFalse(session.shouldShow(false))
        session.onControllersChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun aReplacementControllerIsAConnectionChangeEvenWhenTheCountIsUnchanged() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN)
        session.onControllersChanged(setOf(1))
        session.toggle(currentlyVisible = false)
        session.onControllersChanged(setOf(2))
        assertFalse(session.shouldShow(false))
    }

    @Test
    fun disconnectingOneOfTwoControllersDoesNotCancelAnExplicitShow() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN)
        session.onControllersChanged(setOf(1, 2))
        session.toggle(currentlyVisible = false)
        session.onControllersChanged(setOf(2))
        assertTrue(session.shouldShow(false))
        session.onControllersChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun togglingAHardwareHiddenOverlayUsesItsActualVisibility() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN)
        session.onControllersChanged(setOf(1))
        assertFalse(session.shouldShow(false))
        session.toggle(currentlyVisible = false)
        assertTrue(session.shouldShow(false))
        session.toggle(currentlyVisible = true)
        session.onControllersChanged(emptySet())
        assertFalse(session.shouldShow(true))
    }

    @Test
    fun editorVisibilityIsTemporaryAndDeviceChangesDuringEditingAreAppliedOnExit() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN)
        session.onControllersChanged(setOf(1))
        assertTrue(session.shouldShow(false, isEditing = true))
        assertFalse(session.shouldShow(false))
        session.onControllersChanged(emptySet())
        assertTrue(session.shouldShow(true))

        session.toggle(currentlyVisible = true)
        assertTrue(session.shouldShow(true, isEditing = true))
        assertFalse(session.shouldShow(true))
    }

    @Test
    fun sessionChoicesAreIndependentBetweenGames() {
        val firstGame = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        val secondGame = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOWN)
        firstGame.toggle(currentlyVisible = true)
        firstGame.onControllersChanged(setOf(1))
        firstGame.onControllersChanged(emptySet())
        assertFalse(firstGame.shouldShow(true))
        assertTrue(secondGame.shouldShow(true))
    }
}
