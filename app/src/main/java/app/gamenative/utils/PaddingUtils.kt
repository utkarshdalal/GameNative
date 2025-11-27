package app.gamenative.utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager

object `PaddingUtils` {
    /**
     * Creates padding values with conditional top padding based on status bar visibility.
     * When hideStatusBar is true, top padding is 0.dp, otherwise it uses defaultPadding.
     * All other sides use defaultPadding.
     *
     * @param defaultPadding The default padding to use for start, end, bottom, and top (when status bar is visible)
     * @return PaddingValues with the appropriate padding values
     */
    fun statusBarAwarePadding(
        defaultPadding: Dp = 16.dp
    ): PaddingValues {

        val hideStatusBar = PrefManager.hideStatusBarWhenNotInGame

        return PaddingValues(
            top = if (hideStatusBar) 0.dp else defaultPadding,
            start = defaultPadding,
            end = defaultPadding,
            bottom = defaultPadding
        )
    }
}
