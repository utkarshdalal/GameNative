package app.gamenative.theme

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ThemeCompatStatus { VERIFIED, INCOMPATIBLE, UNVERIFIED }

/** How store themes look in GameNative, from the bundled `theme_compat.json`. */
class ThemeCompatTable(
    val themes: Map<String, ThemeCompatStatus> = emptyMap(),
    val incompatibleTargets: Set<String> = emptySet(),
) {
    /** An explicit id entry wins; otherwise a theme whose targets are all incompatible is incompatible. */
    fun statusOf(theme: StoreTheme): ThemeCompatStatus {
        themes[theme.id]?.let { return it }
        val targets = (listOfNotNull(theme.target) + theme.targets)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        return if (targets.isNotEmpty() && targets.all { it in incompatibleTargets }) {
            ThemeCompatStatus.INCOMPATIBLE
        } else {
            ThemeCompatStatus.UNVERIFIED
        }
    }
}

object ThemeCompat {
    private const val ASSET = "theme_compat.json"

    @Volatile
    private var cached: ThemeCompatTable? = null

    suspend fun load(context: Context): ThemeCompatTable =
        cached ?: withContext(Dispatchers.IO) {
            val text = runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()
            (text?.let(::parse) ?: ThemeCompatTable()).also { cached = it }
        }

    fun parse(json: String): ThemeCompatTable {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return ThemeCompatTable()
        val themes = (root["themes"] as? JsonObject)?.mapNotNull { (id, value) ->
            val status = when ((value as? JsonPrimitive)?.contentOrNull) {
                "verified" -> ThemeCompatStatus.VERIFIED
                "incompatible" -> ThemeCompatStatus.INCOMPATIBLE
                else -> null
            }
            status?.let { id to it }
        }?.toMap() ?: emptyMap()
        val targets = (root["incompatibleTargets"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()
        return ThemeCompatTable(themes, targets)
    }
}
