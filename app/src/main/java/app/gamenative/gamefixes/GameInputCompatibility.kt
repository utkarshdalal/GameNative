package app.gamenative.gamefixes

/** Input compatibility choices that must be active while a game is running. */
object GameInputCompatibility {
    private val mouseDragCompatibilityGames = setOf(
        "STEAM_3858650", // Crushed in Time
    )

    /**
     * Enables narrowly scoped XGE support and sub-pixel mouse-drag accumulation
     * for games whose Windows pointer state diverges from the visible X11 cursor.
     */
    fun needsMouseDragCompatibility(appId: String, useGlibcContainer: Boolean): Boolean =
        !useGlibcContainer && appId in mouseDragCompatibilityGames
}
