package app.gamenative.service

import app.gamenative.ui.util.FriendPlayingNotification
import app.gamenative.ui.util.FriendPlayingNotificationManager
import timber.log.Timber

/**
 * Polls the Steam overlay host for friends who start playing the current app.
 * First successful sample seeds the set (no spam for people already in-game).
 *
 * Skips work while the host is down so launch-time IO is not flooded with connect timeouts.
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
            SteamOverlayClient.invalidateAvailability()
            Timber.d("FriendPlayingWatcher: bound appId=%s", steamAppId)
        }
    }

    fun clear() {
        appId = 0
        seeded = false
        knownPlaying.clear()
        SteamOverlayClient.invalidateAvailability()
    }

    /**
     * @return suggested delay until the next poll (ms). Longer while the host is unavailable.
     */
    suspend fun tick(): Long {
        val currentApp = appId
        if (currentApp <= 0) return 10_000L
        if (!SteamOverlayClient.isAvailable()) return 8_000L

        val friends = SteamOverlayClient.listFriends()
        if (friends.isEmpty()) return 5_000L

        val playingNow = friends.filter { it.isPlaying(currentApp) }
        val playingIds = playingNow.map { it.steamId }.toSet()

        if (!seeded) {
            knownPlaying.clear()
            knownPlaying.addAll(playingIds)
            seeded = true
            return 3_000L
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
        return 3_000L
    }
}
