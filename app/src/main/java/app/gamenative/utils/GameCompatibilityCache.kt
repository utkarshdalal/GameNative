package app.gamenative.utils

import timber.log.Timber

/**
 * In-memory cache for game compatibility responses.
 * Caches all games to avoid re-checking every time user navigates back to library.
 */
object GameCompatibilityCache {
    private val cache = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

    /**
     * Gets cached compatibility response for a game, if available.
     */
    fun getCached(gameName: String): GameCompatibilityService.GameCompatibilityResponse? {
        return cache[gameName]
    }

    /**
     * Caches a compatibility response for a game.
     */
    fun cache(gameName: String, response: GameCompatibilityService.GameCompatibilityResponse) {
        cache[gameName] = response
        Timber.tag("GameCompatibilityCache").d("Cached compatibility for: $gameName")
    }

    /**
     * Checks if a game's compatibility is cached.
     */
    fun isCached(gameName: String): Boolean {
        return cache.containsKey(gameName)
    }

    /**
     * Clears the entire cache.
     */
    fun clear() {
        cache.clear()
        Timber.tag("GameCompatibilityCache").d("Cache cleared")
    }

    /**
     * Gets the current cache size.
     */
    fun size(): Int = cache.size
}

