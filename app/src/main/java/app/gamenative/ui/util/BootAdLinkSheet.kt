package app.gamenative.ui.util

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether a boot-ad CTA's in-app link sheet is open. While true, the booting splash
 * defers its hide so the sheet (hosted inside the splash) survives the game window
 * appearing; the game keeps booting underneath.
 */
object BootAdLinkSheet {
    val open = MutableStateFlow(false)
}
