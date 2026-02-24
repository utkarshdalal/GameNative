package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import com.winlator.container.Container
import kotlinx.coroutines.coroutineScope

/** DRM extras (conditional on container flags). */
internal object DrmExtrasDependency : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int) =
        !container.isUseLegacyDRM && !container.isLaunchRealSteam
    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        SteamService.isFileInstallable(context, "experimental-drm-20260116.tzst")
    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        context.getString(R.string.main_downloading_extras)
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
            "experimental-drm-20260116.tzst",
        ).await()
    }
}
