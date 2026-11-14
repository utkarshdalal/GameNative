package app.gamenative.html5.shim

import android.content.Context
import java.io.File
import org.json.JSONObject
import timber.log.Timber

// remembers each stat's schema type so an offline cold start can decode the stat files: they are
// 4 raw bytes with NO type tag, and reading a float as int32 hands the game its bit pattern and
// makes the next setStat write int bits over what Steam holds as a float.
// deliberately NOT in the GSE save dir -- that tree is cloud-synced, and this is not a save.
object Html5StatTypeCache {

    private const val TAG = "Html5StatTypeCache"

    fun write(context: Context, appId: Int, types: Map<String, String>) {
        if (types.isEmpty()) return
        runCatching {
            val file = fileFor(context, appId).also { it.parentFile?.mkdirs() }
            val obj = JSONObject()
            // lowercase to match the stat file names (GoldbergSaveFiles).
            types.forEach { (name, type) -> obj.put(name.lowercase(), type) }
            file.writeText(obj.toString())
        }.onFailure { Timber.tag(TAG).w(it, "stat type cache write failed appId=%d", appId) }
    }

    // never throws: a missing or corrupt cache must degrade to "no hints", not break seeding.
    fun read(context: Context, appId: Int): Map<String, String> = runCatching {
        val file = fileFor(context, appId)
        if (!file.isFile) return@runCatching emptyMap()
        val obj = JSONObject(file.readText())
        buildMap {
            obj.keys().forEach { key -> put(key.lowercase(), obj.optString(key, "int")) }
        }
    }.onFailure { Timber.tag(TAG).w(it, "stat type cache read failed appId=%d", appId) }
        .getOrDefault(emptyMap())

    internal fun fileFor(context: Context, appId: Int): File =
        File(File(context.filesDir, "html5/stat-types"), "$appId.json")
}
