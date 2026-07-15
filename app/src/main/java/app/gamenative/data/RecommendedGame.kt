package app.gamenative.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    val screenshots: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val becausePlayed: String? = null,
    val becauseGames: List<String> = emptyList(),
    // Populated only when this card is a server-driven featured (see FeaturedItem.kt).
    @Transient val isFeatured: Boolean = false,
    @Transient val featuredStatus: String? = null,
    @Transient val featuredCtas: List<FeaturedCta> = emptyList(),
)

/** A resolved (localized) featured call-to-action shown on the detail screen. */
data class FeaturedCta(
    val label: String,
    val url: String,
    val primary: Boolean = false,
)
