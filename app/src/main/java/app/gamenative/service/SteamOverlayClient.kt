package app.gamenative.service

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Talks to the bionic Steam host process (libsteambootstrap) over its abstract-namespace
 * command socket.
 *
 * Only meaningful while a game is running in bionic-Steam mode: the host owns the logged-in
 * Valve client, and these calls are the app's only route to the friends list, to sending a
 * game invite, and to making the engine deliver a join to the running game.
 */
object SteamOverlayClient {

    private const val SOCKET_NAME = "gamenative-steam-overlay"
    private const val TIMEOUT_MS = 4000

    data class Friend(
        val steamId: Long,
        val personaState: Int,
        val playingAppId: Int,
        /** See [REL_NONE] / [REL_IN_OUR_LOBBY] / [REL_JOINABLE]; decided by the host. */
        val relation: Int,
        val lobbyId: Long,
        val name: String,
    ) {
        /** They've turned up in our lobby -- the only join signal Steam gives the inviter. */
        val inOurLobby: Boolean get() = relation == REL_IN_OUR_LOBBY

        /** They're in this same game in their own lobby, so we can join them. */
        val isJoinable: Boolean get() = relation == REL_JOINABLE && lobbyId != 0L

        val isOnline: Boolean get() = personaState != PERSONA_OFFLINE
        fun isPlaying(appId: Int): Boolean = playingAppId != 0 && playingAppId == appId
    }

    data class SelfInfo(val steamId: Long, val connectString: String) {
        /** Games publish a join token only while they are in a joinable session. */
        val isJoinable: Boolean get() = connectString.isNotBlank()
    }

    const val PERSONA_OFFLINE = 0

    const val REL_NONE = 0
    const val REL_IN_OUR_LOBBY = 1
    const val REL_JOINABLE = 2

    /** Whether the host process is up and serving. Cheap enough to poll before showing UI. */
    suspend fun isAvailable(): Boolean = request("PING")?.firstOrNull() == "PONG"

    suspend fun self(): SelfInfo? {
        val line = request("SELF")?.firstOrNull() ?: return null
        if (!line.startsWith("S ")) return null
        // "S <steamid> <connect string, may contain spaces or be empty>"
        val rest = line.removePrefix("S ")
        val steamId = rest.substringBefore(' ').toLongOrNull() ?: return null
        val connect = rest.substringAfter(' ', "").trim()
        return SelfInfo(steamId, connect)
    }

    suspend fun listFriends(): List<Friend> {
        val lines = request("LIST") ?: return emptyList()
        return lines.mapNotNull { parseFriend(it) }
    }

    /**
     * Sends a Steam game invite. Passing a blank [connectString] tells the host to reuse
     * whatever join token the running game published, which is what a lobby invite normally
     * carries.
     */
    suspend fun invite(friendSteamId: Long, connectString: String = ""): Boolean {
        val cmd = if (connectString.isBlank()) {
            "INVITE $friendSteamId"
        } else {
            "INVITE $friendSteamId ${connectString.replace('\n', ' ')}"
        }
        return request(cmd)?.firstOrNull() == "OK"
    }

    /**
     * Makes the Steam client post GameLobbyJoinRequested_t to the running game, which is how
     * the desktop overlay hands off an accepted invite.
     */
    suspend fun acceptInvite(lobbyId: Long, fromSteamId: Long): Boolean =
        request("ACCEPT $lobbyId $fromSteamId")?.firstOrNull() == "OK"

    /**
     * The connect-string equivalent of [acceptInvite], for games that join by rich presence
     * rather than by lobby id.
     */
    suspend fun acceptRichPresenceJoin(fromSteamId: Long, connectString: String): Boolean =
        request("RPJOIN $fromSteamId ${connectString.replace('\n', ' ')}")?.firstOrNull() == "OK"

    data class OverlayRequest(val dialog: String, val lobbyId: Long) {
        /** The engine names the dialog it wants; an invite request carries "lobbyinvite". */
        val isInviteRequest: Boolean get() = dialog.contains("invite", ignoreCase = true)
    }

    /**
     * Consumes a pending overlay request raised by the game itself -- its in-game "Invite
     * friends" button. Returns null when the game hasn't asked for anything.
     */
    suspend fun pollOverlayRequest(): OverlayRequest? {
        val line = request("POLL")?.firstOrNull() ?: return null
        if (!line.startsWith("A ")) return null
        val parts = line.removePrefix("A ").trim().split(' ')
        val dialog = parts.getOrNull(0)?.takeIf { it != "-" } ?: return null
        return OverlayRequest(dialog, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private fun parseFriend(line: String): Friend? {
        // "F <steamid> <personaState> <playingAppId> <rel> <lobbyId> <name...>"
        if (!line.startsWith("F ")) return null
        val parts = line.removePrefix("F ").split(' ', limit = 6)
        if (parts.size < 6) return null
        return Friend(
            steamId = parts[0].toLongOrNull() ?: return null,
            personaState = parts[1].toIntOrNull() ?: return null,
            playingAppId = parts[2].toIntOrNull() ?: 0,
            relation = parts[3].toIntOrNull() ?: REL_NONE,
            lobbyId = parts[4].toLongOrNull() ?: 0L,
            name = parts[5],
        )
    }

    /**
     * Runs one command and collects the reply. Multi-record replies are terminated by a lone
     * "."; single-record replies are one line. Returns null if the host is unreachable or
     * answered with an error.
     */
    private suspend fun request(command: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = TIMEOUT_MS

                socket.outputStream.write("$command\n".toByteArray())
                socket.outputStream.flush()

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val out = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line == "." -> return@withContext out
                        line.startsWith("ERR") -> {
                            Timber.w("SteamOverlayClient: '$command' -> $line")
                            return@withContext null
                        }
                        else -> {
                            out.add(line)
                            // Single-record replies have no terminator; don't block on more.
                            if (out.size == 1 && !command.startsWith("LIST")) {
                                return@withContext out
                            }
                        }
                    }
                }
                out
            }
        } catch (e: Exception) {
            Timber.d("SteamOverlayClient: '$command' failed: ${e.message}")
            null
        }
    }
}
