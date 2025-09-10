package app.gamenative.service.GOG

/**
 * Constants for GOG game service
 */
object GOGConstants {
    /**
     * Base storage path for GOG games
     * This path must match the E: drive mount in Winlator: /data/data/app.gamenative/storage
     */
    const val GOG_GAMES_BASE_PATH = "/data/data/app.gamenative/storage/gog_games"

    /**
     * Default directory name for GOG game installations
     */
    const val GOG_GAME_DIR_PREFIX = "game_"

    /**
     * Get the full path for a GOG game installation
     */
    fun getGameInstallPath(gameTitle: String): String {
        val sanitizedTitle = gameTitle.replace(Regex("[^a-zA-Z0-9\\s-_]"), "").trim()
        return "$GOG_GAMES_BASE_PATH/$sanitizedTitle"
    }
}
