package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import com.winlator.container.Container
import kotlinx.coroutines.coroutineScope

/** Steam token (when launching real Steam). */
internal object SteamTokenDependency : LaunchDependency {
    private const val FILE = "steam-token.tzst"
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int) = container.isLaunchRealSteam
    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        SteamService.isFileInstallable(context, FILE)
    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        context.getString(R.string.main_downloading_steam_token)
    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        SteamService.downloadFile(
            onDownloadProgress = callbacks.setLoadingProgress,
            parentScope = this,
            context = context,
            FILE,
        ).await()
    }
}
