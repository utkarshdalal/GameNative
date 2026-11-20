package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.OnScreenControlsVisibility

/** Resolve the starting choice without changing the saved per-game default. */
internal fun shouldShowInputControls(
    visibility: OnScreenControlsVisibility,
    automaticVisibility: Boolean,
    isEditing: Boolean = false,
): Boolean = isEditing || when (visibility) {
    OnScreenControlsVisibility.AUTOMATIC -> automaticVisibility
    OnScreenControlsVisibility.SHOW -> true
    OnScreenControlsVisibility.HIDE -> false
}

/** Lives with the game screen, so an overlay toggle never becomes a container setting. */
internal class InputControlsVisibilitySession(private val defaultVisibility: OnScreenControlsVisibility) {
    private var manualVisibility: Boolean? = null
    private var inputDeviceIds: Set<Int>? = null

    fun onInputDevicesChanged(connectedIds: Set<Int>) {
        if (defaultVisibility != OnScreenControlsVisibility.AUTOMATIC) return
        // Initial/repeated scans establish or retain the baseline. Only a real device change
        // hands visibility back to automatic detection, including changes while suspended.
        if (inputDeviceIds != null && inputDeviceIds != connectedIds) manualVisibility = null
        inputDeviceIds = connectedIds.toSet()
    }

    fun toggle(currentlyVisible: Boolean) {
        manualVisibility = !currentlyVisible
    }

    fun shouldShow(automaticVisibility: Boolean, isEditing: Boolean = false): Boolean {
        if (isEditing) return true
        return manualVisibility ?: shouldShowInputControls(defaultVisibility, automaticVisibility)
    }
}

/** Restore buttons when a profile leaves touchscreen mode, without undoing a manual hide. */
internal fun shouldShowControlsAfterProfileApply(
    wereControlsVisible: Boolean,
    wasTouchscreenMode: Boolean,
    isTouchscreenMode: Boolean,
    isShooterMode: Boolean,
    hasOtherInputDevice: Boolean,
): Boolean = !isTouchscreenMode && (
    wereControlsVisible || isShooterMode || (wasTouchscreenMode && !hasOtherInputDevice)
)
