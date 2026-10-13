package app.gamenative.service

import app.gamenative.ui.util.FriendPlayingNotification
import app.gamenative.ui.util.FriendPlayingNotificationManager
import timber.log.Timber

/**
 * Polls the Steam overlay host for friends who start playing the current app.
 * First successful sample seeds the set (no spam for people already in-game).
 */
object FriendPlayingWatcher {
    @Volatile
    private var appId: Int = 0

    @Volatile
    private var seeded: Boolean = false

    private val knownPlaying = mutableSetOf<Long>()

    fun bind(steamAppId: Int) {
        if (steamAppId <= 0) {
            clear()
            return
        }
        if (appId != steamAppId) {
            appId = steamAppId
            seeded = false
            knownPlaying.clear()
            Timber.d("FriendPlayingWatcher: bound appId=%s", steamAppId)
        }
    }

    fun clear() {
        appId = 0
        seeded = false
        knownPlaying.clear()
    }

    suspend fun tick() {
        val currentApp = appId
        if (currentApp <= 0) return
        if (!SteamOverlayClient.isAvailable()) return

        val friends = SteamOverlayClient.listFriends()
        if (friends.isEmpty()) return

        val playingNow = friends.filter { it.isPlaying(currentApp) }
        val playingIds = playingNow.map { it.steamId }.toSet()

        if (!seeded) {
            knownPlaying.clear()
            knownPlaying.addAll(playingIds)
            seeded = true
            return
        }

        val newlyPlaying = playingNow.filter { it.steamId !in knownPlaying }
        newlyPlaying.forEach { friend ->
            Timber.i(
                "FriendPlayingWatcher: %s started app %s (joinable=%s)",
                friend.name,
                currentApp,
                friend.isJoinable,
            )
            FriendPlayingNotificationManager.show(
                FriendPlayingNotification(
                    friendSteamId = friend.steamId,
                    friendName = friend.name,
                    appId = currentApp,
                    joinable = friend.isJoinable,
                    lobbyId = friend.lobbyId,
                ),
            )
        }

        knownPlaying.clear()
        knownPlaying.addAll(playingIds)
    }
}
