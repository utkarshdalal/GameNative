package app.gamenative.data

import kotlinx.serialization.Serializable

@Serializable
data class RecommendedGame(
    val id: String,
    val name: String,
    val developer: String,
    val description: String,
    val heroImageUrl: String,
    val capsuleImageUrl: String,
    val iconUrl: String? = null,
    val videoUrl: String? = null,
    val releaseDate: String? = null,
    val reviewScore: Int? = null,
    val reviewCount: Int? = null,
    val affiliateUrl: String,
    val tags: List<String> = emptyList(),
)
