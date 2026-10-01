package app.gamenative.ui.component

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Read both live scroll states at publication time, not an asynchronously reported Boolean. */
@Composable
internal fun LibraryScrollTracking(
    grid: ScrollableState,
    carousel: ScrollableState,
    onScrollingChanged: (() -> Boolean) -> Unit,
) {
    val report by rememberUpdatedState(onScrollingChanged)
    DisposableEffect(grid, carousel) {
        report { grid.isScrollInProgress || carousel.isScrollInProgress }
        onDispose { report { false } }
    }
}
