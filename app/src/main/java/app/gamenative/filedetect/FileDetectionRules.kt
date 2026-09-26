package app.gamenative.filedetect

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import java.io.File
import java.security.MessageDigest
import okhttp3.Request
import timber.log.Timber

object FileDetectionRules {

    private const val RULES_URL = "https://raw.githubusercontent.com/SteamDatabase/FileDetectionRuleSets/main/rules.ini"
    private const val ASSET_PATH = "filedetect/rules.ini"
    private const val CACHE_DIR = "filedetect"
    private const val CACHE_FILE = "rules.ini"
    private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    private class Loaded(val text: String, val version: String, val ruleSet: RuleSet)

    @Volatile
    private var loaded: Loaded? = null

    fun rulesVersion(context: Context): String = get(context).version

    fun ruleSet(context: Context): RuleSet = get(context).ruleSet

    fun versionOf(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 12)
    }

    @Synchronized
    private fun get(context: Context): Loaded {
        loaded?.let { return it }
        val cache = cacheFile(context)
        val fromCache = if (cache.exists()) {
            runCatching { load(cache.readText()) }
                .onFailure { Timber.tag("FileDetect").w(it, "Cached rules unusable, falling back to bundled") }
                .getOrNull()
        } else null
        val result = fromCache ?: load(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() })
        loaded = result
        return result
    }

    private fun load(text: String): Loaded = Loaded(text, versionOf(text), RuleSet.parse(text))

    private fun cacheFile(context: Context) = File(File(context.filesDir, CACHE_DIR), CACHE_FILE)

    fun refreshIfStale(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val last = PrefManager.fileDetectionRulesFetchedAt
            if (last in (now - REFRESH_INTERVAL_MS)..now) return
            val request = Request.Builder().url(RULES_URL).build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("FileDetect").w("Rules fetch failed: HTTP ${response.code}")
                    return
                }
                val text = response.body?.string() ?: return
                val fresh = load(text)
                if (fresh.ruleSet.rules.isEmpty()) {
                    Timber.tag("FileDetect").w("Fetched rules parsed to zero rules, ignoring")
                    return
                }
                val cache = cacheFile(context)
                cache.parentFile?.mkdirs()
                cache.writeText(text)
                synchronized(this) { loaded = fresh }
                PrefManager.fileDetectionRulesFetchedAt = now
                Timber.tag("FileDetect").i("Rules refreshed to ${fresh.version} (${fresh.ruleSet.rules.size} rules)")
            }
        } catch (e: Exception) {
            Timber.tag("FileDetect").w(e, "Rules refresh failed")
        }
    }
}
