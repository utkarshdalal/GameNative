package app.gamenative.online

import android.content.Context
import org.json.JSONObject
import timber.log.Timber

/**
 * Loads the fork's shipped online compatibility matrix from assets.
 */
object OnlineMatrixRepository {
    private const val ASSET_PATH = "online_matrix.json"

    @Volatile
    private var cache: Map<String, OnlineGameInfo>? = null

    fun get(context: Context, appId: String, store: String = "steam"): OnlineGameInfo? {
        return load(context)["$store:$appId"]
    }

    fun load(context: Context): Map<String, OnlineGameInfo> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = runCatching {
                context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                    parse(reader.readText())
                }
            }.onFailure {
                Timber.w(it, "Failed to load $ASSET_PATH")
            }.getOrDefault(emptyMap())
            cache = loaded
            return loaded
        }
    }

    internal fun parse(json: String): Map<String, OnlineGameInfo> {
        val root = JSONObject(json)
        val entries = root.optJSONArray("entries") ?: return emptyMap()
        val result = LinkedHashMap<String, OnlineGameInfo>()
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            val appId = obj.optString("appId").trim()
            if (appId.isEmpty()) continue
            val store = obj.optString("store", "steam").ifBlank { "steam" }
            val status = OnlineStatus.fromStorageKey(obj.optString("status"))
                ?: OnlineStatus.UNTESTED_ONLINE
            val capability = when (status) {
                OnlineStatus.ONLINE_BLOCKED -> OnlineCapability.BLOCKED
                OnlineStatus.OFFLINE_ONLY -> OnlineCapability.NONE
                else -> OnlineCapability.HAS_ONLINE
            }
            val info = OnlineGameInfo(
                appId = appId,
                store = store,
                capability = capability,
                status = status,
                notes = obj.optString("notes").takeIf { it.isNotBlank() },
            )
            result["$store:$appId"] = info
        }
        return result
    }
}
