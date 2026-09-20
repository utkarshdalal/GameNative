package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A stable full-screen viewport, including when opened over the immersive game window. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ControlSettingsDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            // Otherwise Android measures a floating, wrap-content window on touch.
            // In landscape that can temporarily enlarge the scroll viewport and clamp
            // its offset, snapping back up even when the finger has not moved.
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                .imePadding(),
        ) {
            content()
        }
    }
}
