package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.service.ubisoft.UbisoftHelperArchive
import app.gamenative.service.ubisoft.UbisoftLaunchSupport
import app.gamenative.service.ubisoft.UbisoftRuntime
import com.winlator.container.Container
import java.io.File

/** The stub archive, then the runtime files from Ubisoft's installer. */
object UbisoftHelperDependency : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        gameSource == GameSource.STEAM && UbisoftLaunchSupport.servesLaunch(container) { File(SteamService.getAppDirPath(gameId)) }

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        UbisoftHelperArchive.isReady(context.filesDir) && UbisoftRuntime.isCached(context.filesDir)

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        context.getString(R.string.ubisoft_preparing)

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        UbisoftHelperArchive.downloadAndExtract(context, callbacks.setLoadingProgress)
        callbacks.setLoadingMessage(context.getString(R.string.ubisoft_runtime_installing))
        callbacks.setLoadingProgress(0f)
        UbisoftRuntime.ensureCached(context, callbacks.setLoadingProgress)
        callbacks.setLoadingProgress(1f)
    }
}
