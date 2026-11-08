package app.gamenative.optimize

import app.gamenative.ui.screen.support.WineRegistryText
import org.json.JSONObject

/** Which keys of a kept lever no longer hold what Optimize wrote, because the game saved over them. */
internal object OptimizeDrift {

    /** The ops an engine_file [record] wrote; records kept before they listed them fall back to the rung's [lever]. */
    fun writtenOps(record: JSONObject, lever: Lever?): List<FileOp>? {
        val keys = record.optJSONArray("keys").objects()
        if (keys.isEmpty()) return null
        if (keys.all { it.has("op") }) {
            return keys.map { k ->
                FileOp(k.getString("op"), k.optString("section"), k.getString("key"), if (k.isNull("value")) null else k.optString("value"))
            }
        }
        val ops = (lever as? Lever.EngineFile)?.ops ?: return null
        return keys.mapNotNull { k -> ops.lastOrNull { it.section == k.optString("section") && it.key == k.optString("key") } }
            .takeIf { it.isNotEmpty() }
    }

    /** The [written] ops whose key in [text] no longer holds the written value, or is back after a delete. */
    fun fileDrift(format: ConfigFormat, text: String, written: List<FileOp>): List<FileOp> = written.filter { op ->
        val now = if (text.isBlank()) null else format.read(text, op)
        if (op.isDelete) now != null else now != op.value
    }

    /** Names of the registry values a registry [record] wrote whose data in [hiveText] differs now. */
    fun registryDrift(hiveText: String, record: JSONObject): List<String> {
        val values = record.optJSONArray("values").objects()
        if (values.isEmpty()) return emptyList()
        val names = values.map { it.getString("name") }
        val now = WineRegistryText.read(hiveText, record.getString("key"), names)
        return values.filter { v ->
            val applied = if (v.isNull("applied")) null else v.optString("applied")
            now[v.getString("name")]?.let { WineRegistryText.canonical(it) } != applied
        }.map { it.getString("name") }
    }
}
