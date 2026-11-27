package app.gamenative.service.store

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.StoreGame
import app.gamenative.enums.SaveLocation
import app.gamenative.service.ubisoft.UbisoftCloudPreference
import app.gamenative.service.ubisoft.UbisoftCloudSavesManager
import app.gamenative.service.ubisoft.UbisoftHelperArchive
import app.gamenative.service.ubisoft.UbisoftHelperConfig
import app.gamenative.service.ubisoft.UbisoftHelperDeployment
import app.gamenative.service.ubisoft.UbisoftLoginGate
import app.gamenative.service.ubisoft.UbisoftStore
import app.gamenative.service.ubisoft.UbisoftStoreGame
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import timber.log.Timber

/** Ubisoft Connect on the shared store seam; everything Ubisoft-specific stays behind [UbisoftStore]. */
object UbisoftStoreAdapter : StoreAdapter {
    override val source = GameSource.UBISOFT
    override val displayName = "Ubisoft"

    override fun isSignedIn(context: Context): Boolean = UbisoftStore.isSignedIn(context)

    override fun helperServesStore(context: Context): Boolean? =
        if (UbisoftHelperArchive.isReady(context.filesDir)) UbisoftHelperConfig.optional(context, UbisoftHelperConfig.DEMUX_SERVICE_DOWNLOAD) != null else null

    override suspend fun prepareHelper(context: Context) {
        UbisoftHelperArchive.downloadAndExtract(context) {}
    }

    override fun accountName(context: Context): String? = if (isSignedIn(context)) UbisoftStore.displayName(context) ?: displayName else null

    override suspend fun signIn(context: Context): Result<Unit> = UbisoftStore.signIn(context)

    override fun signOut(context: Context) = UbisoftStore.signOut(context)

    override suspend fun fetchLibrary(context: Context): List<StoreGame> = UbisoftStore.library(context).getOrThrow().map { game ->
        StoreGame(
            source = source.name,
            productId = game.productId.toString(),
            title = game.title,
            iconUrl = game.iconUrl,
            coverUrl = game.coverUrl,
            heroUrl = game.heroUrl,
            latestVersion = game.latestManifest,
            metadata = JSONObject()
                .put("uplayId", game.uplayId)
                .put("latestManifest", game.latestManifest)
                .put("configuration", game.configuration)
                .put("logoUrl", game.logoUrl)
                .put("appId", game.appId)
                .toString(),
        )
    }

    override fun logoUrl(game: StoreGame): String =
        runCatching { JSONObject(game.metadata).optString("logoUrl") }.getOrDefault("")

    private fun ubisoftGame(game: StoreGame): UbisoftStoreGame {
        val meta = runCatching { JSONObject(game.metadata) }.getOrElse { JSONObject() }
        return UbisoftStoreGame(
            productId = game.productId.toLong(),
            uplayId = meta.optLong("uplayId"),
            title = game.title,
            coverUrl = game.coverUrl,
            iconUrl = game.iconUrl,
            heroUrl = game.heroUrl,
            latestManifest = meta.optString("latestManifest").ifEmpty { game.latestVersion },
            configuration = meta.optString("configuration"),
            appId = meta.optString("appId"),
        )
    }

    override suspend fun downloadSizes(context: Context, game: StoreGame): Pair<Long, Long> {
        val info = UbisoftStore.installInfo(context, ubisoftGame(game)).getOrThrow()
        info.unsupported?.let { throw UnsupportedOperationException(it) }
        return info.downloadBytes to info.installBytes
    }

    /** The library refresh carries the latest build; there is no separate update query. */
    override suspend fun latestVersion(context: Context, game: StoreGame): String? = game.latestVersion.ifEmpty { null }

    override suspend fun install(context: Context, game: StoreGame, installDir: File, progress: DownloadInfo): String =
        UbisoftStore.install(context, ubisoftGame(game), installDir, progress).getOrThrow().manifestId

    override fun executable(game: StoreGame, installDir: File): String? =
        UbisoftStore.installedRecord(installDir)?.let { UbisoftHelperDeployment.EXECUTABLE }

    override suspend fun prepareLaunch(context: Context, container: Container, game: StoreGame, preferredSave: SaveLocation): StoreLaunchPrep {
        UbisoftLoginGate.ensureSignedIn(context).onFailure { return StoreLaunchPrep.Failed(it.message.orEmpty()) }
        val installDir = File(game.installPath)
        runCatching { UbisoftStore.checkLaunch(context, installDir, ubisoftGame(game).appId) }.onFailure { return StoreLaunchPrep.Failed(it.message.orEmpty()) }
        if (container.isLocalSavesOnly) return StoreLaunchPrep.Ready
        val preference = when (preferredSave) {
            SaveLocation.Local -> UbisoftCloudPreference.LOCAL
            SaveLocation.Remote -> UbisoftCloudPreference.REMOTE
            SaveLocation.None -> UbisoftCloudPreference.NONE
        }
        val uplayId = UbisoftStore.installedRecord(installDir)?.uplayId ?: ubisoftGame(game).uplayId
        val pull = UbisoftCloudSavesManager.syncBeforeLaunch(context, container, game.appId, preference, uplayId)
        Timber.i("Ubisoft cloud save pull for ${game.title}: $pull")
        return if (pull is UbisoftCloudSavesManager.PullResult.Conflict) StoreLaunchPrep.SaveConflict(pull.localMillis, pull.remoteMillis) else StoreLaunchPrep.Ready
    }

    override fun launchCommand(context: Context, container: Container, game: StoreGame, gameDirWindows: String): StoreLaunch {
        // Written only now: a container's first boot replaces the registry hives written before it.
        val launch = runBlocking { UbisoftStore.prepareLaunch(context, container, File(game.installPath), ubisoftGame(game).appId, gameDirWindows) }
        return StoreLaunch("winhandler.exe \"${launch.executable}\" ${launch.arguments}".trimEnd())
    }

    override suspend fun onUninstall(context: Context, game: StoreGame) {
        if (game.installPath.isNotEmpty()) UbisoftStore.uninstall(context, File(game.installPath), game.productId.toLong())
    }

    override suspend fun afterExit(context: Context, container: Container, game: StoreGame) {
        if (!container.isLocalSavesOnly) UbisoftCloudSavesManager.syncAfterExit(context, container, game.appId)
    }
}
