package app.gamenative.service

import app.gamenative.data.GameProcessInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks Steam game process info for games currently running through GameNative.
 *
 * This lets SteamService re-send notifyGamesPlayed() after the Steam connection
 * is re-established (for example after device sleep / resume) without needing to
 * re-discover game processes from scratch.
 */
object ActiveGameRegistry {
    private val activeGames = ConcurrentHashMap<Int, GameProcessInfo>()

    fun put(gameProcessInfo: GameProcessInfo) {
        activeGames[gameProcessInfo.appId] = gameProcessInfo
    }

    fun remove(appId: Int) {
        activeGames.remove(appId)
    }

    fun clear() {
        activeGames.clear()
    }

    fun all(): List<GameProcessInfo> = activeGames.values.toList()
}
