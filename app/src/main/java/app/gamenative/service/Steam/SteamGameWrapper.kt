package app.gamenative.data

import app.gamenative.Constants
import app.gamenative.enums.AppType
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService

/**
 * Steam game implementation
 */
data class SteamGameWrapper(
    private val steamApp: SteamApp,
) : Game {
    override val id: String get() = steamApp.id.toString()
    override val name: String get() = steamApp.name
    override val source: GameSource get() = GameSource.STEAM

    override val isInstalled: Boolean get() {
        val downloadDirectoryApps = DownloadService.getDownloadDirectoryApps()
        return downloadDirectoryApps.contains(SteamService.getAppDirName(steamApp))
    }

    override val isShared: Boolean get() {
        val thisSteamId: Int = SteamService.userSteamId?.accountID?.toInt() ?: 0
        return thisSteamId != 0 && !steamApp.ownerAccountId.contains(thisSteamId)
    }

    override val iconUrl: String get() =
        Constants.Library.ICON_URL + "${steamApp.id}/${steamApp.clientIconHash}.ico"

    override val appType: AppType get() = steamApp.type

    override fun toLibraryItem(index: Int): LibraryItem = LibraryItem(
        index = index,
        appId = "STEAM_${steamApp.id}",
        name = steamApp.name,
        iconUrl = iconUrl,
        isShared = isShared,
        gameSource = GameSource.STEAM,
    )
}
