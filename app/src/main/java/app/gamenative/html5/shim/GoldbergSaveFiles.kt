package app.gamenative.html5.shim

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

// shared by Html5AchievementSeed and SteamworksJsBridge: output must match the wine path's
// Goldberg files because AchievementWatcher reads both the same way.
object GoldbergSaveFiles {

    // earned entries only. write-then-rename so FileObserver sees one MOVED_TO with a complete
    // file -- a half-written file would JSONException the watcher.
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

    // 4-byte LE int32/float32. filename lowercased to match SteamService's statNameToId reader.
    fun writeStatFileAtomic(gseDir: File, name: String, value: Number, type: String) {
        // SECURITY: name comes from untrusted game JS and becomes a filename. Steam stat names are
        // [A-Za-z0-9_]; anything else could be a path-traversal write.
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
