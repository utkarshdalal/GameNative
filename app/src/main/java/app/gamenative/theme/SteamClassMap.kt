package app.gamenative.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Any historical or current Steam class name -> readable `module_Name`.
 * [names] is the reduced DeckThemes class map; names missing from it are decoded from the
 * old `module_Name_hash` form, and opaque hashes stay unmapped.
 */
class SteamClassMap(val names: Map<String, String> = emptyMap()) {

    enum class How { GLOBAL, MAPPED, REGEX, OPAQUE_UNMAPPED }

    fun readable(cls: String): Pair<String, How> {
        if (!cls.contains('_') && cls.length < 17) return cls to How.GLOBAL
        names[cls]?.let { return it to How.MAPPED }
        HASHED.matchEntire(cls)?.let { return "${it.groupValues[1]}_${it.groupValues[2]}" to How.REGEX }
        if (isOpaque(cls)) return cls to How.OPAQUE_UNMAPPED
        return cls to How.GLOBAL
    }

    fun toJson(): String = buildJsonObject { names.forEach { (k, v) -> put(k, v) } }.toString()

    companion object {
        val EMPTY = SteamClassMap()

        private val HASHED = Regex("^([a-z0-9]+)_([A-Za-z0-9_-]+?)_([A-Za-z0-9_-]{5})$")
        private val OPAQUE = Regex("^_?[A-Za-z0-9_-]{17,26}$")
        private val READABLE = Regex("^([a-z0-9]+)_([A-Za-z0-9_-]+)$")
        private val DIGIT = Regex("\\d")

        fun isOpaque(name: String): Boolean =
            OPAQUE.matches(name) && DIGIT.containsMatchIn(name) && !HASHED.matches(name)

        fun fromJson(text: String): SteamClassMap {
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: return EMPTY
            val m = HashMap<String, String>(obj.size * 2)
            for ((k, v) in obj) (v as? JsonPrimitive)?.contentOrNull?.let { m[k] = it }
            return SteamClassMap(m)
        }

        /**
         * Reduces DeckThemes' `stable.json` ({uid: [every name the class has had]}) to the names whose
         * readable form the translator can use.
         */
        fun reduceStableJson(text: String): SteamClassMap {
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: return EMPTY
            val lists = HashMap<String, List<String>>(obj.size * 2)
            for ((uid, v) in obj) {
                val arr = v as? JsonArray ?: continue
                lists[uid] = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
            val readableOfUid = HashMap<String, String>()
            val prefixModule = HashMap<String, String>()
            for ((uid, names) in lists) {
                for (x in names) {
                    if (READABLE.matches(x) && !x.endsWith("_") &&
                        names.any { y -> y.startsWith(x + "_") && y.length == x.length + 6 }
                    ) {
                        readableOfUid[uid] = x
                        prefixModule.putIfAbsent(uid.substringBefore('_'), x.substringBefore('_'))
                        break
                    }
                }
            }
            val out = HashMap<String, String>()
            for ((uid, names) in lists) {
                val r = readableOfUid[uid] ?: run {
                    if (!uid.contains('_')) return@run null
                    val mod = prefixModule[uid.substringBefore('_')] ?: return@run null
                    "${mod}_${uid.substringAfter('_')}"
                } ?: continue
                if (SteamClassRoles.roleOf(r) == null && !SteamClassRoles.isExtraInteresting(r)) continue
                for (x in names) {
                    if (x.endsWith("_") || (!x.contains('_') && x.length < 17)) continue
                    out.putIfAbsent(x, r)
                }
            }
            return SteamClassMap(out)
        }
    }
}
