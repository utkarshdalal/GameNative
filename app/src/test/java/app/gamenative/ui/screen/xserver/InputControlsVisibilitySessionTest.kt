package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.OnScreenControlsVisibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputControlsVisibilitySessionTest {
    @Test
    fun automaticStartsFromDeviceDetectionAndReturnsToItOnANewLaunch() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        session.onInputDevicesChanged(emptySet())
        assertTrue(session.shouldShow(true))
        session.toggle(currentlyVisible = true)
        assertFalse(session.shouldShow(true))

        val nextLaunch = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        nextLaunch.onInputDevicesChanged(setOf(1))
        assertFalse(nextLaunch.shouldShow(false))
        nextLaunch.onInputDevicesChanged(emptySet())
        assertTrue(nextLaunch.shouldShow(true))
    }

    @Test
    fun showStartsVisibleWithOrWithoutAController() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOW)
        session.onInputDevicesChanged(setOf(1))
        assertTrue(session.shouldShow(false))
        session.onInputDevicesChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun hideStartsHiddenWithOrWithoutAController() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.HIDE)
        session.onInputDevicesChanged(setOf(1))
        assertFalse(session.shouldShow(false))
        session.onInputDevicesChanged(emptySet())
        assertFalse(session.shouldShow(true))
    }

    @Test
    fun automaticPreservesAManualHideOnResumeButStillFollowsConnectionAndDisconnection() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        session.onInputDevicesChanged(emptySet())
        session.toggle(currentlyVisible = true)
        repeat(3) {
            session.onInputDevicesChanged(emptySet())
            assertFalse(session.shouldShow(true))
        }
        session.onInputDevicesChanged(setOf(1))
        assertFalse(session.shouldShow(false))
        session.onInputDevicesChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun automaticPreservesAManualShowOnResumeButANewDeviceResumesDetection() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        session.onInputDevicesChanged(setOf(1))
        session.toggle(currentlyVisible = false)
        repeat(3) {
            session.onInputDevicesChanged(setOf(1))
            assertTrue(session.shouldShow(false))
        }
        // Same device count, but a replacement controller or another physical input device.
        session.onInputDevicesChanged(setOf(2))
        assertFalse(session.shouldShow(false))
    }

    @Test
    fun automaticDisconnectCanOverrideAManualHideEvenWhenTheChangeHappenedWhileSuspended() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        session.onInputDevicesChanged(setOf(1))
        session.toggle(currentlyVisible = false)
        session.toggle(currentlyVisible = true)
        assertFalse(session.shouldShow(false))
        session.onInputDevicesChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun showKeepsAManualHideAcrossDeviceChangesAndRelaunchRestoresShow() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOW)
        session.onInputDevicesChanged(setOf(1))
        session.toggle(currentlyVisible = true)
        for (ids in listOf(emptySet(), setOf(2), setOf(2, 3), emptySet())) {
            session.onInputDevicesChanged(ids)
            assertFalse(session.shouldShow(ids.isEmpty()))
        }
        val nextLaunch = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOW)
        nextLaunch.onInputDevicesChanged(setOf(1))
        assertTrue(nextLaunch.shouldShow(false))
    }

    @Test
    fun hideKeepsAManualShowAcrossDeviceChangesAndRelaunchRestoresHide() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.HIDE)
        session.onInputDevicesChanged(emptySet())
        session.toggle(currentlyVisible = false)
        for (ids in listOf(setOf(1), setOf(1, 2), setOf(2), emptySet())) {
            session.onInputDevicesChanged(ids)
            assertTrue(session.shouldShow(ids.isEmpty()))
        }
        val nextLaunch = InputControlsVisibilitySession(OnScreenControlsVisibility.HIDE)
        nextLaunch.onInputDevicesChanged(emptySet())
        assertFalse(nextLaunch.shouldShow(true))
    }

    @Test
    fun editorVisibilityIsTemporaryAndDoesNotReplaceTheSessionChoice() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOW)
        session.toggle(currentlyVisible = true)
        session.onInputDevicesChanged(setOf(1))
        assertTrue(session.shouldShow(false, isEditing = true))
        assertFalse(session.shouldShow(false))
        session.onInputDevicesChanged(emptySet())
        assertFalse(session.shouldShow(true))
    }

    @Test
    fun automaticStillObservesRealDeviceChangesWhileEditing() {
        val session = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        session.onInputDevicesChanged(emptySet())
        session.toggle(currentlyVisible = true)
        session.onInputDevicesChanged(setOf(1))
        assertTrue(session.shouldShow(false, isEditing = true))
        assertFalse(session.shouldShow(false))
        session.onInputDevicesChanged(emptySet())
        assertTrue(session.shouldShow(true))
    }

    @Test
    fun sessionChoicesAreIndependentBetweenGames() {
        val firstGame = InputControlsVisibilitySession(OnScreenControlsVisibility.AUTOMATIC)
        val secondGame = InputControlsVisibilitySession(OnScreenControlsVisibility.SHOW)
        firstGame.onInputDevicesChanged(emptySet())
        firstGame.toggle(currentlyVisible = true)
        secondGame.onInputDevicesChanged(setOf(1))
        assertFalse(firstGame.shouldShow(true))
        assertTrue(secondGame.shouldShow(false))
    }
}
