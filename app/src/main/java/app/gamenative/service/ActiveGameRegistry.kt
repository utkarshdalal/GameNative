package app.gamenative.service

import app.gamenative.data.GameProcessInfo

/**
 * Tracks the single Steam game session currently managed by GameNative.
 *
 * GameNative only supports one active game at a time, so storing a single slot
 * avoids stale entries being re-announced after reconnect.
 */
object ActiveGameRegistry {
    @Volatile
    private var activeGame: GameProcessInfo? = null

    fun get(): GameProcessInfo? = activeGame

    @Synchronized
    fun set(gameProcessInfo: GameProcessInfo) {
        activeGame = gameProcessInfo
    }

    @Synchronized
    fun clearIfMatches(appId: Int) {
        if (activeGame?.appId == appId) {
            activeGame = null
        }
    }

    @Synchronized
    fun clear() {
        activeGame = null
    }
}
