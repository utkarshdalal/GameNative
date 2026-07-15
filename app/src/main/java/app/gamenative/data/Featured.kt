package app.gamenative.data

import android.content.Context
import app.gamenative.R
import kotlinx.serialization.Serializable

/** Response of POST /api/games/hero: the static recommendation plus an optional featured. */
@Serializable
data class HeroResponse(
    val recommendation: RecommendedGame? = null,
    val featured: FeaturedItem? = null,
)

@Serializable
data class FeaturedItem(
    val campaignId: String,
    val title: String,
    val developer: String? = null,
    val heroImageUrl: String = "",
    val capsuleImageUrl: String? = null,
    val iconUrl: String? = null,
    val videoUrl: String? = null,
    val screenshots: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val status: String? = null,
    // Advertiser-supplied locale -> localized copy; resolved on-device.
    val description: Map<String, String> = emptyMap(),
    val actions: List<FeaturedAction> = emptyList(),
    val startsAt: String? = null,
    val endsAt: String? = null,
)

@Serializable
data class FeaturedAction(
    val type: String,
    val url: String,
    val store: String? = null,
    val style: String? = null,
    // Only for type CUSTOM: advertiser-supplied locale -> label map.
    val label: Map<String, String> = emptyMap(),
)

private fun deviceLanguage(context: Context): String =
    context.resources.configuration.locales[0].language

private fun Map<String, String>.forLocale(context: Context): String? =
    this[deviceLanguage(context)] ?: this["en"] ?: values.firstOrNull()

fun FeaturedItem.localizedDescription(context: Context): String =
    description.forLocale(context) ?: ""

/**
 * Known action types get a client-owned, fully-translated label (interpolating the store name);
 * CUSTOM actions fall back to the advertiser-supplied localized label map.
 */
fun FeaturedAction.localizedLabel(context: Context): String = when (type.uppercase()) {
    "WISHLIST" -> store?.let { context.getString(R.string.featured_action_wishlist_on, it) }
        ?: context.getString(R.string.featured_action_wishlist)
    "PREORDER" -> store?.let { context.getString(R.string.featured_action_preorder_on, it) }
        ?: context.getString(R.string.featured_action_preorder)
    "BUY" -> store?.let { context.getString(R.string.featured_action_buy_on, it) }
        ?: context.getString(R.string.featured_action_buy)
    "NOTIFY" -> context.getString(R.string.featured_action_notify)
    "VISIT" -> context.getString(R.string.featured_action_visit)
    else -> label.forLocale(context) ?: context.getString(R.string.featured_action_visit)
}

/** Flatten a featured into the RecommendedGame the detail screen already knows how to render. */
fun FeaturedItem.toRecommendedGame(context: Context): RecommendedGame = RecommendedGame(
    id = campaignId,
    name = title,
    developer = developer ?: "",
    description = localizedDescription(context),
    heroImageUrl = heroImageUrl,
    capsuleImageUrl = capsuleImageUrl ?: heroImageUrl,
    iconUrl = iconUrl,
    videoUrl = videoUrl,
    releaseDate = null,
    reviewScore = null,
    reviewCount = null,
    affiliateUrl = actions.firstOrNull()?.url ?: "",
    tags = tags,
    screenshots = screenshots,
    videos = listOfNotNull(videoUrl),
    becausePlayed = null,
    becauseGames = emptyList(),
    isFeatured = true,
    featuredStatus = status,
    featuredCtas = actions.mapIndexed { index, a ->
        FeaturedCta(
            label = a.localizedLabel(context),
            url = a.url,
            primary = a.style?.equals("primary", ignoreCase = true) ?: (index == 0),
        )
    },
)
