package app.gamenative.data

import app.gamenative.enums.AppType

/**
 * Unified interface for all game types (Steam, GOG, etc.)
 */
interface Game {
    val id: String
    val name: String
    val source: GameSource
    val isInstalled: Boolean
    val isShared: Boolean
    val iconUrl: String
    val appType: AppType

    fun toLibraryItem(index: Int): LibraryItem
}
