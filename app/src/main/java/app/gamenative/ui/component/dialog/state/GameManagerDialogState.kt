package app.gamenative.ui.component.dialog.state

import androidx.compose.runtime.saveable.mapSaver
import app.gamenative.data.LibraryItem

data class GameManagerDialogState(
    val visible: Boolean,
    val branch: String? = null,
) {
    companion object {
        val Saver = mapSaver(
            save = { state ->
                mapOf(
                    "visible" to state.visible,
                    "branch" to state.branch,
                )
            },
            restore = { savedMap ->
                GameManagerDialogState(
                    visible = savedMap["visible"] as Boolean,
                    branch = savedMap["branch"] as? String,
                )
            },
        )
    }
}
