package app.gamenative.online

import android.content.Context
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Fetches Steam store categories/genres so Online install can appear for titles that are
 * not yet in the seeded matrix (e.g. Bogos Binted, How to Fish).
 *
 * Results are cached in memory + SharedPreferences to keep library filtering cheap.
 */
object OnlineStoreSignals {
    private const val PREFS = "gamenative_online_store_signals"
    private const val KEY_PREFIX = "tags_"
    private const val NEG_PREFIX = "neg_"
    private const val NEGATIVE_TTL_MS = 6L * 60 * 60 * 1000

    private val memory = ConcurrentHashMap<String, List<String>>()
    private val http by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .build()
    }

    fun cachedTags(context: Context, steamAppId: Int): List<String>? {
        val key = steamAppId.toString()
        memory[key]?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PREFIX + key, null) ?: return null
        val tags = raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        memory[key] = tags
        return tags
    }

    fun putCache(context: Context, steamAppId: Int, tags: List<String>) {
        val key = steamAppId.toString()
        memory[key] = tags
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + key, tags.joinToString("|"))
            .remove(NEG_PREFIX + key)
            .apply()
    }

    /**
     * Returns store category/genre labels for [steamAppId], fetching from Steam when needed.
     * Empty list means "looked up, no online markers found" (cached as empty).
     */
    suspend fun tagsForSteamApp(context: Context, steamAppId: Int): List<String> =
        withContext(Dispatchers.IO) {
            if (steamAppId <= 0) return@withContext emptyList()
            cachedTags(context, steamAppId)?.let { return@withContext it }

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val negAt = prefs.getLong(NEG_PREFIX + steamAppId, 0L)
            if (negAt > 0L && System.currentTimeMillis() - negAt < NEGATIVE_TTL_MS) {
                return@withContext emptyList()
            }

            val fetched = runCatching { fetchSteamCategories(steamAppId) }
                .onFailure { Timber.w(it, "Online store signal fetch failed for %s", steamAppId) }
                .getOrNull()

            if (fetched == null) {
                prefs.edit().putLong(NEG_PREFIX + steamAppId.toString(), System.currentTimeMillis()).apply()
                return@withContext emptyList()
            }

            putCache(context, steamAppId, fetched)
            fetched
        }

    private fun fetchSteamCategories(steamAppId: Int): List<String> {
        // Prefer a light filter; fall back to full appdetails if categories are omitted.
        val urls = listOf(
            "https://store.steampowered.com/api/appdetails?appids=$steamAppId&filters=categories,genres&cc=us&l=english",
            "https://store.steampowered.com/api/appdetails?appids=$steamAppId&cc=us&l=english",
        )
        for (url in urls) {
            val labels = requestCategoryLabels(url, steamAppId)
            if (labels.isNotEmpty()) return labels
        }
        return emptyList()
    }

    private fun requestCategoryLabels(url: String, steamAppId: Int): List<String> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val root = JSONObject(body)
            val entry = root.optJSONObject(steamAppId.toString()) ?: return emptyList()
            if (!entry.optBoolean("success", false)) return emptyList()
            val data = entry.optJSONObject("data") ?: return emptyList()
            val labels = mutableListOf<String>()
            data.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("description")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(labels::add)
                }
            }
            data.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("description")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(labels::add)
                }
            }
            return labels.distinctBy { it.lowercase(Locale.ROOT) }
        }
    }
}
