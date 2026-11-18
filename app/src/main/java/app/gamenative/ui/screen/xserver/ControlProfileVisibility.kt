package app.gamenative.ui.screen.xserver

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
