package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.rockstar.RockstarHelperArchive
import app.gamenative.service.rockstar.RockstarLaunchSupport
import com.winlator.container.Container

/** No download or extraction during ordinary launches. Trigger from installed Rockstar files. */
object RockstarHelperDependency : LaunchDependency {
    /* An Epic copy is served only once an installed helper supports it, so only Steam copies can need the download. */
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        gameSource == GameSource.STEAM && RockstarLaunchSupport.servedSteamLaunch(container, gameId) != null

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
        RockstarHelperArchive.downloadAndExtract(context, callbacks.setLoadingProgress)
        callbacks.setLoadingProgress(1f)
    }
}
