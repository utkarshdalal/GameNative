package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import com.winlator.container.Container
import kotlinx.coroutines.coroutineScope

/** ImageFs base (first-time files) for the container variant. */
internal object ImageFsBaseDependency : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int) = true
    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        SteamService.isImageFsInstallable(context, container.containerVariant)
    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        context.getString(R.string.main_downloading_first_time_files)
    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        SteamService.downloadImageFs(
            onDownloadProgress = callbacks.setLoadingProgress,
            parentScope = this,
            variant = container.containerVariant,
            context = context,
        ).await()
    }
}
