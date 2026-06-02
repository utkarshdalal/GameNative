package app.gamenative.html5.shim

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

// shared Goldberg-shape on-disk writers used by both the pre-launch seed (Html5AchievementSeed)
// and the live bridge (SteamworksJsBridge). both must produce byte-identical files because
// AchievementWatcher reads them the same way the Wine path does -- no parallel plumbing.
object GoldbergSaveFiles {

    // achievements.json: {name: {earned: true, earned_time?: <unix>}} for earned entries only.
    // atomic-rename idiom -- POSIX rename(2) is atomic, FileObserver fires MOVED_TO once with a
    // complete file (a half-written file would JSONException the watcher).
    fun writeAchievementsJsonAtomic(gseDir: File, ach: Map<String, Boolean>, times: Map<String, Long>) {
        gseDir.mkdirs()
        val json = JSONObject()
        ach.forEach { (name, earned) ->
            if (earned) {
                json.put(
                    name,
                    JSONObject().apply {
                        put("earned", true)
                        times[name]?.let { put("earned_time", it) }
                    },
                )
            }
        }
        val tmp = File(gseDir, "achievements.json.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        tmp.renameTo(File(gseDir, "achievements.json"))
    }

    // stats/<lowercased-name>: 4-byte LE int32 or float32 per schema type. filename lowercased to
    // match SteamService's reader (statNameToId lookup). atomic-rename for the same reason as above.
    fun writeStatFileAtomic(gseDir: File, name: String, value: Number, type: String) {
        // name comes from untrusted game JS via SteamworksJsBridge.setStat and becomes a
        // filename -- reject anything that could escape statsDir. Steam stat names are
        // [A-Za-z0-9_]; a separator or ".." here would be a path-traversal write primitive.
        require(name.isNotEmpty() && name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }) {
            "illegal stat name: $name"
        }
        val statsDir = File(gseDir, "stats").also { it.mkdirs() }
        val lower = name.lowercase()
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).run {
            when (type) {
                "float", "avgrate" -> putFloat(value.toFloat())
                else -> putInt(value.toInt())
            }
            array()
        }
        val tmp = File(statsDir, "$lower.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(File(statsDir, lower))
    }
}
