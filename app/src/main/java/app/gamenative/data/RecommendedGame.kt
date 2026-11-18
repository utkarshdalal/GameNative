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
    val priceLabel: String? = null,
    val tags: List<String> = emptyList(),
    val screenshots: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val becausePlayed: String? = null,
    val becauseGames: List<String> = emptyList(),
    // Landscape cover for a library tile, when it differs from the hero (GOG's horizontal cover).
    val headerImageUrl: String? = null,
    // Full-bleed landscape art for the library backdrop, when the hero is not suited to it
    // (GOG's store background fades to white at the bottom).
    val backdropImageUrl: String? = null,
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
    val type: String = "",
    val appId: Int? = null,
)
