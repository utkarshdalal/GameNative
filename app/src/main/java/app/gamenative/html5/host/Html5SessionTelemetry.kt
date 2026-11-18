package app.gamenative.html5.host

import android.content.Context
import android.os.SystemClock
import androidx.webkit.WebViewCompat
import app.gamenative.PrefManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.GameSessionId
import com.posthog.PostHog
import com.winlator.container.Container

// game_exited for webview sessions. like the wine event it always fires with the game identity and session
// length; the extra detail follows the usage-analytics setting. wine-only fields are left out, not zeroed.
internal object Html5SessionTelemetry {
    @Volatile private var sessionStartMs = 0L
    @Volatile private var rendererGone = false

    fun sessionStarted() {
        GameSessionId.bind()
        sessionStartMs = SystemClock.elapsedRealtime()
        rendererGone = false
    }

    fun markRendererGone() {
        rendererGone = true
    }

    fun captureExit(context: Context, appId: String, engineProfile: String?) {
        val start = sessionStartMs
        if (start == 0L) return
        sessionStartMs = 0L
        val webViewPackage = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        PostHog.capture(
            event = "game_exited",
            properties = exitProperties(
                gameName = ContainerUtils.resolveGameName(appId),
                gameStore = ContainerUtils.extractGameSourceFromContainerId(appId).name,
                sessionLengthSec = (SystemClock.elapsedRealtime() - start) / 1000,
                sessionUuid = GameSessionId.active(),
                usageAnalytics = PrefManager.usageAnalyticsEnabled,
                engineProfile = engineProfile,
                webViewPackage = webViewPackage?.packageName,
                webViewVersion = webViewPackage?.versionName,
                rendererGone = rendererGone,
            ),
        )
        GameSessionId.end()
    }

    internal fun exitProperties(
        gameName: String,
        gameStore: String,
        sessionLengthSec: Long,
        sessionUuid: String?,
        usageAnalytics: Boolean,
        engineProfile: String?,
        webViewPackage: String?,
        webViewVersion: String?,
        rendererGone: Boolean,
    ): Map<String, Any> = buildMap {
        put("game_name", gameName)
        put("game_store", gameStore)
        put("runtime", Container.RUNTIME_WEBVIEW)
        put("session_length", sessionLengthSec)
        sessionUuid?.let { put("session_uuid", it) }
        if (usageAnalytics) {
            engineProfile?.takeIf { it.isNotEmpty() }?.let { put("engine_pack", it) }
            webViewPackage?.let { put("webview_package", it) }
            webViewVersion?.let { put("webview_version", it) }
            put("renderer_gone", rendererGone)
        }
    }

    // trigger: "hint" (game page), "container_dialog", or "opfs_unsupported" (the launch fell back to wine).
    fun captureRuntimeSwitched(appId: String, from: String, to: String, trigger: String) {
        if (!PrefManager.usageAnalyticsEnabled) return
        PostHog.capture(
            event = "game_runtime_switched",
            properties = mapOf(
                "game_name" to ContainerUtils.resolveGameName(appId),
                "game_store" to ContainerUtils.extractGameSourceFromContainerId(appId).name,
                "from" to from,
                "to" to to,
                "trigger" to trigger,
            ),
        )
    }
}
