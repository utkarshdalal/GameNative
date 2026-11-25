package app.gamenative.service.xbox

import android.content.Context
import app.gamenative.Crypto
import app.gamenative.service.xbox.api.XboxApis
import app.gamenative.service.xbox.api.XboxLibrary
import app.gamenative.service.xbox.api.XboxLoginGate
import app.gamenative.service.xbox.api.XboxOwnedGames
import app.gamenative.service.xbox.api.XboxProduct
import app.gamenative.service.xbox.live.LiveConfig
import app.gamenative.service.xbox.live.MsaTokens
import app.gamenative.service.xbox.live.XboxLicensing
import app.gamenative.service.xbox.live.load
import app.gamenative.service.xbox.msixvc.HttpRangeSource
import app.gamenative.service.xbox.msixvc.InstallResult
import app.gamenative.service.xbox.msixvc.MsixvcInstaller
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import timber.log.Timber

/** A library entry: catalog data, Game Pass membership and the local install, if any. */
data class XboxGame(
    val product: XboxProduct,
    val onGamePass: Boolean,
    val install: XboxInstallRecord?,
) {
    val storeId: String get() = product.storeId
    val title: String get() = product.title ?: product.storeId
    val downloadSizeBytes: Long? get() = product.downloadSizeInBytes
    /** Only MSIXVC (GDK) packages install; MSIXVC2, UWP and other formats are listed but refused. */
    val isInstallable: Boolean get() = product.isMsixvc && !product.isBundle
}

enum class XboxDownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

data class XboxDownloadProgress(
    val storeId: String,
    val state: XboxDownloadState,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

/**
 * Store-neutral entry point for Xbox PC (Microsoft Store / PC Game Pass GDK) titles: sign-in, library,
 * install (license, download, decrypt to plain files), update check, uninstall and launch preparation.
 * Nothing here runs at app start; every network call follows a user action.
 */
object XboxStore {
    private const val LICENSE_MARKET = "xbox.store.licenseMarket"
    private const val LIBRARY_MARKET = "xbox.store.market"
    private const val LIBRARY_LANGUAGE = "xbox.store.language"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Pair<Job, AtomicBoolean>>()
    private val progress = ConcurrentHashMap<String, MutableStateFlow<XboxDownloadProgress>>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var tokenStore: XboxTokenStore? = null

    fun tokenStore(context: Context): XboxTokenStore = tokenStore ?: synchronized(this) {
        tokenStore ?: XboxTokenStore(
            File(context.filesDir, "xbox/credentials.bin"),
            object : XboxTokenStore.Codec {
                override fun encrypt(bytes: ByteArray) = Crypto.encrypt(bytes)
                override fun decrypt(bytes: ByteArray) = Crypto.decrypt(bytes)
            },
        ).also { tokenStore = it }
    }

    private fun registry(context: Context) = XboxInstallRegistry(File(context.filesDir, "xbox/installs.json"))

    private fun knownIdsFile(context: Context) = File(context.filesDir, "xbox/known-store-ids.json")

    private fun stateDir(context: Context) = File(context.filesDir, "xbox/state")

    private fun apis(context: Context) = XboxApis(context, tokenStore(context), client)

    private fun liveConfig(context: Context) = LiveConfig.load(context.filesDir)

    private fun setting(context: Context, key: String, default: String) =
        runCatching { XboxHelperConfig.optional(context.filesDir, key) }.getOrNull() ?: default

    // ---- account ---------------------------------------------------------------------------

    fun isSignedIn(context: Context): Boolean = XboxLoginGate.isSignedIn(tokenStore(context))

    fun displayName(context: Context): String? = tokenStore(context).user()?.username

    /** Opens the sign-in activity when needed and suspends until it closes. */
    suspend fun signIn(context: Context): Result<XboxUser> = XboxLoginGate.ensureSignedIn(context, tokenStore(context))

    /** Keeps the device registration and installed games. */
    fun signOut(context: Context) = tokenStore(context).clearUser()

    // ---- library ---------------------------------------------------------------------------

    /** Store ids the user added by hand (Game Pass titles the account does not own yet, or ids the owned list misses). */
    fun addStoreId(context: Context, storeId: String) = synchronized(this) {
        val ids = knownIds(context) + storeId.trim().uppercase()
        knownIdsFile(context).apply { parentFile?.mkdirs() }.writeText(JSONArray(ids.distinct()).toString())
    }

    private fun knownIds(context: Context): List<String> =
        runCatching { JSONArray(knownIdsFile(context).readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrElse { emptyList() }

    /**
     * Owned PC games (when the account listing answers), ids added by hand and installed games, resolved on
     * displaycatalog and marked with Game Pass membership.
     */
    suspend fun library(context: Context): List<XboxGame> = withContext(Dispatchers.IO) {
        val apis = apis(context)
        val store = tokenStore(context)
        val owned = if (isSignedIn(context)) {
            runCatching {
                val cfg = liveConfig(context)
                XboxOwnedGames(client, apis.config, apis.xsts) {
                    MsaTokens.userRpsToken(
                        store, cfg,
                        address = apis.config.value(XboxOwnedGames.KEY_MSA_SCOPE),
                        policy = XboxHelperConfig.optional(context.filesDir, XboxOwnedGames.KEY_MSA_POLICY),
                    ).token
                }.storeIds(setting(context, LIBRARY_MARKET, "US"), setting(context, LIBRARY_LANGUAGE, "en-US"))
            }.onFailure { Timber.w(it, "Xbox: owned games listing unavailable") }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val installs = registry(context).all().associateBy { it.storeId.uppercase() }
        val ids = (owned + knownIds(context) + installs.keys).distinct()
        val products = apis.library.products(ids) { id, e -> Timber.w(e, "Xbox: no catalog entry for %s", id) }
        val families = runCatching { apis.library.gamePassFamilies() }.getOrDefault(emptySet())
        products.map { XboxGame(it, XboxLibrary.isOnGamePass(it, families), installs[it.storeId.uppercase()]) }
    }

    /** One product by Store id on displaycatalog. */
    suspend fun product(context: Context, storeId: String): XboxProduct = withContext(Dispatchers.IO) {
        XboxHelperArchive.downloadAndExtract(context) {}
        apis(context).catalog.product(storeId.trim().uppercase())
    }

    // ---- install ---------------------------------------------------------------------------

    fun installed(context: Context): List<XboxInstallRecord> = registry(context).all()

    fun progress(storeId: String): StateFlow<XboxDownloadProgress>? = progress[storeId.uppercase()]

    /**
     * Starts or resumes an install into [installDir]. The content keys are fetched first (the account must own
     * the title or have it through Game Pass), then the package streams into plain files; the package itself is
     * never stored, so disk use is the installed size.
     */
    fun startDownload(context: Context, product: XboxProduct, installDir: File): StateFlow<XboxDownloadProgress> {
        val id = product.storeId.uppercase()
        val flow = progress.getOrPut(id) { MutableStateFlow(XboxDownloadProgress(id, XboxDownloadState.QUEUED)) }
        if (jobs[id]?.first?.isActive == true) return flow
        val stop = AtomicBoolean(false)
        flow.value = XboxDownloadProgress(id, XboxDownloadState.QUEUED, flow.value.doneBytes, flow.value.totalBytes)
        val job = scope.launch {
            try {
                install(context.applicationContext, product, installDir, flow, stop)
            } catch (e: Exception) {
                Timber.e(e, "Xbox: install of %s failed", id)
                flow.value = flow.value.copy(state = XboxDownloadState.FAILED, error = e.message ?: e.javaClass.simpleName)
            } finally {
                jobs.remove(id)
            }
        }
        jobs[id] = job to stop
        return flow
    }

    private suspend fun install(
        context: Context,
        product: XboxProduct,
        installDir: File,
        flow: MutableStateFlow<XboxDownloadProgress>,
        stop: AtomicBoolean,
    ) {
        val id = product.storeId.uppercase()
        check(product.isMsixvc) { "Only MSIXVC packages can be installed" }
        val contentId = product.contentId ?: error("The product has no desktop package")
        XboxHelperArchive.downloadAndExtract(context) {}
        val store = tokenStore(context)
        val apis = apis(context)
        val keys = XboxLicensing.contentKeys(store, liveConfig(context), contentId, setting(context, LICENSE_MARKET, "neutral"))
        val details = apis.packages.basePackage(contentId) ?: error("No package is available for this account")
        val main = details.mainPackage ?: error("The package has no MSIXVC file")
        val url = main.urls.firstOrNull() ?: error("The package has no download location")
        check(installDir.isDirectory || installDir.mkdirs()) { "Cannot create the install folder" }
        flow.value = XboxDownloadProgress(id, XboxDownloadState.RUNNING, 0, main.fileSize)
        val state = File(stateDir(context), "$id.state").apply { parentFile?.mkdirs() }
        val installer = MsixvcInstaller(
            source = HttpRangeSource(client, url, main.fileSize),
            keys = keys,
            targetDir = installDir,
            stateFile = state,
            metadataFile = File(installDir, ".gamenative-xbox-package.json"),
        )
        val result = installer.install(
            onProgress = { done, total -> flow.value = XboxDownloadProgress(id, XboxDownloadState.RUNNING, done, total) },
            isCancelled = { stop.get() },
        )
        if (result == InstallResult.CANCELED) {
            flow.value = flow.value.copy(state = XboxDownloadState.PAUSED)
            return
        }
        val config = MicrosoftGameConfig.read(installDir)
        registry(context).put(
            XboxInstallRecord(
                storeId = id,
                title = product.title ?: id,
                contentId = contentId,
                packageFamilyName = config?.packageFamilyName ?: product.packageFamilyName,
                version = details.versionId,
                installPath = installDir.absolutePath,
                sizeBytes = flow.value.totalBytes,
            ),
        )
        flow.value = flow.value.copy(state = XboxDownloadState.COMPLETED, doneBytes = flow.value.totalBytes)
    }

    /** Stops after the chunks in flight; [startDownload] resumes from there. */
    fun pause(storeId: String) {
        jobs[storeId.uppercase()]?.second?.set(true)
    }

    /** Stops and removes everything the install wrote. */
    suspend fun cancel(context: Context, storeId: String, installDir: File) {
        val id = storeId.uppercase()
        jobs[id]?.let { (job, stop) -> stop.set(true); job.join() }
        withContext(Dispatchers.IO) {
            if (registry(context).get(id) == null) installDir.deleteRecursively()
            File(stateDir(context), "$id.state").delete()
        }
        progress[id]?.value = XboxDownloadProgress(id, XboxDownloadState.CANCELED)
    }

    suspend fun uninstall(context: Context, storeId: String) = withContext(Dispatchers.IO) {
        val record = registry(context).get(storeId) ?: return@withContext
        File(record.installPath).deleteRecursively()
        File(stateDir(context), "${record.storeId.uppercase()}.state").delete()
        registry(context).remove(record.storeId)
        progress.remove(record.storeId.uppercase())
    }

    /** The package build the service offers for an installed title, or null when it cannot tell. */
    suspend fun latestVersion(context: Context, storeId: String): String? {
        val record = registry(context).get(storeId) ?: return null
        return apis(context).packages.basePackage(record.contentId)?.versionId
    }

    /** True when the service offers a different package build than the installed one. */
    suspend fun hasUpdate(context: Context, storeId: String): Boolean {
        val record = registry(context).get(storeId) ?: return false
        val details = apis(context).packages.basePackage(record.contentId) ?: return false
        return record.version != null && details.versionId != record.version
    }

    // ---- launch ----------------------------------------------------------------------------

    /** Runtime stand-in, identity file, early-load proxy and token endpoint for one launch. */
    suspend fun prepareLaunch(
        context: Context,
        storeId: String,
        prefixDriveC: File,
        installDirWindows: String,
        onProgress: (Float) -> Unit = {},
    ): XboxLaunchPlan {
        val record = registry(context).get(storeId) ?: error("$storeId is not installed")
        val store = tokenStore(context)
        val tokens = RuntimeMsaTokenProvider { clientId, fullTrust ->
            val t = MsaTokens.msaTokenForRuntime(store, liveConfig(context), clientId, fullTrust)
            RuntimeMsaToken(t.token, t.expiry, t.deviceRps, t.deviceExpiry)
        }
        return XboxLaunchSupport.prepare(context, prefixDriveC, File(record.installPath), installDirWindows, record, tokens, onProgress)
    }

    fun onGameExited() = XboxLaunchSupport.stop()
}
