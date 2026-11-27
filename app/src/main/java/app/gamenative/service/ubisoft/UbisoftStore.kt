package app.gamenative.service.ubisoft

import android.content.Context
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import com.winlator.container.Container
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Ubisoft Connect as a store: the owned library, install size, download with pause and resume,
 * updates, uninstall and launch preparation, with no Steam involved. Every call here is made on a
 * user action; nothing is fetched on its own.
 */
object UbisoftStore {
    /** Sizes for the install dialog; [unsupported] says why the build cannot be installed here. */
    data class InstallInfo(val downloadBytes: Long, val installBytes: Long, val language: String?, val unsupported: String?)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun manifestDir(context: Context) = File(context.filesDir, "ubisoft/manifests")

    fun isSignedIn(context: Context): Boolean = UbisoftAuthManager.load(context) != null

    fun displayName(context: Context): String? = UbisoftAuthManager.displayName(context)

    /** Opens Ubisoft's sign-in page when there is no usable session. */
    suspend fun signIn(context: Context): Result<Unit> = withHelper(context) { UbisoftLoginGate.ensureSignedIn(context) }

    fun signOut(context: Context) = UbisoftAuthManager.logout(context)

    /** The account's installable games. */
    suspend fun library(context: Context): Result<List<UbisoftStoreGame>> = withHelper(context) {
        runCatching {
            session(context).use { s ->
                val owned = s.ownedGames().map { g ->
                    if (g.configuration.isNotBlank() || !g.owned || g.productType != 0 || g.latestManifest.isBlank()) {
                        g
                    } else {
                        g.copy(configuration = runCatching { s.productConfig(g.productId) }.getOrNull().orEmpty())
                    }
                }
                UbisoftLibrary.storeGames(owned) { file -> assetUrl(context, file) }.also { games ->
                    val bases = owned.filter { it.owned && !it.suspended && it.productType == 0 }
                    val platforms = bases.mapNotNull { UbisoftProductConfig.parse(it.configuration)?.thirdPartyPlatform }.groupingBy { it }.eachCount()
                    Timber.i(
                        "Ubisoft ownership: ${owned.size} product(s), ${bases.size} owned game(s), ${bases.count { it.latestManifest.isBlank() }} without a build, " +
                            "started by another store: $platforms, ${games.size} installable",
                    )
                }
            }
        }.onFailure { Timber.w(it, "Ubisoft library could not be read") }
    }

    suspend fun installInfo(context: Context, game: UbisoftStoreGame): Result<InstallInfo> = withHelper(context) {
        runCatching {
            session(context).use { s ->
                val manifest = UbisoftManifest.parse(manifest(context, s, game))
                val unsupported = runCatching { manifest.requireSupported() }.exceptionOrNull()?.message
                val language = manifest.pickLanguage(Locale.getDefault())
                val plan = UbisoftInstallPlan.build(manifest.selectFiles(language, UbisoftLibrary.ownedIds(s.ownedGames())))
                InstallInfo(plan.downloadBytes, plan.installBytes, language, unsupported)
            }
        }
    }

    /** Installs [game] into [installDir], or updates the build already there. Canceling the job pauses it. */
    suspend fun install(context: Context, game: UbisoftStoreGame, installDir: File, downloadInfo: DownloadInfo): Result<UbisoftInstallRecord> =
        withHelper(context) {
            runCatching {
                session(context).use { s ->
                    downloadInfo.updateStatusMessage(context.getString(R.string.download_preparing))
                    val bytes = manifest(context, s, game)
                    val language = UbisoftInstaller.readRecord(installDir)?.language
                        ?: UbisoftManifest.parse(bytes).pickLanguage(Locale.getDefault())
                    val record = UbisoftInstaller(s, http, installDir)
                        .install(game, bytes, language, UbisoftLibrary.ownedIds(s.ownedGames()), DownloadInfoProgress(context, downloadInfo))
                    manifestDir(context).listFiles().orEmpty()
                        .filter { it.name.startsWith("${game.productId}-") && it.name != manifestName(game) }
                        .forEach { it.delete() }
                    record
                }
            }.onFailure {
                if (it is CancellationException) throw it
                Timber.w(it, "Ubisoft install of %d stopped", game.productId)
            }
        }

    fun installedRecord(installDir: File): UbisoftInstallRecord? = UbisoftInstaller.readRecord(installDir)

    /** A newer build than the installed one is published. */
    fun needsUpdate(installDir: File, game: UbisoftStoreGame): Boolean =
        installedRecord(installDir)?.let { it.manifestId != game.latestManifest } ?: false

    fun uninstall(context: Context, installDir: File, productId: Long): Boolean {
        manifestDir(context).listFiles().orEmpty().filter { it.name.startsWith("$productId-") }.forEach { it.delete() }
        return installDir.deleteRecursively()
    }

    /** See [UbisoftLaunchSupport.checkStandalone]. */
    suspend fun checkLaunch(context: Context, installDir: File, appId: String) = UbisoftLaunchSupport.checkStandalone(context, installDir, appId)

    /** See [UbisoftLaunchSupport.prepareStandalone]; the helper dependency has already fetched the stub and runtime. */
    suspend fun prepareLaunch(
        context: Context,
        container: Container,
        installDir: File,
        appId: String,
        gameRoot: String = "A:\\",
        onProgress: (Float) -> Unit = {},
    ): UbisoftLaunchSupport.StandaloneLaunch = UbisoftLaunchSupport.prepareStandalone(context, container, installDir, appId, gameRoot, onProgress)

    fun takeExitOutcome(container: Container): UbisoftExitOutcome? = UbisoftLaunchSupport.takeExitOutcome(container)

    private fun manifestName(game: UbisoftStoreGame) = "${game.productId}-${game.latestManifest}.manifest"

    /** The build's manifest, from the cache or the store. */
    private fun manifest(context: Context, s: UbisoftStoreSession, game: UbisoftStoreGame): ByteArray {
        val cached = File(manifestDir(context), manifestName(game))
        if (cached.isFile) return cached.readBytes()
        val urls = s.urls(game.productId, listOf("manifests/${game.latestManifest}.manifest")).first()
        var last: Exception? = null
        for (url in urls) {
            try {
                val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("manifest HTTP ${resp.code}")
                    resp.body?.bytes() ?: throw java.io.IOException("manifest answer has no body")
                }
                UbisoftManifest.parse(bytes)
                cached.parentFile?.mkdirs()
                cached.writeBytes(bytes)
                return bytes
            } catch (e: Exception) {
                last = e
            }
        }
        throw java.io.IOException("the manifest of ${game.productId} could not be downloaded", last)
    }

    private fun session(context: Context): UbisoftStoreSession {
        val creds = UbisoftAuthManager.load(context) ?: throw UbisoftCloudException("not signed in to Ubisoft", 401)
        fun need(name: String) = UbisoftHelperConfig.optional(context, name) ?: throw UbisoftCloudException("Ubisoft store configuration is missing $name")
        return UbisoftStoreSession(
            creds = creds,
            ownershipService = need(UbisoftHelperConfig.DEMUX_SERVICE_OWNERSHIP),
            downloadService = need(UbisoftHelperConfig.DEMUX_SERVICE_DOWNLOAD),
        ) {
            UbisoftDemuxClient.connect(
                host = need(UbisoftHelperConfig.DEMUX_HOST),
                port = need(UbisoftHelperConfig.DEMUX_PORT).toInt(),
                clientVersion = need(UbisoftHelperConfig.DEMUX_CLIENT_VERSION).toLong(),
                clientId = need(UbisoftHelperConfig.DEMUX_CLIENT_ID),
                ticket = creds.ticket,
            )
        }
    }

    private fun assetUrl(context: Context, file: String): String =
        UbisoftHelperConfig.optional(context, UbisoftHelperConfig.ASSETS_URL)?.let { UbisoftHelperConfig.fill(it, mapOf("file" to file)) }.orEmpty()

    /** The helper configuration holds the service endpoints; a session that is close to expiry is refreshed first. */
    private suspend fun <T> withHelper(context: Context, block: suspend () -> Result<T>): Result<T> = withContext(Dispatchers.IO) {
        runCatching { UbisoftHelperArchive.downloadAndExtract(context) {} }.onFailure { return@withContext Result.failure(it) }
        if (isSignedIn(context)) {
            when (val fresh = UbisoftAuthManager.ensureFresh(context)) {
                is UbisoftAuthManager.Freshness.Ready -> Unit
                UbisoftAuthManager.Freshness.NeedsSignIn -> Unit
                is UbisoftAuthManager.Freshness.Unavailable -> return@withContext Result.failure(IllegalStateException(fresh.reason))
            }
        }
        block()
    }

    private class DownloadInfoProgress(private val context: Context, private val info: DownloadInfo) : UbisoftProgress {
        private val done = AtomicLong()
        @Volatile private var total = 0L

        override fun total(bytes: Long, done: Long) {
            total = bytes
            this.done.set(done)
            info.setTotalExpectedBytes(bytes)
            info.initializeBytesDownloaded(done)
            publish(done)
        }

        override fun advance(bytes: Long) {
            info.updateBytesDownloaded(bytes)
            publish(done.addAndGet(bytes))
        }

        override fun phase(phase: UbisoftProgress.Phase) = info.updateStatusMessage(
            when (phase) {
                UbisoftProgress.Phase.DOWNLOADING -> null
                UbisoftProgress.Phase.ASSEMBLING -> context.getString(R.string.download_assembling_files)
            },
        )

        private fun publish(done: Long) {
            if (total > 0) info.setProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
        }
    }
}
