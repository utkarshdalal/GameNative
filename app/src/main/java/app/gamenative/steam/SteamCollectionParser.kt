package app.gamenative.steam

import app.gamenative.data.SteamCollection
import org.json.JSONObject
import timber.log.Timber

object SteamCollectionParser {
    private const val KEY_PREFIX = "user-collections."

    data class RawEntry(val key: String, val value: String, val isDeleted: Boolean)
    data class ParseResult(val collections: List<SteamCollection>, val skippedDynamicCount: Int)

    fun parse(entries: List<RawEntry>): ParseResult {
        val collections = mutableListOf<SteamCollection>()
        var skippedDynamic = 0
        for (e in entries) {
            if (!e.key.startsWith(KEY_PREFIX) || e.isDeleted) continue
            try {
                val json = JSONObject(e.value)
                val added = json.optJSONArray("added")
                // Static collections carry an explicit "added" array. Dynamic ones use "filterSpec".
                if (added == null) {
                    if (json.has("filterSpec")) skippedDynamic++
                    continue
                }
                val appIds = buildSet { for (i in 0 until added.length()) add(added.getInt(i)) }
                // optString returns the literal "null" for a JSON-null value, so treat that (and blank) as absent.
                val id = json.optString("id").takeUnless { it.isBlank() || it == "null" }
                    ?: e.key.removePrefix(KEY_PREFIX)
                if (id.isBlank()) continue
                val name = json.optString("name").takeUnless { it.isBlank() || it == "null" } ?: id
                collections.add(SteamCollection(id = id, name = name, appIds = appIds))
            } catch (t: Throwable) {
                Timber.tag("SteamCollectionParser").w(t, "Skipping malformed collection entry ${e.key}")
            }
        }
        return ParseResult(collections, skippedDynamic)
    }
}
