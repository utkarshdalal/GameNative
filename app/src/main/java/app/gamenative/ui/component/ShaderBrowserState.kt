package app.gamenative.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Browser navigation state that survives browser close/reopen (user request 2026-08-11):
 * after activating a shader and returning to the QuickMenu, reopening the browser restores
 * the SAME level where the shader was chosen — family/subfolder screen, search query,
 * pagination page and the last focused row. Held by [ShaderSectionState], which outlives
 * the browser surface within a game session (the QuickMenu stays composed while hidden).
 */
class ShaderBrowserState {
    val nav = ShaderBrowserNav()
    var query by mutableStateOf("")
    val pages = mutableStateMapOf<String, Int>()
    val focusIndices = mutableStateMapOf<String, Int>()
}
