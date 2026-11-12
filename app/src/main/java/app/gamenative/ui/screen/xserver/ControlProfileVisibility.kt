package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.OnScreenControlsVisibility

/** Resolve the starting choice without changing the saved per-game default. */
internal fun shouldShowInputControls(
    visibility: OnScreenControlsVisibility,
    automaticVisibility: Boolean,
    isEditing: Boolean = false,
): Boolean = isEditing || when (visibility) {
    OnScreenControlsVisibility.AUTOMATIC -> automaticVisibility
    OnScreenControlsVisibility.SHOWN -> true
    OnScreenControlsVisibility.HIDDEN -> false
}

/** Lives with the game screen, so an overlay toggle never becomes a container setting. */
internal class InputControlsVisibilitySession(private val defaultVisibility: OnScreenControlsVisibility) {
    private var manualVisibility: Boolean? = null
    private var controllerIds: Set<Int> = emptySet()
    private var showWithController = false

    fun onControllersChanged(connectedIds: Set<Int>) {
        // A repeated scan (including resume) must preserve an explicit show with a controller.
        // Only a new controller starts automatic hiding again; disconnecting preserves the choice.
        if (connectedIds.any { it !in controllerIds }) showWithController = false
        controllerIds = connectedIds.toSet()
    }

    fun toggle(currentlyVisible: Boolean) {
        manualVisibility = !currentlyVisible
        showWithController = !currentlyVisible && controllerIds.isNotEmpty()
    }

    fun shouldShow(automaticVisibility: Boolean, isEditing: Boolean = false): Boolean {
        if (isEditing) return true
        if (controllerIds.isNotEmpty() && !showWithController) return false
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
