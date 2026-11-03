package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.OnScreenControlsVisibility

/** An explicit game preference wins over automatic device/mode changes; editing is temporary. */
internal fun shouldShowInputControls(
    visibility: OnScreenControlsVisibility,
    automaticVisibility: Boolean,
    isEditing: Boolean = false,
): Boolean = isEditing || when (visibility) {
    OnScreenControlsVisibility.AUTOMATIC -> automaticVisibility
    OnScreenControlsVisibility.SHOWN -> true
    OnScreenControlsVisibility.HIDDEN -> false
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
