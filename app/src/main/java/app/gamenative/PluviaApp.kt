package app.gamenative

import android.os.StrictMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.events.AndroidEvent
import app.gamenative.events.EventDispatcher
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerMigrator
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.PlayIntegrity
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
import com.winlator.widget.XServerView
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

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

        if (PrefManager.usageAnalyticsEnabled) {
            com.posthog.PostHog.capture(
                event = "\$set",
                properties = mapOf(
                    "\$set" to mapOf("recommendation_enabled" to PrefManager.showRecommendations),
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

        // TODO: find a way to make this saveable, this is terrible (leak that memory baby)
        internal var xEnvironment: XEnvironment? = null
        internal var xServerView: XServerView? = null
        var inputControlsView: InputControlsView? = null
        var inputControlsManager: InputControlsManager? = null
        var touchpadView: TouchpadView? = null
        var achievementWatcher: app.gamenative.service.AchievementWatcher? = null

        var isOverlayPaused by mutableStateOf(false)
        @Volatile
        var isActivityInForeground: Boolean = true

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
            SteamService.keepAlive = false
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
}
