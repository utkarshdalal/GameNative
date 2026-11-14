package app.gamenative.html5.shim

import android.util.Base64
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

// host side of steamworks.js / greenworks.js. methods run on the WebView binder thread; writes are
// small enough to do inline. writes the same Goldberg files as the wine path so AchievementWatcher
// needs no html5 plumbing. gseDir MUST be the same dir Html5AchievementSeed seeded.
class SteamworksJsBridge(
    private val containerId: String,
    private val appId: Int,
    private val gseDir: File,
    // Steam language NAME (e.g. "german"), resolved with the same precedence as navigator.language
    // so the two agree.
    private val gameLanguage: String = "english",
) {
    private val logFile: File by lazy {
        val root = File(DownloadService.baseExternalAppDirPath, "html5-logs/$containerId")
        root.mkdirs()
        File(root, "steamworks.jsonl")
    }

    // concurrent: JS reads on the binder thread while the seed writes.
    private val achievementsCache = ConcurrentHashMap<String, Boolean>()
    private val earnedTimes = ConcurrentHashMap<String, Long>()

    // keyed LOWERCASE like the seed and the stat files; game JS passes schema case ("NumWins"),
    // so every access MUST go through statKey.
    private val statsCache = ConcurrentHashMap<String, Number>()
    private val statTypes = ConcurrentHashMap<String, String>()

    // Steam stat names are [A-Za-z0-9_], so lowercase() is a lossless canonical form here.
    private fun statKey(name: String): String = name.lowercase()

    // persisted synchronously on the binder thread (NO scope.launch) so a mid-session kill
    // doesn't lose the flag.
    @Volatile private var observedFlipped: Boolean = false

    @Volatile private var cachedQuota: String? = null

    // exit handshake: WebViewScreen.onDispose blocks on awaitGreenworksSnapshot BEFORE
    // webView.destroy(); JS answers via captureGreenworksOutboundSnapshot.
    private val greenworksSnapshotLatch = CountDownLatch(1)
    @Volatile private var capturedSnapshot: String? = null

    @JavascriptInterface
    fun log(recordJson: String) {
        runCatching { logFile.appendText("$recordJson\n") }
            .onFailure { Timber.tag(TAG).w(it, "jsonl append failed") }
    }

    internal fun logToFile(recordJson: String, file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("$recordJson\n")
        }.onFailure { Timber.tag(TAG).w(it, "jsonl append failed (override)") }
    }

    @JavascriptInterface
    fun activateAchievement(name: String): Boolean {
        Timber.tag(TAG).d("activateAchievement: %s", name)
        // don't rewrite an earned one -- preserves earned_time.
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

    // serves both GetCurrentGameLanguage and GetSteamUILanguage: a language NAME, NOT BCP-47.
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
        Timber.tag(TAG).d("indicateAchievementProgress noop: %s %d/%d", name, current, max)
        return true
    }

    @JavascriptInterface
    fun setStat(name: String, value: Double): Boolean {
        Timber.tag(TAG).d("setStat: %s=%s", name, value)
        // JS Number can't tell int from float; only the schema knows.
        val key = statKey(name)
        val type = statTypes[key] ?: "int"
        val coerced: Number = if (type == "float" || type == "avgrate") value.toFloat() else value.toInt()
        statsCache[key] = coerced
        runCatching { writeStatFileAtomic(key, coerced, type) }
            .onFailure { Timber.tag(TAG).e(it, "stat file write failed: %s", name) }
        return true
    }

    @JavascriptInterface
    fun getStatInt(name: String): Int = statsCache[statKey(name)]?.toInt() ?: 0

    @JavascriptInterface
    fun getStatFloat(name: String): Double = statsCache[statKey(name)]?.toDouble() ?: 0.0

    @JavascriptInterface
    fun storeStats(): Boolean {
        // rewrite achievements.json only to wake AchievementWatcher -- it doesn't watch stats/,
        // so this is the only upload trigger. its dedupe prevents false notifications.
        runCatching { writeAchievementsJsonAtomic() }
            .onFailure { Timber.tag(TAG).e(it, "storeStats touch failed") }
        return true
    }

    // zeroed stats reach Steam via the close-time sync. achievement clears are never pushed, so
    // they only last until the next seed.
    @JavascriptInterface
    fun resetAllStats(achievementsToo: Boolean): Boolean {
        Timber.tag(TAG).d("resetAllStats: achievementsToo=%s stats=%d", achievementsToo, statsCache.size)
        statsCache.keys.forEach { name ->
            val type = statTypes[name] ?: "int"
            val zero: Number = if (type == "float" || type == "avgrate") 0f else 0
            statsCache[name] = zero
            runCatching { writeStatFileAtomic(name, zero, type) }
                .onFailure { Timber.tag(TAG).e(it, "stat file write failed (reset): %s", name) }
        }
        if (achievementsToo) {
            achievementsCache.clear()
            earnedTimes.clear()
            runCatching { writeAchievementsJsonAtomic() }
                .onFailure { Timber.tag(TAG).e(it, "achievements.json write failed (reset)") }
        }
        return true
    }

    @JavascriptInterface
    fun getUserAccountId(): Int = PrefManager.steamUserAccountId

    // string to avoid JS Number precision loss on the 64-bit ID.
    @JavascriptInterface
    fun getUserSteamId64(): String = PrefManager.steamUserSteamId64.toString()

    @JavascriptInterface
    fun getUserPersonaName(): String = PrefManager.steamUserName

    // lazy: the store lookups hit the DB.
    private val dlcs by lazy { Html5DlcResolver.forContainer(containerId) }

    @JavascriptInterface
    fun isDlcInstalled(dlcAppId: Int): Boolean = dlcs.any { it.appId == dlcAppId && it.installed }

    @JavascriptInterface
    fun getDlcListJson(): String = Html5DlcResolver.toJson(dlcs)

    @JavascriptInterface
    fun requestStats(): Boolean {
        // already seeded pre-launch; no re-fetch.
        return true
    }

    @JavascriptInterface
    fun markGreenworksCloudObserved() {
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

    // cloud files fetched by syncInbound, pulled by steamworks.js at parse time so localStorage is
    // written in the game's real origin. writing from Kotlin via evaluateJavascript lands in
    // about:blank's origin and is lost on navigate.
    @Volatile
    private var inboundCloudFiles: List<Pair<String, ByteArray>> = emptyList()

    fun setInboundCloudFiles(files: List<Pair<String, ByteArray>>) {
        inboundCloudFiles = files
    }

    // base64 keeps arbitrary bytes safe across the JS string boundary.
    @JavascriptInterface
    fun getInboundCloudJson(): String {
        val obj = JSONObject()
        inboundCloudFiles.forEach { (name, bytes) ->
            obj.put(name, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
        return obj.toString()
    }

    // greenworks.saveFilesToCloud bytes can't ride the gn:gw:* localStorage namespace: the exit
    // capture uploads it as btoa(utf8(value)), which corrupts every byte >= 0x80 of a binary save.
    // staged raw here and merged into the exit upload verbatim. in-memory on purpose: a kill loses
    // the pending upload, but the file is still on disk and the game re-uploads on its next save.
    private val stagedCloudFiles = ConcurrentHashMap<String, ByteArray>()

    @JavascriptInterface
    fun stageCloudFile(name: String, base64: String) {
        val bytes = runCatching { Base64.decode(base64, Base64.NO_WRAP) }.getOrNull()
        if (bytes == null) {
            Timber.tag("Html5GreenworksCloud").w("stageCloudFile: undecodable base64 for name=%s", name)
            return
        }
        stagedCloudFiles[name] = bytes
        markGreenworksCloudObserved()
        Timber.tag("Html5GreenworksCloud").i("stageCloudFile: name=%s bytes=%d", name, bytes.size)
    }

    // drains so a second exit in the same process can't re-upload stale bytes.
    internal fun consumeStagedCloudFiles(): Map<String, ByteArray> {
        if (stagedCloudFiles.isEmpty()) return emptyMap()
        val copy = HashMap(stagedCloudFiles)
        stagedCloudFiles.clear()
        return copy
    }

    // clearing localStorage alone isn't enough for server-side files -- inbound sync would keep
    // re-downloading them. this tombstones the file in Steam Cloud.
    @JavascriptInterface
    fun deleteFromCloud(filename: String): Boolean {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            GreenworksCloudClient.deleteFromCloud(appId, filename)
        }
    }

    @JavascriptInterface
    fun getCloudQuota(): String {
        cachedQuota?.let { return it }
        // on failure return real numbers so game UI doesn't gate on NaN.
        val result = runCatching {
            GreenworksCloudClient.getQuotaJson(appId)
        }.getOrElse {
            Timber.tag("Html5GreenworksCloud").w(it, "getCloudQuota: falling back to conservative defaults")
            """{"total":104857600,"available":104857600}"""
        }
        cachedQuota = result
        return result
    }

    // quota only changes when WE upload, so no TTL needed.
    fun invalidateCloudQuotaCache() {
        cachedQuota = null
        Timber.tag("Html5GreenworksCloud").d("invalidateCloudQuotaCache: cleared")
    }

    @JavascriptInterface
    fun captureGreenworksOutboundSnapshot(json: String) {
        // {"<filename>":"<base64-utf8-bytes>", ...}
        capturedSnapshot = json
        greenworksSnapshotLatch.countDown()
        Timber.tag("Html5GreenworksCloud").d(
            "captureGreenworksOutboundSnapshot: signaled (jsonLen=%d)",
            json.length,
        )
    }

    // null if capture never ran.
    internal fun consumeGreenworksOutboundSnapshot(): String? = capturedSnapshot

    // false on timeout; the caller destroys anyway -- a partial flush beats none.
    internal fun awaitGreenworksSnapshot(timeoutMs: Long): Boolean =
        greenworksSnapshotLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

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
        stats.forEach { (name, value) -> statsCache[statKey(name)] = value }
        statTypes.clear()
        types.forEach { (name, type) -> statTypes[statKey(name)] = type }
    }

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
