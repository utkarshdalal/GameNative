package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.rockstar.RockstarHelperArchive
import app.gamenative.service.rockstar.RockstarLaunchSupport
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** No download or extraction during ordinary launches. Trigger from installed Rockstar files. */
object RockstarHelperDependency : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean = when (gameSource) {
        GameSource.STEAM -> RockstarLaunchSupport.servedSteamLaunch(container, gameId) != null
        GameSource.EPIC -> RockstarLaunchSupport.epicCopyNeedsHelper(gameId)
        else -> false
    }

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        RockstarHelperArchive.isReady(context.filesDir)

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Preparing Rockstar launcher support"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        try {
            RockstarHelperArchive.downloadAndExtract(context, callbacks.setLoadingProgress)
        } catch (e: Exception) {
            /* Without the helper an Epic copy starts its own executable, so the launch goes on. */
            if (e is CancellationException || gameSource != GameSource.EPIC) throw e
            Timber.w(e, "Rockstar helper unavailable for an Epic copy")
        }
        callbacks.setLoadingProgress(1f)
    }
}
