package app.gamenative.data

import app.gamenative.enums.AppType

/**
 * GOG game implementation
 */
data class GOGGameWrapper(
    private val gogGame: GOGGame,
) : Game {
    override val id: String get() = gogGame.id
    override val name: String get() = gogGame.title
    override val source: GameSource get() = GameSource.GOG
    override val isInstalled: Boolean get() = gogGame.isInstalled
    override val isShared: Boolean get() = false
    override val iconUrl: String get() = "https://images.gog-statics.com/games/${gogGame.id}_icon.jpg"
    override val appType: AppType get() = AppType.game

    override fun toLibraryItem(index: Int): LibraryItem = LibraryItem(
        index = index,
        appId = "GOG_${gogGame.id}",
        name = gogGame.title,
        iconHash = "",
        isShared = false,
        gameSource = GameSource.GOG,
    )
}
