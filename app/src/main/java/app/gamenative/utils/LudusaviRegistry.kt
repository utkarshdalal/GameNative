package app.gamenative.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches the community-maintained
 * [Ludusavi save manifest](https://github.com/mtkennerly/ludusavi-manifest), filtering
 * to "Pattern B" Steam games — those whose Steamworks SDK cloud saves live inside their
 * install directory rather than under <userdata>/<appid>/remote/.
 *
 * Used by the "Use Recommended" button in the SDK Cloud Save Bridge container setting
 * and by the first-launch prompt in preLaunchApp.
 *
 * Flow on lookup:
 *  1. Check in-memory cache → return if present.
 *  2. Read disk cache JSON → if mtime < CACHE_TTL_MS old, parse and use.
 *  3. Else fetch upstream YAML, filter to Pattern B Steam entries, write disk cache.
 *  4. On fetch failure, fall back to stale disk cache if present; otherwise return null.
 *
 * Disk cache format is a filtered JSON (~190 KB) — much cheaper to re-parse than the
 * full 5 MB YAML on every cold start.
 */
object LudusaviRegistry {

    private const val MANIFEST_URL = "https://raw.githubusercontent.com/mtkennerly/ludusavi-manifest/master/data/manifest.yaml"
    private const val CACHE_FILE = "ludusavi_pattern_b.json"
    private val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7)

    @Volatile
    private var memoryCache: Map<Int, SteamUtils.SdkCloudSaveRecommendation>? = null
    // Serializes the fetch + disk-write + memoryCache populate path. Without this, a
    // concurrent primeCache() + lookup() could both pass the null check, both fetch the
    // 5 MB manifest, and both write the disk cache (last writer wins; the wasted work and
    // duplicate network traffic are the real cost).
    private val loadMutex = Mutex()

    /**
     * Returns the recommended save subdir for [appId] from the Ludusavi manifest, or null
     * if the game isn't listed or the registry can't be loaded. Network-bound on cold cache.
     * Safe to call from a coroutine; performs disk I/O and HTTPS on Dispatchers.IO.
     */
    suspend fun lookup(context: Context, appId: Int): SteamUtils.SdkCloudSaveRecommendation? = withContext(Dispatchers.IO) {
        ensureLoaded(context)?.get(appId)
    }

    /**
     * Priming call used at app start. Populates the cache (fetching if stale) without
     * looking up a specific game. Equivalent in effect to calling [lookup] with any
     * never-matching appId.
     */
    suspend fun primeCache(context: Context) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        Unit
    }

    private suspend fun ensureLoaded(context: Context): Map<Int, SteamUtils.SdkCloudSaveRecommendation>? {
        // Fast path: in-memory cache populated.
        memoryCache?.let { return it }

        // Slow path: serialize so concurrent callers don't all fetch the 5 MB manifest.
        return loadMutex.withLock {
            // Re-check after acquiring the lock; another caller may have populated while we waited.
            memoryCache?.let { return@withLock it }

            val cacheFile = File(context.filesDir, CACHE_FILE)
            if (cacheFile.isFile && (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_TTL_MS) {
                runCatching { parseCacheJson(cacheFile.readText()) }
                    .onSuccess { parsed ->
                        memoryCache = parsed
                        Timber.i("LudusaviRegistry: loaded ${parsed.size} Pattern B entries from disk cache")
                        return@withLock parsed
                    }
                    .onFailure { Timber.w(it, "LudusaviRegistry: disk cache parse failed; will refetch") }
            }

            val fetched = fetchAndFilter()
            // Treat an empty parse as failure: guards against silent schema drift in the
            // upstream manifest poisoning both caches with a no-data result for CACHE_TTL_MS.
            if (fetched.isNullOrEmpty()) {
                if (fetched != null) {
                    Timber.w("LudusaviRegistry: parser returned 0 entries; refusing to cache (likely schema drift)")
                }
                if (cacheFile.isFile) {
                    runCatching { parseCacheJson(cacheFile.readText()) }
                        .onSuccess {
                            // Mirror the empty-fetch guard above: a 0-entry disk cache is a
                            // poisoned/stale artifact (e.g. a `{}` file from before this guard
                            // shipped). Promoting it to memoryCache would let the fast path
                            // short-circuit forever. Delete the file so a future fetch can
                            // replace it, and fall through to return null.
                            if (it.isEmpty()) {
                                Timber.w("LudusaviRegistry: disk cache parsed to 0 entries; deleting poisoned file")
                                runCatching { cacheFile.delete() }
                            } else {
                                memoryCache = it
                                Timber.w("LudusaviRegistry: using stale disk cache (${it.size} entries) after fetch failure")
                                return@withLock it
                            }
                        }
                }
                return@withLock null
            }

            runCatching {
                cacheFile.writeText(serializeCacheJson(fetched))
            }.onFailure { Timber.w(it, "LudusaviRegistry: failed to write disk cache") }
            memoryCache = fetched
            Timber.i("LudusaviRegistry: fetched ${fetched.size} Pattern B entries from upstream manifest")
            fetched
        }
    }

    private fun fetchAndFilter(): Map<Int, SteamUtils.SdkCloudSaveRecommendation>? {
        // No explicit Accept-Encoding: OkHttp handles gzip transparently when we don't
        // set the header ourselves. Manually setting it disables auto-decompress and
        // hands us raw gzipped bytes, which then fail YAML parse with 0x1F at offset 0.
        val request = Request.Builder()
            .url(MANIFEST_URL)
            .build()

        val body = runCatching {
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("LudusaviRegistry: manifest fetch HTTP ${response.code}")
                    return null
                }
                response.body?.string()
            }
        }.getOrElse {
            Timber.w(it, "LudusaviRegistry: manifest fetch failed")
            return null
        } ?: return null

        return runCatching { parseManifestYaml(body) }.getOrElse {
            Timber.w(it, "LudusaviRegistry: YAML parse failed")
            null
        }
    }

    /**
     * Streaming line-based parser. SnakeYAML's eager Map<String, Any?> load OOMs on the
     * 5 MB manifest with ~5000 game entries on a 512 MB Dalvik heap. We only need a flat
     * `appId -> subdir` map — finalize each game as we encounter the next top-level key
     * so memory stays O(1) per game.
     *
     * Indentation contract (consistent in Ludusavi's manifest):
     *  - column 0: game name, ends with `:`
     *  - column 2: section (`files`, `steam`, `installDir`, ...)
     *  - column 4: path key under `files`, or property like `id` under `steam`
     *  - column 6: property under a file path (`tags`, `when`)
     *  - column 8: list item (`- save`, `- os: windows`)
     */
    private fun parseManifestYaml(yamlText: String): Map<Int, SteamUtils.SdkCloudSaveRecommendation> {
        val out = mutableMapOf<Int, SteamUtils.SdkCloudSaveRecommendation>()

        var gameName: String? = null
        var steamId: Int? = null
        var saveSubdir: String? = null

        var section: String? = null
        var subSection: String? = null

        var currentPath: String? = null
        var currentPathHasSaveTag = false
        var currentPathAppliesWindows = true

        fun finalizePath() {
            val path = currentPath
            if (path != null && saveSubdir == null && currentPathHasSaveTag && currentPathAppliesWindows) {
                if (path.startsWith("<base>/")) {
                    val first = path.removePrefix("<base>/").replace('\\', '/').substringBefore('/')
                    if (first.isNotEmpty() && !first.contains('*') &&
                        SteamUtils.isValidSdkCloudSubdir(first)) {
                        saveSubdir = first
                    }
                }
            }
            currentPath = null
            currentPathHasSaveTag = false
            currentPathAppliesWindows = true
            subSection = null
        }

        fun finalizeGame() {
            finalizePath()
            val name = gameName
            val id = steamId
            val sub = saveSubdir
            if (name != null && id != null && id > 0 && sub != null) {
                out[id] = SteamUtils.SdkCloudSaveRecommendation(
                    appId = id,
                    subdir = sub,
                    name = name,
                    notes = "From Ludusavi manifest",
                )
            }
            gameName = null
            steamId = null
            saveSubdir = null
            section = null
        }

        yamlText.lineSequence().forEach { rawLine ->
            if (rawLine.isBlank()) return@forEach
            val indent = rawLine.takeWhile { it == ' ' }.length
            val content = rawLine.drop(indent)
            if (content.startsWith("#")) return@forEach

            when (indent) {
                0 -> {
                    if (content.endsWith(":") && !content.startsWith("_")) {
                        finalizeGame()
                        gameName = content.removeSuffix(":").trim('"', '\'')
                    }
                }
                2 -> {
                    finalizePath()
                    section = content.substringBefore(':').trim('"', '\'')
                }
                4 -> {
                    if (section == "files" && content.endsWith(":")) {
                        finalizePath()
                        currentPath = content.removeSuffix(":").trim('"', '\'')
                    } else if (section == "steam") {
                        val match = STEAM_ID_REGEX.find(content)
                        if (match != null) steamId = match.groupValues[1].toIntOrNull()
                    }
                }
                6 -> {
                    if (currentPath != null) {
                        subSection = content.substringBefore(':').trim('"', '\'')
                        if (subSection == "when") {
                            // Explicit constraints present — start with no-OS-matched and
                            // flip to true only when we see windows.
                            currentPathAppliesWindows = false
                        }
                    }
                }
                8 -> {
                    if (currentPath != null) {
                        when (subSection) {
                            "tags" -> {
                                val tag = content.removePrefix("-").trim().trim('"', '\'')
                                if (tag.equals("save", ignoreCase = true)) {
                                    currentPathHasSaveTag = true
                                }
                            }
                            "when" -> {
                                val match = OS_REGEX.find(content)
                                if (match != null && match.groupValues[1].equals("windows", ignoreCase = true)) {
                                    currentPathAppliesWindows = true
                                }
                            }
                        }
                    }
                }
                // Deeper indents (e.g. continuation of a `when` entry) are ignored — we
                // already captured what we need at indent 8.
            }
        }
        finalizeGame()
        return out
    }

    private val STEAM_ID_REGEX = Regex("""id\s*:\s*(\d+)""")
    private val OS_REGEX = Regex("""os\s*:\s*(\w+)""")

    private fun parseCacheJson(text: String): Map<Int, SteamUtils.SdkCloudSaveRecommendation> {
        val root = JSONObject(text)
        val games = root.optJSONArray("games") ?: return emptyMap()
        val out = mutableMapOf<Int, SteamUtils.SdkCloudSaveRecommendation>()
        for (i in 0 until games.length()) {
            val entry = games.optJSONObject(i) ?: continue
            val appId = entry.optInt("appId", 0)
            val subdir = entry.optString("subdir", "")
            if (appId <= 0 || subdir.isEmpty()) continue
            out[appId] = SteamUtils.SdkCloudSaveRecommendation(
                appId = appId,
                subdir = subdir,
                name = entry.optString("name", ""),
                notes = entry.optString("notes", ""),
            )
        }
        return out
    }

    private fun serializeCacheJson(entries: Map<Int, SteamUtils.SdkCloudSaveRecommendation>): String {
        val games = JSONArray()
        for ((_, rec) in entries.entries.sortedBy { it.key }) {
            games.put(JSONObject().apply {
                put("appId", rec.appId)
                put("subdir", rec.subdir)
                put("name", rec.name)
                put("notes", rec.notes)
            })
        }
        return JSONObject().apply {
            put("source", MANIFEST_URL)
            put("fetchedAtMs", System.currentTimeMillis())
            put("games", games)
        }.toString()
    }
}
