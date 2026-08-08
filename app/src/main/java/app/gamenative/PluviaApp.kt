package app.gamenative

import android.os.StrictMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.events.EventDispatcher
import app.gamenative.html5.host.ChromiumVersionGate
import app.gamenative.html5.host.WebViewOrigin
import app.gamenative.html5.install.Html5InstallWatcher
import app.gamenative.html5.profile.DefaultProfileWiper
import app.gamenative.html5.savesync.Html5CrashpadCleanup
import app.gamenative.html5.savesync.Html5LeveldbHealth
import app.gamenative.html5.savesync.Html5SaveSyncService
import app.gamenative.service.ActiveGameRegistry
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.sync.FrontendSyncManager
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerMigrator
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.PlayIntegrity
import app.gamenative.utils.downloader.ContainerFilesDownloader
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import com.google.android.play.core.splitcompat.SplitCompatApplication
import com.posthog.PersonProfiles

// Add PostHog imports
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.winlator.container.Container
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerRendererView
import com.winlator.xenvironment.XEnvironment
import timber.log.Timber
import dagger.hilt.android.HiltAndroidApp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

typealias NavChangedListener = NavController.OnDestinationChangedListener

@HiltAndroidApp
class PluviaApp : SplitCompatApplication() {

    @Inject lateinit var gogGameDao: GOGGameDao
    @Inject lateinit var amazonGameDao: AmazonGameDao
    @Inject lateinit var html5InstallWatcher: Html5InstallWatcher
    @Inject lateinit var html5SaveSyncService: Html5SaveSyncService

    private val appScope: CoroutineScope get() = Companion.appScope

    override fun onCreate() {
        super.onCreate()

        // MUST run before any org.xerial.snappy class loads (checked by SnappyLoader.loadNativeLibrary).
        // switches snappy-java's native loader from classpath-resource extraction (which fails on
        // Android because AGP strips .so files from non-lib/<abi>/ jar paths) to System.loadLibrary,
        // which finds our extractSnappyAndroidJni-relocated lib/<abi>/libsnappyjava.so.

        // skip on Robolectric -- host JVM has no libsnappyjava on its library path, but the jar's
        // classpath-resource path DOES yield a working native (Linux/Mac/Windows .so/.dylib/.dll).
        // setting use.systemlib here would break snappy for the whole JVM.
        if (!android.os.Build.FINGERPRINT.startsWith("robolectric")) {
            System.setProperty("org.xerial.snappy.use.systemlib", "true")
        }

        preloadSystemLibraries()

        // Allows to find resource streams not closed within GameNative and JavaSteam
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )

            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        NetworkMonitor.init(this)

        // Init our custom crash handler.
        CrashHandler.initialize(this)

        // Init our datastore preferences.
        PrefManager.init(this)
        FrontendSyncManager.init(this)

        // boot gate for chromium >= 100. gate-fail flips html5RuntimeDisabled;
        // WebViewScreen + install watcher + save-sync service all check this flag and no-op.
        if (!ChromiumVersionGate.isSupported(this)) {
            val chromiumMajor = ChromiumVersionGate.getMajor(this)
            val shown = chromiumMajor?.toString() ?: "unknown"
            Timber.tag("PluviaApp").w(
                "chromium %s gate fail — html5 runtime disabled (min major=%d)",
                shown,
                ChromiumVersionGate.MIN_MAJOR,
            )
            SnackbarManager.show(
                getString(R.string.webview_unsupported_chromium, shown, ChromiumVersionGate.MIN_MAJOR),
            )
            html5RuntimeDisabled = true
        } else {
            Timber.tag("PluviaApp").d("chromium gate ok — html5 runtime supported")
        }

        // resolve the html5 loopback port (single port for all containers; per-container
        // origin via *.localhost host). MUST run before save-sync starts so origin paths
        // are stable. failure to bind a deterministic port = html5 disabled this session
        // rather than silently drifting (drift would orphan saves under the wrong leveldb
        // origin filename).
        if (!html5RuntimeDisabled) {
            WebViewOrigin.init(this)
            WebViewOrigin.initFailureMessage()?.let { reason ->
                Timber.tag("PluviaApp").w("html5 port init failed: %s", reason)
                SnackbarManager.show(reason)
                html5RuntimeDisabled = true
            }
        }

        // html5 boot housekeeping. deferred off the main thread so Wine-only users don't pay for
        // it on every launch. order matters within the block: DefaultProfileWiper must finish
        // before WebView ever opens (chromium takes an exclusive lock at open), but it can race
        // freely with html5InstallWatcher.start() below since the watcher only does work when
        // LibraryInstallStatusChanged fires -- never at boot.
        appScope.launch {
            runCatching {
                DefaultProfileWiper.wipeIfNeeded(
                    context = this@PluviaApp,
                    flagRead = { PrefManager.html5DefaultProfileWiped },
                    flagWrite = { PrefManager.html5DefaultProfileWiped = it },
                )
            }.onFailure { Timber.e(it, "DefaultProfileWiper boot wipe failed") }

            if (!html5RuntimeDisabled) {
                // chromium ships no auto-repair for LocalStorage; without this, a single force-stop
                // mid-compaction permanently silently-drops all subsequent writes.
                runCatching { Html5LeveldbHealth.repairIfWedged(this@PluviaApp) }
                    .onFailure { Timber.e(it, "Html5LeveldbHealth boot scan failed") }
                // wipe accumulated crashpad dumps. chromium ships no knob to disable generation;
                // bounded-by-cleanup is the practical alternative. paired with SyncFileFilter
                // (which keeps any leakage out of cloud).
                runCatching { Html5CrashpadCleanup.wipe(this@PluviaApp) }
                    .onFailure { Timber.e(it, "Html5CrashpadCleanup boot wipe failed") }
            }
        }

        // start auto-flip watcher only AFTER chromium gate runs -- start() itself
        // also checks html5RuntimeDisabled, but starting it after the gate keeps the call ordered.
        // forward-only listener; subscribed once for the process lifetime.
        html5InstallWatcher.start()

        // exit-sync subscriber. same html5-gate semantics as the watcher.
        // wrapped in runCatching -- a start() failure must never prevent the app from launching.
        runCatching { html5SaveSyncService.start() }
            .onFailure { Timber.e(it, "failed to start Html5SaveSyncService") }

        // one-shot migration: clear per-appId html5 lastApplied sync markers whenever the html5
        // WebView origin format changes. clearing markers forces inbound sync to re-run
        // wine→webview at the new origin (wine prefix is unchanged so the wine-vs-lastApplied
        // gate would otherwise skip). idempotent across cold boots; bump TARGET when
        // introducing a future change.
        // v1 (target=1): `https://gamenative` → loopback `http://127.0.0.1:<port>`
        // v2 (target=2): loopback → per-container `https://game-<id>` over AssetLoader (DEAD,
        //                briefly tested then reverted because module-worker subresources fail)
        // v3 (target=3): per-container `http://<safeId>.localhost:<port>` over loopback --
        //                worker subresources reach the loopback server, save round-trip works.
        runCatching {
            val target = 3
            if (PrefManager.html5OriginMigrationVersion < target) {
                val syncStateDir = java.io.File(filesDir, "html5/sync-state")
                if (syncStateDir.isDirectory) {
                    val cleared = syncStateDir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".lastApplied") }
                        ?.count { it.delete() } ?: 0
                    Timber.i(
                        "html5 origin-migration v%d: cleared %d lastApplied marker(s)",
                        target, cleared,
                    )
                }
                PrefManager.html5OriginMigrationVersion = target
            }
        }.onFailure { Timber.e(it, "html5 origin-migration failed (non-fatal)") }

        // Initialize GOGConstants
        app.gamenative.service.gog.GOGConstants.init(this)

        DownloadService.populateDownloadService(this)

        migrateGogAmazonPaths()

        appScope.launch {
            ContainerMigrator.migrateLegacyContainersIfNeeded(
                context = applicationContext,
                onProgressUpdate = null,
                onComplete = null
            )
        }

        // Preload all container files in the background
        appScope.launch {
            ContainerFilesDownloader.preloadAllContainerFiles(applicationContext)
        }

        // Clear any stale temporary config overrides from previous app sessions
        try {
            IntentLaunchManager.clearAllTemporaryOverrides()
            Timber.d("[PluviaApp]: Cleared temporary config overrides from previous session")
        } catch (e: Exception) {
            Timber.e(e, "[PluviaApp]: Failed to clear temporary config overrides")
        }

        // Initialize PostHog Analytics
        val postHogConfig = PostHogAndroidConfig(
            apiKey = BuildConfig.POSTHOG_API_KEY,
            host = BuildConfig.POSTHOG_HOST,
        ).apply {
            /* turn every event into an identified one */
            personProfiles = PersonProfiles.ALWAYS
        }
        PostHogAndroid.setup(this, postHogConfig)
        com.posthog.PostHog.register("build_flavor", BuildConfig.FLAVOR)

        if (PrefManager.usageAnalyticsEnabled) {
            // WebView provider+version is the dominant html5 compatibility variable; capture it
            // as person-properties so the install-base distribution is queryable (decides whether
            // old/locked WebView is a real population or a documentable tail). see ChromiumVersionGate.
            val webView = ChromiumVersionGate.getWebViewInfo(this)
            com.posthog.PostHog.capture(
                event = "\$set",
                properties = mapOf(
                    "\$set" to mapOf(
                        "recommendation_enabled" to PrefManager.showRecommendations,
                        "webview_package" to (webView.packageName ?: "unknown"),
                        "webview_version" to (webView.versionName ?: "unknown"),
                        "webview_chromium_major" to (webView.major ?: -1),
                        "webview_html5_supported" to ((webView.major ?: 0) >= ChromiumVersionGate.MIN_MAJOR),
                        "webview_opfs_sah_supported" to ((webView.major ?: 0) >= ChromiumVersionGate.MIN_OPFS_SAH_MAJOR),
                    ),
                ),
            )
        }

        PlayIntegrity.warmUp(this)

    }

    /**
     * One-time migration: moves GOG/Amazon game directories from
     * {filesDir}/ to {dataDir}/ to match Steam/Epic, and updates DB paths.
     */
    private fun migrateGogAmazonPaths() {
        if (PrefManager.gogAmazonPathMigrated) return

        val dataDir = dataDir.path
        val filesDir = filesDir.absolutePath
        Timber.i("[Migration] Migrating GOG/Amazon install paths from $filesDir to $dataDir")

        val migrations = listOf(
            File(filesDir, "GOG") to File(dataDir, "GOG"),
            File(filesDir, "Amazon") to File(dataDir, "Amazon"),
        )

        for ((oldDir, newDir) in migrations) {
            if (!oldDir.exists()) continue
            if (newDir.exists()) {
                Timber.w("[Migration] Target already exists, skipping rename: ${newDir.path}")
                continue
            }
            val renamed = oldDir.renameTo(newDir)
            if (renamed) {
                Timber.i("[Migration] Renamed ${oldDir.path} -> ${newDir.path}")
            } else {
                Timber.w("[Migration] Failed to rename ${oldDir.path} -> ${newDir.path}")
            }
        }

        val oldPrefix = "$filesDir/"
        val newPrefix = "$dataDir/"

        runBlocking(Dispatchers.IO) {
            try {
                val gogGames = gogGameDao.getAllAsList()
                for (game in gogGames) {
                    if (game.installPath.isNotEmpty() && game.installPath.contains(oldPrefix)) {
                        val updated = game.copy(installPath = game.installPath.replace(oldPrefix, newPrefix))
                        gogGameDao.update(updated)
                    }
                }
                Timber.i("[Migration] Updated ${gogGames.count { it.installPath.contains(oldPrefix) }} GOG install paths")
            } catch (e: Exception) {
                Timber.e(e, "[Migration] Failed to update GOG DB paths")
            }

            try {
                val amazonGames = amazonGameDao.getAllAsList()
                for (game in amazonGames) {
                    if (game.installPath.isNotEmpty() && game.installPath.contains(oldPrefix)) {
                        val newPath = game.installPath.replace(oldPrefix, newPrefix)
                        amazonGameDao.markAsInstalled(game.productId, newPath, game.installSize, game.versionId)
                    }
                }
                Timber.i("[Migration] Updated ${amazonGames.count { it.installPath.contains(oldPrefix) }} Amazon install paths")
            } catch (e: Exception) {
                Timber.e(e, "[Migration] Failed to update Amazon DB paths")
            }
        }

        PrefManager.gogAmazonPathMigrated = true
        Timber.i("[Migration] GOG/Amazon path migration complete")
    }

    companion object {
        @JvmField
        val events: EventDispatcher = EventDispatcher()
        internal var onDestinationChangedListener: NavChangedListener? = null

        // process-lifetime supervisor scope. shared across the app for background work that
        // must outlive a specific Activity / Composable / ViewModel -- boot init, WebView
        // teardown's flush+snapshot awaits, etc. NonCancellable wrap at call sites that need
        // to survive Composable destruction.
        internal val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // TODO: find a way to make this saveable, this is terrible (leak that memory baby)
        internal var xEnvironment: XEnvironment? = null
        internal var xServerView: XServerRendererView? = null
        var inputControlsView: InputControlsView? = null
        var inputControlsManager: InputControlsManager? = null
        var touchpadView: TouchpadView? = null
        var achievementWatcher: app.gamenative.service.AchievementWatcher? = null

        // HTML5 runtime parallel to xEnvironment. WebViewScreen owns lifetime (registers on
        // attach, clears on dispose). MainActivity.onPause/onResume drives webView.onPause /
        // .onResume alongside the Wine equivalent so suspend semantics apply uniformly.
        var activeWebView: android.webkit.WebView? = null

        var isOverlayPaused by mutableStateOf(false)
        @Volatile
        var isActivityInForeground: Boolean = true

        // one-shot boot flag. true iff chromium < 100 or WebView package unavailable.
        // WebViewScreen checks this at compose-entry and snackbars + pops back if true.
        @Volatile
        var html5RuntimeDisabled: Boolean = false

        // Active runtime suspend policy for the current in-game session.
        var activeSuspendPolicy: String = Container.SUSPEND_POLICY_MANUAL
            private set
        private var hasInitializedSuspendPolicyState: Boolean = false

        fun setActiveSuspendPolicy(policy: String) {
            activeSuspendPolicy = Container.normalizeSuspendPolicy(policy)
            hasInitializedSuspendPolicyState = true
        }

        /**
         * full environment teardown — shared by XServerScreen.exit() and
         * MainActivity.onDestroy fallback so both paths clean up identically
         */
        fun shutdownEnvironment() {
            val env = xEnvironment
            Timber.i("shutdownEnvironment: env=%s", env != null)

            // per-step catch so one failing teardown doesn't prevent the rest from running
            runCatching { achievementWatcher?.stop() }
                .onFailure { Timber.e(it, "shutdownEnvironment: achievementWatcher.stop") }
            runCatching { SteamService.clearCachedAchievements() }
                .onFailure { Timber.e(it, "shutdownEnvironment: clearCachedAchievements") }
            runCatching { touchpadView?.releasePointerCapture() }
                .onFailure { Timber.e(it, "shutdownEnvironment: releasePointerCapture") }
            runCatching { env?.stopEnvironmentComponents() }
                .onFailure { Timber.e(it, "shutdownEnvironment: stopEnvironmentComponents") }

            xEnvironment = null
            inputControlsView = null
            inputControlsManager = null
            touchpadView = null
            achievementWatcher = null
            // null the WebView global too -- MainActivity's stale-keepAlive guard requires BOTH
            // xEnvironment and activeWebView null, so leaving this set wedges keepAlive on the
            // next same-process launch when an html5 session dies via this recovery path.
            activeWebView = null
            ActiveGameRegistry.clear()
            SteamService.keepAlive = false
            SteamService.clearPlayingConflict()
            clearActiveSuspendState()
        }

        fun clearActiveSuspendState() {
            activeSuspendPolicy = Container.SUSPEND_POLICY_MANUAL
            isOverlayPaused = false
            hasInitializedSuspendPolicyState = false
        }

        fun hasValidSuspendPolicyState(): Boolean = hasInitializedSuspendPolicyState

        fun isNeverSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)

        fun isManualSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

    }

    /**
     * Some native libraries we dlopen at runtime (libsteamclient.so via SteamBootstrap,
     * the lsfg-vk layer, etc.) depend on `libjpeg.so`, which isn't on every device's
     * dynamic linker search path. Pre-load the system copy here with RTLD_GLOBAL
     * semantics (System.load is global) so all subsequent dlopens find its symbols.
     *
     * Single place for all: runs once in Application.onCreate before any other
     * native lib is loaded by this process. Failures are non-fatal — devices that
     * don't have the file (or have it elsewhere) just fall through.
     */
    private fun preloadSystemLibraries() {
        val is64 = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val candidates = if (is64) {
            listOf("/system/lib64/libjpeg.so", "/system/lib/libjpeg.so")
        } else {
            listOf("/system/lib/libjpeg.so", "/system/lib64/libjpeg.so")
        }
        for (path in candidates) {
            if (!File(path).exists()) continue
            try {
                System.load(path)
                Timber.i("[PluviaApp]: Preloaded $path")
                return
            } catch (e: Throwable) {
                Timber.w(e, "[PluviaApp]: System.load($path) failed")
            }
        }
        Timber.w("[PluviaApp]: Could not preload system libjpeg.so (none of the candidate paths worked)")
    }
}
