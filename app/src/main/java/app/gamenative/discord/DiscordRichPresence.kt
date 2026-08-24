package app.gamenative.discord

import androidx.annotation.VisibleForTesting
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.ContainerUtils
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** Why Discord Rich Presence is or is not currently publishing. */
enum class DiscordAvailability {
    DISABLED,
    SDK_NOT_BUNDLED,
    NOT_CONFIGURED,
    READY,

    /** The SDK rejected an update, usually because Discord isn't installed or is signed out. */
    UNAVAILABLE,
}

/**
 * Publishes the running game as the user's Discord activity ("Playing <game>" with an elapsed
 * timer), and clears it when the game exits.
 *
 * Uses the Discord Social SDK's unauthenticated RPC path via [DiscordNative], which on Android
 * publishes through the installed Discord app while it is signed in. No login, no account linking
 * and nothing is read back from the user's account.
 *
 * Every path is best-effort: a missing SDK, a missing app ID or Discord being unreachable lands in
 * [availability] and GameNative carries on as if the integration weren't there.
 *
 * All work is posted to a single background thread, so no internal locking is needed and callers
 * never block.
 */
object DiscordRichPresence {

    private const val TAG = "DiscordRPC"
    private const val UPDATE_RETRY_DELAY_MS = 5_000L
    private const val FALLBACK_GAME_NAME = "a Windows game"

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "discord-rpc").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // Backstop only: every native and service call below is already guarded individually. An
    // optional feature must never take the app down from a background coroutine.
    private val handler = CoroutineExceptionHandler { _, t ->
        Timber.tag(TAG).e(t, "Unhandled failure on the Discord thread")
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + handler)

    @Volatile
    var availability = DiscordAvailability.SDK_NOT_BUNDLED
        private set

    // Only ever touched from the dispatcher's single thread.
    private var currentSession: GameSession? = null
    private var pendingLaunch: PendingLaunch? = null
    private var initialized = false

    private data class PendingLaunch(val containerId: String, val startedAtMs: Long)

    private data class GameSession(
        val containerId: String,
        val name: String,
        val startedAtMs: Long,
        val artUrl: String?,
    )

    /**
     * Publishes [containerId]'s game, counting up from [startedAtMs] (epoch millis). Safe to call
     * for every launch, it is a no-op unless the feature is enabled and Discord is reachable.
     */
    fun onGameStarted(containerId: String, startedAtMs: Long) {
        scope.launch {
            // Held even when we can't publish, so flipping the setting on mid-game works.
            pendingLaunch = PendingLaunch(containerId, startedAtMs)
            if (!ensureInitialized()) return@launch
            publishPendingLaunch()
        }
    }

    /** Clears the published activity. Safe to call when nothing is published. */
    fun onGameStopped() {
        scope.launch {
            pendingLaunch = null
            clearPresence()
        }
    }

    /** Turning the setting off clears presence; turning it on republishes a running game. */
    fun onEnabledChanged(enabled: Boolean) {
        scope.launch {
            if (!enabled) {
                clearPresence()
                teardown()
                availability = DiscordAvailability.DISABLED
                return@launch
            }
            if (ensureInitialized()) publishPendingLaunch()
        }
    }

    private fun publishPendingLaunch() {
        val launch = pendingLaunch ?: return
        // Resolving name and art hits the store services, so it is deferred to here rather than
        // done on every game launch regardless of whether Discord is enabled.
        val session = GameSession(
            containerId = launch.containerId,
            name = resolveGameName(launch.containerId),
            startedAtMs = launch.startedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            artUrl = resolveArtUrl(launch.containerId),
        )
        currentSession = session
        Timber.tag(TAG).i("Publishing presence for '%s'", session.name)
        publish(session, isRetry = false)
    }

    private fun ensureInitialized(): Boolean {
        // Re-checked every call so the setting takes effect without a restart, and guarded because
        // an optional feature must not be what brings the app down.
        val enabled = runCatching { PrefManager.discordRichPresenceEnabled }.getOrDefault(false)
        if (!enabled) {
            availability = DiscordAvailability.DISABLED
            return false
        }
        if (!DiscordNative.isAvailable) {
            availability = DiscordAvailability.SDK_NOT_BUNDLED
            if (DiscordConfig.sdkBundled) {
                // Packaging problem, not the ordinary "this build ships without Discord" case.
                Timber.tag(TAG).w("Discord SDK bundled but the JNI bridge failed to load")
            }
            return false
        }
        if (DiscordConfig.applicationId <= 0L) {
            availability = DiscordAvailability.NOT_CONFIGURED
            Timber.tag(TAG).w("Discord SDK bundled but no DISCORD_APPLICATION_ID was set at build time")
            return false
        }
        if (initialized) return true

        // Handing the Activity over starts the SDK's own networking, which aborts the process if
        // it fails, so a user who never enables the feature must not be exposed to it.
        if (!DiscordSocialSdkCompat.attachEngineActivity()) {
            availability = DiscordAvailability.UNAVAILABLE
            return false
        }

        val started = runCatching { DiscordNative.nativeInitialize(DiscordConfig.applicationId) }
            .onFailure { Timber.tag(TAG).e(it, "Discord client failed to initialize") }
            .getOrDefault(false)
        if (!started) {
            availability = DiscordAvailability.UNAVAILABLE
            return false
        }

        DiscordNative.presenceResultListener = ::onPresenceResult
        initialized = true
        availability = DiscordAvailability.READY
        Timber.tag(TAG).i("Discord Rich Presence initialized")
        return true
    }

    /** Stops the native client and its callback pump. */
    private fun teardown() {
        if (!initialized) return
        initialized = false
        DiscordNative.presenceResultListener = null
        runCatching { DiscordNative.nativeShutdown() }
            .onFailure { Timber.tag(TAG).w(it, "Failed to shut down Discord client") }
    }

    private fun clearPresence() {
        val session = currentSession ?: return
        currentSession = null
        if (!initialized) return

        runCatching { DiscordNative.nativeClearPresence() }
            .onSuccess { Timber.tag(TAG).i("Cleared presence for '%s'", session.name) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to clear presence") }
    }

    private fun publish(session: GameSession, isRetry: Boolean) {
        val hasGameArt = session.artUrl != null
        val posted = runCatching {
            DiscordNative.nativeUpdatePresence(
                name = session.name,
                details = null,
                state = null,
                startTimestampMs = session.startedAtMs,
                largeImage = session.artUrl ?: DiscordConfig.FALLBACK_IMAGE_ASSET_KEY,
                largeText = if (hasGameArt) session.name else DiscordConfig.FALLBACK_IMAGE_TEXT,
                smallImage = null,
                smallText = null,
            )
        }.onFailure {
            Timber.tag(TAG).e(it, "Failed to publish presence")
            availability = DiscordAvailability.UNAVAILABLE
        }.isSuccess

        if (posted && !isRetry) scheduleRetryIfStillFailing(session)
    }

    /**
     * A rejected update surfaces in [onPresenceResult] rather than at the call site, so retry once
     * a few seconds later: Discord's RPC endpoint is often not up yet when a game launch wakes the
     * device. One retry only, so a user without Discord doesn't cost a polling loop.
     */
    private fun scheduleRetryIfStillFailing(session: GameSession) {
        scope.launch {
            delay(UPDATE_RETRY_DELAY_MS)
            if (currentSession != session) return@launch
            if (availability != DiscordAvailability.UNAVAILABLE) return@launch
            Timber.tag(TAG).i("Retrying presence for '%s'", session.name)
            publish(session, isRetry = true)
        }
    }

    private fun onPresenceResult(success: Boolean, message: String) {
        if (success) {
            availability = DiscordAvailability.READY
            Timber.tag(TAG).d("Presence update accepted")
        } else {
            availability = DiscordAvailability.UNAVAILABLE
            Timber.tag(TAG).w("Presence update rejected: %s", message)
        }
    }

    /** Test-only: the dispatcher is single-threaded and FIFO, so a finished no-op means idle. */
    @VisibleForTesting
    internal fun awaitIdle(timeoutMs: Long = 5_000L) {
        runBlocking { withTimeout(timeoutMs) { scope.launch { }.join() } }
    }

    private fun resolveGameName(containerId: String): String =
        runCatching { ContainerUtils.resolveGameName(containerId) }
            .getOrElse {
                Timber.tag(TAG).w(it, "Could not resolve game name")
                ""
            }
            .ifBlank { FALLBACK_GAME_NAME }

    /**
     * The game's own cover art as an https URL. Discord's image fields take an uploaded asset key
     * or an external URL, and an application is capped at 300 uploaded assets, so per-game art
     * comes from the store CDNs instead. Squarer art is preferred, since Discord renders it square.
     *
     * Null for custom games: their art is local files Discord's servers can't reach.
     */
    private fun resolveArtUrl(containerId: String): String? = runCatching {
        val gameId = ContainerUtils.extractGameIdFromContainerId(containerId)
        val candidates = when (ContainerUtils.extractGameSourceFromContainerId(containerId)) {
            GameSource.STEAM -> SteamService.getAppInfoOf(gameId)?.let {
                listOf(it.getCapsuleUrl(), it.headerUrl, it.iconUrl)
            }
            GameSource.GOG -> GOGService.getGOGGameOf(gameId.toString())?.let {
                listOf(it.iconUrl, it.imageUrl)
            }
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId)?.let {
                listOf(it.iconUrl, it.primaryImageUrl)
            }
            GameSource.AMAZON -> AmazonService.getAmazonGameByAppId(gameId)?.let {
                listOf(it.artUrl, it.heroUrl)
            }
            GameSource.CUSTOM_GAME -> null
        }
        candidates.orEmpty().firstOrNull(::isPublishableImageUrl)
    }.getOrElse {
        Timber.tag(TAG).w(it, "Could not resolve game art")
        null
    }

    /** Discord caps image fields at 300 characters and can only fetch http(s) URLs. */
    private fun isPublishableImageUrl(url: String): Boolean =
        url.length in 1..300 && (url.startsWith("https://") || url.startsWith("http://"))
}
