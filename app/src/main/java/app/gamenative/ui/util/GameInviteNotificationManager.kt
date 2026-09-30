package app.gamenative.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GameInvite(
    val fromSteamId: Long,
    val connectString: String,
) {
    /** Lobby-based games carry the lobby id in the connect string; others use a raw address. */
    val lobbyId: Long?
        get() = LOBBY_REGEX.find(connectString)?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        val LOBBY_REGEX = Regex("""\+connect_lobby\s+(\d+)""")
    }
}

/**
 * Holds the most recent unanswered game invite. Unlike an achievement toast this sticks around
 * until the user accepts or dismisses it, so it is state rather than a one-shot channel.
 */
object GameInviteNotificationManager {

    private val _pending = MutableStateFlow<GameInvite?>(null)
    val pending: StateFlow<GameInvite?> = _pending.asStateFlow()

    fun show(fromSteamId: Long, connectString: String) {
        _pending.value = GameInvite(fromSteamId, connectString)
    }

    fun clear() {
        _pending.value = null
    }
}
