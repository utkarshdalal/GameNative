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
    private var deviceVisibilityUpdatePending = false

    fun onInputDevicesChanged(connectedIds: Set<Int>) {
        if (defaultVisibility != OnScreenControlsVisibility.AUTOMATIC) return
        if (inputDeviceIds == connectedIds) return
        // The first scan establishes the baseline without clearing a manual choice.
        if (inputDeviceIds != null) manualVisibility = null
        inputDeviceIds = connectedIds.toSet()
        deviceVisibilityUpdatePending = true
    }

    /** Keep device changes pending while the quick menu prevents applying visibility. */
    fun consumeDeviceVisibilityUpdate(onlyIfDevicesChanged: Boolean): Boolean {
        val shouldUpdate = !onlyIfDevicesChanged || deviceVisibilityUpdatePending
        deviceVisibilityUpdatePending = false
        return shouldUpdate
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
