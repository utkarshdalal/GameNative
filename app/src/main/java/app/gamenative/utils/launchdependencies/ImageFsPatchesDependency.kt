package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import com.winlator.container.Container
import kotlinx.coroutines.coroutineScope

/** Wine imagefs patches (GLIBC only). */
internal object ImageFsPatchesDependency : LaunchDependency {
    private const val FILE = "imagefs_patches_gamenative.tzst"
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int) =
        container.containerVariant.equals(Container.GLIBC, ignoreCase = true)
    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        SteamService.isFileInstallable(context, FILE)
    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        context.getString(R.string.main_downloading_wine)
    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        SteamService.downloadImageFsPatches(
            onDownloadProgress = callbacks.setLoadingProgress,
            parentScope = this,
            context = context,
        ).await()
    }
}
