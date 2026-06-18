package app.gamenative.html5.shim

import android.webkit.JavascriptInterface
import androidx.annotation.VisibleForTesting
import app.gamenative.PrefManager
import app.gamenative.html5.host.WebViewScreenViewModel
import app.gamenative.html5.savesync.GreenworksCloudClient
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.DownloadService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// host side of the steamworks shim. JS in assets/html5/shims/steamworks.js calls
// __gnSteamworksBridge.<method> via @JavascriptInterface; methods land on the WebView
// binder thread. all writes are sub-ms (< 1KB JSON / 4-byte stat).

// log dir keyed on container.id (parallels app_webview/Profile-<container.id>/).
// bridge owns achievements + stats cache + atomic file writes; AchievementWatcher
// reads achievements.json + stats/<name> identically to the Wine path -- no parallel plumbing.

// constructor takes appId/gseDir so seed (Html5AchievementSeed) and bridge writes target
// the same on-disk location resolved by SteamService.getGseSaveDirs(context, appId).
class SteamworksJsBridge(
    private val containerId: String,
    private val appId: Int,
    private val gseDir: File,
    // resolved Steam/Goldberg language NAME (e.g. "german"), already run through the same
    // precedence as navigator.language so the two channels agree. "english" = unset/unmappable.
    private val gameLanguage: String = "english",
) {
    // diagnostic JSONL sink.
    private val logFile: File by lazy {
        val root = File(DownloadService.baseExternalAppDirPath, "html5-logs/$containerId")
        root.mkdirs()
        File(root, "steamworks.jsonl")
    }

    // ---------------- caches ----------------
    // ConcurrentHashMap per -- bridge runs on WebView binder thread; concurrent
    // reads from JS + writes from seed need lock-free reads. private val so the only legal
    // post-construction mutation path is seedFromSchema() (visible-for-testing).
    private val achievementsCache = ConcurrentHashMap<String, Boolean>()
    private val earnedTimes = ConcurrentHashMap<String, Long>()
    private val statsCache = ConcurrentHashMap<String, Number>()
    private val statTypes = ConcurrentHashMap<String, String>()

    // session-scoped debounce for the first-greenworks-call observation hook.
    // first call sets true + persists WebViewContainer.greenworksCloudObserved=true on disk;
    // subsequent calls early-return. binder-thread synchronous so process-kill mid-session
    // doesn't lose the flip (NO scope.launch).
    @Volatile private var observedFlipped: Boolean = false

    // session-cached cloud quota JSON. populated on first getCloudQuota call.
    // subsequent calls return the cache. invalidate would happen on outbound success but
    // that's not strictly necessary for Cookie Clicker's "do you want to back up?" UI.
    @Volatile private var cachedQuota: String? = null

    // controller seam -- same shape as OpfsFlushController. JS-side
    // captureGreenworksOutboundSnapshot calls land here on the binder thread, write
    // the JSON to capturedSnapshot, then countDown the latch. WebViewScreen.onDispose
    // blocks on awaitGreenworksSnapshot(5_000) BEFORE webView.destroy().
    // syncOutbound greenworks branch reads consumeGreenworksOutboundSnapshot
    // post-event-emit to feed GreenworksCloudClient.upload
    private val greenworksSnapshotLatch = CountDownLatch(1)
    @Volatile private var capturedSnapshot: String? = null

    // ---------------- diagnostic JSONL ----------------

    @JavascriptInterface
    fun log(recordJson: String) {
        runCatching { logFile.appendText("$recordJson\n") }
            .onFailure { Timber.tag(TAG).w(it, "jsonl append failed") }
    }

    // visible-for-testing: lets tests override the sink without DownloadService init.
    internal fun logToFile(recordJson: String, file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("$recordJson\n")
        }.onFailure { Timber.tag(TAG).w(it, "jsonl append failed (override)") }
    }

    // ---------------- achievements (6) ----------------

    @JavascriptInterface
    fun activateAchievement(name: String): Boolean {
        Timber.tag(TAG).d("activateAchievement: %s", name)
        // idempotent: if already earned, do not re-write (preserves earned_time; tested)
        if (achievementsCache[name] == true) return true
        achievementsCache[name] = true
        earnedTimes[name] = System.currentTimeMillis() / 1000
        runCatching { writeAchievementsJsonAtomic() }
            .onFailure { Timber.tag(TAG).e(it, "achievements.json write failed") }
        return true
    }

    @JavascriptInterface
    fun clearAchievement(name: String): Boolean {
        Timber.tag(TAG).d("clearAchievement: %s", name)
        achievementsCache.remove(name)
        earnedTimes.remove(name)
        runCatching { writeAchievementsJsonAtomic() }
            .onFailure { Timber.tag(TAG).e(it, "achievements.json write failed (clear)") }
        return true
    }

    // Steam ISteamApps::GetCurrentGameLanguage / ISteamUtils::GetSteamUILanguage both return
    // the API language NAME ("english"/"german"/...) -- NOT BCP-47. we surface one value for
    // both (single language setting). steamworks.js + greenworks.js route here.
    @JavascriptInterface
    fun getGameLanguage(): String = gameLanguage.ifBlank { "english" }

    @JavascriptInterface
    fun getAchievement(name: String): Boolean = achievementsCache[name] == true

    @JavascriptInterface
    fun getAchievementNames(): String =
        JSONArray(achievementsCache.keys.toList()).toString()

    @JavascriptInterface
    fun getNumberOfAchievements(): Int = achievementsCache.size

    @JavascriptInterface
    fun indicateAchievementProgress(name: String, current: Int, max: Int): Boolean {
        // noop returning true. no native progress UI in v1; achievements.json untouched.
        Timber.tag(TAG).d("indicateAchievementProgress noop: %s %d/%d", name, current, max)
        return true
    }

    // ---------------- stats (4) ----------------

    @JavascriptInterface
    fun setStat(name: String, value: Double): Boolean {
        Timber.tag(TAG).d("setStat: %s=%s", name, value)
        // type from schema (seeded at boot via seedFromSchema). default int -- JS Number doesn't
        // distinguish int/float; schema is the only authoritative source.
        val type = statTypes[name] ?: "int"
        val coerced: Number = if (type == "float" || type == "avgrate") value.toFloat() else value.toInt()
        statsCache[name] = coerced
        runCatching { writeStatFileAtomic(name, coerced, type) }
            .onFailure { Timber.tag(TAG).e(it, "stat file write failed: %s", name) }
        return true
    }

    @JavascriptInterface
    fun getStatInt(name: String): Int = statsCache[name]?.toInt() ?: 0

    @JavascriptInterface
    fun getStatFloat(name: String): Double = statsCache[name]?.toDouble() ?: 0.0

    @JavascriptInterface
    fun storeStats(): Boolean {
        // touch achievements.json so AchievementWatcher's FileObserver MOVED_TO event fires --
        // observer doesn't watch the stats subdir, so this is the only way to trigger upload.
        // notifiedNames dedupe prevents false notifications.
        runCatching { writeAchievementsJsonAtomic() }
            .onFailure { Timber.tag(TAG).e(it, "storeStats touch failed") }
        return true
    }

    // ---------------- user identity ----------------
    // logged-in steam user info read straight from PrefManager (cached at login). returned as
    // primitives so the JS shim can populate steamIdStub with REAL values instead of placeholder
    // 0 / 'Player' -- some titles render the persona name + steamId in their UI.

    @JavascriptInterface
    fun getUserAccountId(): Int = PrefManager.steamUserAccountId

    // string to avoid JS Number precision loss on the 64-bit ID.
    @JavascriptInterface
    fun getUserSteamId64(): String = PrefManager.steamUserSteamId64.toString()

    @JavascriptInterface
    fun getUserPersonaName(): String = PrefManager.steamUserName

    // ---------------- requestStats ----------------

    @JavascriptInterface
    fun requestStats(): Boolean {
        // cache populated at seed; cb fires sync from JS via syncCb. no re-fetch in v1.
        return true
    }

    // ---------------- greenworks observation hook ----------------

    @JavascriptInterface
    fun markGreenworksCloudObserved() {
        // session debounce: in-memory flip happens once per process; persist runs once.
        if (observedFlipped) return
        observedFlipped = true
        Timber.tag("Html5GreenworksCloud").i("markGreenworksCloudObserved: containerId=%s", containerId)
        runCatching {
            val slug = WebViewScreenViewModel.slugFromAppId(containerId)
            if (slug == null) {
                Timber.tag("Html5GreenworksCloud").w(
                    "markGreenworksCloudObserved: no slug for containerId=%s — flag NOT persisted",
                    containerId,
                )
                return@runCatching
            }
            val current = WebViewContainer.load(slug)
            if (current == null) {
                Timber.tag("Html5GreenworksCloud").w(
                    "markGreenworksCloudObserved: WebViewContainer.load returned null for slug=%s — flag NOT persisted",
                    slug,
                )
                return@runCatching
            }
            if (current.greenworksCloudObserved) {
                // already persisted across a prior session; nothing to write.
                return@runCatching
            }
            WebViewContainer.save(slug, current.copy(greenworksCloudObserved = true))
            Timber.tag("Html5GreenworksCloud").i(
                "markGreenworksCloudObserved: persisted greenworksCloudObserved=true slug=%s",
                slug,
            )
        }.onFailure {
            Timber.tag("Html5GreenworksCloud").e(it, "markGreenworksCloudObserved: persist failed")
        }
    }

    // ---------------- parse-time cloud restore ----------------

    // Html5SaveSyncService.syncInbound caches the freshly-fetched cloud files here. the
    // restore path (steamworks.js inline at parse time) reads them via getInboundCloudJson
    // and writes localStorage in the game's actual origin. this avoids the
    // about:blank-vs-https://game-... origin mismatch that broke direct evaluateJavascript
    // localStorage writes from Kotlin (writes landed in about:blank's scope, lost on navigate).
    @Volatile
    private var inboundCloudFiles: List<Pair<String, ByteArray>> = emptyList()

    fun setInboundCloudFiles(files: List<Pair<String, ByteArray>>) {
        inboundCloudFiles = files
    }

    // returns JSON {filename: base64bytes, ...} for the renderer's inline-restore script.
    // utf-8 round-trip via base64 keeps non-ASCII safe through the JS string boundary.
    @JavascriptInterface
    fun getInboundCloudJson(): String {
        val obj = JSONObject()
        inboundCloudFiles.forEach { (name, bytes) ->
            obj.put(name, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
        return obj.toString()
    }

    // ---------------- cloud delete ----------------

    // greenworks.deleteFile in the renderer normally only clears localStorage[gn:gw:<name>].
    // for files that originated server-side (probe / debug / per-title saves users want to
    // purge from Steam Cloud), the localStorage clear isn't enough -- INBOUND keeps re-
    // downloading. this bridge method runs the actual SteamCloud.deleteFile RPC so the
    // file is tombstoned server-side. binder thread + runBlocking matches getCloudQuota.
    @JavascriptInterface
    fun deleteFromCloud(filename: String): Boolean {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            GreenworksCloudClient.deleteFromCloud(appId, filename)
        }
    }

    // ---------------- cloud quota ----------------

    @JavascriptInterface
    fun getCloudQuota(): String {
        cachedQuota?.let { return it }
        // delegate to GreenworksCloudClient.getQuotaJson, which returns
        // {"total": Long, "available": Long} as JSON string. on failure returns
        // a conservative fallback so the JS callback fires with valid numbers
        // and Cookie Clicker's UI doesn't gate on a NaN.
        val result = runCatching {
            GreenworksCloudClient.getQuotaJson(appId)
        }.getOrElse {
            Timber.tag("Html5GreenworksCloud").w(it, "getCloudQuota: falling back to conservative defaults")
            // 100MB total / 100MB available -- same shape as the success path.
            """{"total":104857600,"available":104857600}"""
        }
        cachedQuota = result
        return result
    }

    // invalidate-on-outbound-success (NOT 30s timer).
    // syncOutboundGreenworks calls this on successful upload. cheaper than wall-clock
    // TTL; matches semantics -- quota only changes when WE upload.
    fun invalidateCloudQuotaCache() {
        cachedQuota = null
        Timber.tag("Html5GreenworksCloud").d("invalidateCloudQuotaCache: cleared")
    }

    // ---------------- outbound snapshot controller ----------------

    @JavascriptInterface
    fun captureGreenworksOutboundSnapshot(json: String) {
        // called by WebViewScreen.onDispose JS via evaluateJavascript BEFORE webView.destroy().
        // json shape: {"<filename1>":"<base64-utf8-bytes>","<filename2>":"..."}.
        capturedSnapshot = json
        greenworksSnapshotLatch.countDown()
        Timber.tag("Html5GreenworksCloud").d(
            "captureGreenworksOutboundSnapshot: signaled (jsonLen=%d)",
            json.length,
        )
    }

    // visible-for-testing -- read the captured snapshot exactly once after the latch fires.
    // returns null if capture never ran. NOT a JavascriptInterface; called from
    // Html5SaveSyncService.syncOutbound on the dispatcher thread post-WebViewDestroyed event.
    internal fun consumeGreenworksOutboundSnapshot(): String? = capturedSnapshot

    // visible-for-testing -- block ≤ timeoutMs awaiting the JS snapshot capture. returns
    // true if the latch fired in time, false on timeout. caller logs the timeout but
    // proceeds to webView.destroy() -- partial flush is better than no flush.
    internal fun awaitGreenworksSnapshot(timeoutMs: Long): Boolean =
        greenworksSnapshotLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    // ---------------- visible-for-testing seam ----------------

    @VisibleForTesting
    internal fun seedFromSchema(
        achievements: Map<String, Boolean>,
        achTimes: Map<String, Long>,
        stats: Map<String, Number>,
        types: Map<String, String>,
    ) {
        achievementsCache.clear()
        achievementsCache.putAll(achievements)
        earnedTimes.clear()
        earnedTimes.putAll(achTimes)
        statsCache.clear()
        statsCache.putAll(stats)
        statTypes.clear()
        statTypes.putAll(types)
    }

    // snapshots the live caches and delegates to the shared GoldbergSaveFiles writer so the
    // on-disk shape stays byte-identical to the pre-launch seed path.
    @VisibleForTesting
    internal fun writeAchievementsJsonAtomic() {
        GoldbergSaveFiles.writeAchievementsJsonAtomic(gseDir, achievementsCache, earnedTimes)
    }

    @VisibleForTesting
    internal fun writeStatFileAtomic(name: String, value: Number, type: String) {
        GoldbergSaveFiles.writeStatFileAtomic(gseDir, name, value, type)
    }

    companion object {
        private const val TAG = "SteamworksJsBridge"
    }
}
