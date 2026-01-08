package app.gamenative.ui.component.dialog.state

import androidx.compose.runtime.saveable.mapSaver
import app.gamenative.data.LibraryItem

data class GameManagerDialogState(
    val visible: Boolean,
) {
    companion object {
        val Saver = mapSaver(
            save = { state ->
                mapOf(
                    "visible" to state.visible,
                )
            },
            restore = { savedMap ->
                GameManagerDialogState(
                    visible = savedMap["visible"] as Boolean,
                )
            },
        )
    }
}
