package app.gamenative.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.service.SteamOverlayClient
import com.winlator.container.Container

/**
 * Backing state for the QuickMenu invite tab.
 *
 * Deliberately constructed inside QuickMenu (see the BfgMenuState precedent) rather than plumbed
 * in from XServerScreen, which sits at the dex verifier's register limit.
 *
 * The host process already knows which app it is serving, so nothing here needs an app id.
 */
class SteamInviteState private constructor() {

    var friends by mutableStateOf<List<SteamOverlayClient.Friend>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    /** Null until an invite is attempted; then the id of the friend and whether it went through. */
    var lastInviteFailed by mutableStateOf(false)
        private set

    var hostUnavailable by mutableStateOf(false)
        private set

    suspend fun refresh() {
        isLoading = true
        val loaded = SteamOverlayClient.listFriends()
        // Online friends first, then alphabetical; offline friends can't be invited usefully.
        friends = loaded.sortedWith(compareByDescending<SteamOverlayClient.Friend> { it.isOnline }.thenBy { it.name.lowercase() })
        hostUnavailable = loaded.isEmpty() && !SteamOverlayClient.isAvailable()
        isLoading = false
    }

    suspend fun invite(steamId: Long) {
        lastInviteFailed = !SteamOverlayClient.invite(steamId)
    }

    companion object {
        fun createIfAvailable(container: Container?): SteamInviteState? =
            if (container != null && container.isLaunchBionicSteam) SteamInviteState() else null
    }
}
