package app.gamenative.service.store

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.StoreGame
import app.gamenative.enums.SaveLocation
import app.gamenative.service.xbox.MicrosoftGameConfig
import app.gamenative.service.xbox.XboxDownloadState
import app.gamenative.service.xbox.XboxGame
import app.gamenative.service.xbox.XboxHelperArchive
import app.gamenative.service.xbox.XboxHelperConfig
import app.gamenative.service.xbox.XboxLaunchPlan
import app.gamenative.service.xbox.XboxStore
import app.gamenative.service.xbox.api.XboxApiConfig
import app.gamenative.service.xbox.api.XboxProduct
import com.winlator.container.Container
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Xbox PC (Microsoft Store and PC Game Pass) on the shared store seam; everything Xbox-specific stays behind [XboxStore]. */
object XboxStoreAdapter : StoreAdapter {
    override val source = GameSource.XBOX
    override val displayName = "Xbox"

    private val prepared = ConcurrentHashMap<Int, XboxLaunchPlan>()

    override fun isSignedIn(context: Context): Boolean = XboxStore.isSignedIn(context)

    override fun helperServesStore(context: Context): Boolean? =
        if (XboxHelperArchive.isReady(context.filesDir)) XboxHelperConfig.optional(context.filesDir, XboxApiConfig.DISPLAY_CATALOG_URL) != null else null

    override suspend fun prepareHelper(context: Context) {
        XboxHelperArchive.downloadAndExtract(context) {}
    }

    override fun accountName(context: Context): String? = if (isSignedIn(context)) XboxStore.displayName(context) ?: displayName else null

    override suspend fun signIn(context: Context): Result<Unit> = XboxStore.signIn(context).map { }

    override fun signOut(context: Context) {
        XboxStore.signOut(context)
    }

    override suspend fun fetchLibrary(context: Context): List<StoreGame> =
        XboxStore.library(context).map { storeGame(it.product, it.onGamePass) }

    override val addsGamesById: Boolean get() = true

    override suspend fun gameById(context: Context, id: String): StoreGame {
        val product = XboxStore.product(context, id)
        XboxStore.addStoreId(context, product.storeId)
        return storeGame(product, onGamePass = false)
    }

    private fun storeGame(product: XboxProduct, onGamePass: Boolean): StoreGame = StoreGame(
        source = source.name,
        productId = product.storeId.uppercase(),
        title = product.title ?: product.storeId,
        iconUrl = product.image("Logo", "Tile", "BoxArt")?.uri.orEmpty(),
        coverUrl = product.image("Poster", "BoxArt")?.uri.orEmpty(),
        heroUrl = product.image("SuperHeroArt", "TitledHeroArt", "BoxArt")?.uri.orEmpty(),
        developer = product.publisher.orEmpty(),
        downloadSize = product.downloadSizeInBytes ?: 0,
        unsupportedReason = unsupportedReason(product).orEmpty(),
        metadata = JSONObject()
            .put("packageFormat", product.desktopPackage?.packageFormat.orEmpty())
            .put("packageFamilyName", product.packageFamilyName.orEmpty())
            .put("onGamePass", onGamePass)
            .toString(),
    )

    /** Only MSIXVC (GDK) packages install; MSIXVC2, UWP, bundles and products without a PC package are refused. */
    private fun unsupportedReason(product: XboxProduct): String? = when {
        XboxGame(product, onGamePass = false, install = null).isInstallable -> null
        product.isBundle -> "bundle"
        else -> "package format ${product.desktopPackage?.packageFormat ?: "none"}"
    }

    private suspend fun installableProduct(context: Context, game: StoreGame): XboxProduct {
        val product = XboxStore.product(context, game.productId)
        unsupportedReason(product)?.let { throw UnsupportedOperationException(it) }
        return product
    }

    override suspend fun downloadSizes(context: Context, game: StoreGame): Pair<Long, Long>? {
        val size = installableProduct(context, game).downloadSizeInBytes ?: return null
        return size to size
    }

    override suspend fun latestVersion(context: Context, game: StoreGame): String? = XboxStore.latestVersion(context, game.productId)

    override suspend fun install(context: Context, game: StoreGame, installDir: File, progress: DownloadInfo): String {
        val product = installableProduct(context, game)
        val flow = XboxStore.startDownload(context, product, installDir)
        var lastDone = -1L
        val final = try {
            flow.first { p ->
                if (p.totalBytes > 0) progress.setTotalExpectedBytes(p.totalBytes)
                if (lastDone < 0 || p.doneBytes < lastDone) {
                    progress.initializeBytesDownloaded(p.doneBytes)
                } else {
                    progress.updateBytesDownloaded(p.doneBytes - lastDone)
                }
                lastDone = p.doneBytes
                if (p.totalBytes > 0) progress.setProgress(p.doneBytes.toFloat() / p.totalBytes)
                p.state != XboxDownloadState.QUEUED && p.state != XboxDownloadState.RUNNING
            }
        } catch (e: CancellationException) {
            XboxStore.pause(product.storeId)
            throw e
        }
        return when (final.state) {
            XboxDownloadState.COMPLETED ->
                XboxStore.installed(context).firstOrNull { it.storeId.equals(product.storeId, true) }?.version.orEmpty()
            XboxDownloadState.FAILED -> throw IllegalStateException(final.error ?: "Xbox install failed")
            else -> throw CancellationException("Xbox install stopped")
        }
    }

    override fun executable(game: StoreGame, installDir: File): String? =
        MicrosoftGameConfig.read(installDir)?.pcExecutable?.name?.replace('/', '\\')?.trimStart('\\')

    override suspend fun prepareLaunch(context: Context, container: Container, game: StoreGame, preferredSave: SaveLocation): StoreLaunchPrep {
        XboxStore.signIn(context).onFailure { return StoreLaunchPrep.Failed(it.message.orEmpty()) }
        prepared[game.appId] = runCatching { plan(context, container, game, StoreGames.windowsDir(container, game.installPath)) }
            .getOrElse { return StoreLaunchPrep.Failed(it.message.orEmpty()) }
        return StoreLaunchPrep.Ready
    }

    private suspend fun plan(context: Context, container: Container, game: StoreGame, gameDirWindows: String): XboxLaunchPlan =
        XboxStore.prepareLaunch(context, game.productId, File(container.rootDir, ".wine/drive_c"), gameDirWindows)

    override fun launchCommand(context: Context, container: Container, game: StoreGame, gameDirWindows: String): StoreLaunch {
        val plan = prepared.remove(game.appId) ?: runBlocking { plan(context, container, game, gameDirWindows) }
        val root = gameDirWindows.trimEnd('\\')
        val workingDir = plan.workingDirRelative.replace('\\', '/').let { if (it.isEmpty()) File(game.installPath) else File(game.installPath, it) }
        return StoreLaunch(
            command = "winhandler.exe \"$root\\${plan.executableRelative}\"",
            workingDir = workingDir,
            environment = plan.environment,
        )
    }

    override suspend fun onUninstall(context: Context, game: StoreGame) {
        XboxStore.uninstall(context, game.productId)
    }

    override suspend fun afterExit(context: Context, container: Container, game: StoreGame) {
        XboxStore.onGameExited()
    }
}
