package app.gamenative.data.gog

import kotlinx.serialization.Serializable

@Serializable
data class GogMap(
    val version: String = "",
    val games: Map<String, GogGameEntry> = emptyMap(),
    val steam: Map<String, String> = emptyMap(),
    val epic: Map<String, String> = emptyMap(),
    val title: Map<String, String> = emptyMap(),
)

@Serializable
data class GogGameEntry(
    val gogId: String = "",
    val slug: String = "",
    val title: String = "",
    val url: String = "",
    val image: String? = null,
    val tags: List<String>? = null,
)
