package app.gamenative.steamcontroller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Per-session Steam Controller UI state. Owned by XServerScreen as ONE remembered local: that composable sits at
 * the dex verifier's 255-register limit, so the mapper handle and the editor visibility flags live here instead of
 * as separate locals in that method.
 */
class ScSessionState {
    var mapper: TritonMapper? by mutableStateOf(null)
    var showRoot by mutableStateOf(false)
    var showBindings by mutableStateOf(false)
    var showLayout by mutableStateOf(false)
    var showKeyboard by mutableStateOf(false)
    var showConfigs by mutableStateOf(false)
    /** A sub-editor was opened from the root hub, so closing it returns to the hub instead of the game. */
    var returnToRoot by mutableStateOf(false)

    val isLive: Boolean get() = mapper?.transportReady == true
}
