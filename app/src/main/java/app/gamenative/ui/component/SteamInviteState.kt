package app.gamenative.ui.component

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.R
import app.gamenative.SteamBootstrap
import app.gamenative.service.SteamOverlayClient
import com.winlator.container.Container
import timber.log.Timber

/**
 * Backing state for the QuickMenu invite tab.
 *
 * Deliberately constructed inside QuickMenu (see the BfgMenuState precedent) rather than plumbed
 * in from XServerScreen, which sits at the dex verifier's register limit.
 *
 * The host process already knows which app it is serving, so nothing here needs an app id.
 */
class SteamInviteState private constructor() {

    /** Uptime when this state was created — i.e. when the game session opened. */
    private val createdAt = SystemClock.uptimeMillis()

    /** Last invite request consumed (dedupe key), for hosts that never clear their POLL. */
    private var lastConsumedRequest: String? = null
    private var lastConsumedAt: Long = 0L

    var friends by mutableStateOf<List<SteamOverlayClient.Friend>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    /** String resource for whatever last went wrong, or null. Invite and join fail differently. */
    var lastError by mutableStateOf<Int?>(null)
        private set

    var hostUnavailable by mutableStateOf(false)
        private set

    /**
     * Friends we've sent an invite to. This only records that the invite went out -- Steam tells
     * the inviter nothing about it being accepted or declined, so a row stops saying "sent" only
     * when the friend actually shows up in our lobby (or the menu is reopened).
     */
    var inviteSent by mutableStateOf(emptySet<Long>())
        private set

    suspend fun refresh() {
        isLoading = true
        val loaded = SteamOverlayClient.listFriends()
        // Friends you can act on first: joinable, then already-in-your-game, then online.
        friends = loaded.sortedWith(
            compareByDescending<SteamOverlayClient.Friend> { it.isJoinable }
                .thenByDescending { it.inOurLobby }
                .thenByDescending { it.isOnline }
                .thenBy { it.name.lowercase() },
        )
        hostUnavailable = loaded.isEmpty() && !SteamOverlayClient.isAvailable()
        isLoading = false
    }

    suspend fun invite(steamId: Long) {
        val ok = SteamOverlayClient.invite(steamId)
        lastError = if (ok) null else R.string.steam_invite_failed
        if (ok) inviteSent = inviteSent + steamId
    }

    /**
     * Joins a friend already playing this game. Goes through the same call as accepting an
     * invite -- the running game just gets told to join their lobby.
     */
    suspend fun join(friend: SteamOverlayClient.Friend) {
        val ok = SteamOverlayClient.acceptInvite(friend.lobbyId, friend.steamId)
        Timber.i("SteamInviteState: join ${friend.name} lobby=${friend.lobbyId} -> $ok")
        lastError = if (ok) null else R.string.steam_join_failed
    }

    /** Refreshes without the loading flicker, for the poll that runs while the tab is open. */
    suspend fun refreshQuietly() {
        val loaded = SteamOverlayClient.listFriends()
        if (loaded.isEmpty()) return

        // Keep the existing order rather than re-sorting: rows moving under a controller
        // cursor mid-navigation is worse than a joined friend staying where they were.
        val byId = loaded.associateBy { it.steamId }
        friends = friends.mapNotNull { byId[it.steamId] }

        // Once they're actually in, the "sent" note has served its purpose.
        inviteSent = inviteSent - loaded.filter { it.inOurLobby }.map { it.steamId }.toSet()
    }

    /**
     * True when the game has asked for its invite dialog -- i.e. the player pressed the game's
     * own "Invite friends" button. Consumes the request and pre-loads the friend list so the tab
     * is populated by the time it opens.
     *
     * Guards (spec 2026-08-11 quickmenu-invite-regression): a request must not pop the menu
     * right at game start ([SESSION_GRACE_MS] — a stale request from a previous session would
     * otherwise auto-open the menu and skip the pause the moment the game launches), and the
     * same request is consumed only once per [REQUEST_DEDUPE_WINDOW_MS] so a host that never
     * clears its POLL cannot make the menu auto-reopen forever.
     */
    suspend fun consumeGameInviteRequest(): Boolean {
        if (SteamBootstrap.getProcessStatus() !is SteamBootstrap.ProcessStatus.Ready) return false
        if (SystemClock.uptimeMillis() - createdAt < SESSION_GRACE_MS) return false

        val request = SteamOverlayClient.pollOverlayRequest() ?: return false
        if (!request.isInviteRequest) {
            Timber.d("SteamInviteState: ignoring overlay request '${request.dialog}'")
            return false
        }

        val key = "${request.dialog}|${request.lobbyId}"
        val now = SystemClock.uptimeMillis()
        if (key == lastConsumedRequest && now - lastConsumedAt < REQUEST_DEDUPE_WINDOW_MS) return false
        lastConsumedRequest = key
        lastConsumedAt = now

        Timber.i("SteamInviteState: game requested ${request.dialog} lobby=${request.lobbyId}")
        lastError = null
        inviteSent = emptySet()
        refresh()
        return true
    }

    companion object {
        /**
         * Set while the menu is open because the game asked for it, so the host screen can skip
         * suspending the game. An invite dialog must not pause: the game has to keep running to
         * receive the peer that's joining. Stores the uptime of the auto-open so a stale flag
         * (from a previous open) can never leave the game unpaused — the host checks
         * [OPEN_REQUEST_MAX_AGE_MS].
         */
        const val OPEN_REQUEST_MAX_AGE_MS = 30_000L

        /** Invite requests inside this window after the game session opens are ignored. */
        const val SESSION_GRACE_MS = 20_000L

        /** The same invite request is consumed at most once inside this window. */
        const val REQUEST_DEDUPE_WINDOW_MS = 60_000L

        @Volatile
        var openedForGameRequest: Long = 0L
        fun createIfAvailable(container: Container?): SteamInviteState? =
            if (container != null && container.isLaunchBionicSteam) SteamInviteState() else null
    }
}
