package app.gamenative.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.service.SteamOverlayClient
import com.winlator.container.Container
import timber.log.Timber

/**
 * App-wide controller for the in-game Steam friends overlay (invite / join).
 * Keeps state off [app.gamenative.ui.screen.xserver.XServerScreen] (register limit).
 */
object SteamFriendsOverlayController {
    var visible by mutableStateOf(false)
        private set

    /** True when opened because the game asked for its invite dialog. */
    var openedForGameRequest by mutableStateOf(false)
        private set

    var inviteState by mutableStateOf<SteamInviteState?>(null)
        private set

    private var boundContainerId: String? = null

    fun bind(container: Container?) {
        val next = SteamInviteState.createIfAvailable(container)
        if (next == null) {
            boundContainerId = null
            inviteState = null
            if (visible) hide()
            return
        }
        if (inviteState == null || boundContainerId != container?.id) {
            boundContainerId = container?.id
            inviteState = next
        }
    }

    fun isSupported(container: Container?): Boolean =
        SteamInviteState.createIfAvailable(container) != null

    fun show(forGameRequest: Boolean = false) {
        if (inviteState == null) return
        openedForGameRequest = forGameRequest
        SteamInviteState.openedForGameRequest = forGameRequest
        visible = true
        Timber.i("SteamFriendsOverlay: show (gameRequest=%s)", forGameRequest)
    }

    fun hide() {
        visible = false
        openedForGameRequest = false
        SteamInviteState.openedForGameRequest = false
    }

    /**
     * Poll the Steam host for an in-game "Invite friends" / overlay request.
     * @return true if the overlay was opened
     */
    suspend fun pollAndShowIfGameRequested(): Boolean {
        val state = inviteState ?: return false
        if (visible) return false
        if (!state.consumeGameInviteRequest()) return false
        show(forGameRequest = true)
        return true
    }

    suspend fun hostReady(): Boolean = SteamOverlayClient.isAvailable()
}
