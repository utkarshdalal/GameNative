package app.gamenative.data.gog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GogRecResponse(
    val products: List<GogRecProduct> = emptyList(),
)

@Serializable
data class GogRecProduct(
    @SerialName("product_id") val productId: Long = 0,
    val rating: Double = 0.0,
    val details: GogRecDetails? = null,
    val pricing: GogRecPricing? = null,
)

@Serializable
data class GogRecDetails(
    val title: String = "",
    @SerialName("is_available") val isAvailable: Boolean = true,
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("image_horizontal_url") val imageHorizontalUrl: String = "",
    @SerialName("store_url") val storeUrl: String = "",
)

@Serializable
data class GogRecPricing(
    val price: GogRecPrice? = null,
)

@Serializable
data class GogRecPrice(
    @SerialName("base_price") val basePrice: Int = 0,
    @SerialName("final_price") val finalPrice: Int = 0,
)

@Serializable
data class GogProductDetail(
    @SerialName("release_date") val releaseDate: String? = null,
    val images: GogProductImages? = null,
    val description: GogProductDescription? = null,
    val videos: List<GogProductVideo> = emptyList(),
    val screenshots: List<GogProductScreenshot> = emptyList(),
)

@Serializable
data class GogProductScreenshot(
    @SerialName("formatter_template_url") val formatterTemplateUrl: String = "",
)

@Serializable
data class GogProductImages(
    val background: String? = null,
)

@Serializable
data class GogProductDescription(
    val lead: String = "",
    val full: String = "",
)

@Serializable
data class GogProductVideo(
    @SerialName("video_url") val videoUrl: String = "",
)

@Serializable
data class GogAverageRating(
    val value: Double = 0.0,
    val count: Int = 0,
)

@Serializable
data class GogV2Game(
    @SerialName("_embedded") val embedded: GogV2Embedded? = null,
)

@Serializable
data class GogV2Embedded(
    val developers: List<GogNamedRef> = emptyList(),
    val tags: List<GogNamedRef> = emptyList(),
)

@Serializable
data class GogNamedRef(
    val name: String = "",
)

@Serializable
data class GamesdbNode(
    val game: GamesdbGame? = null,
)

@Serializable
data class GamesdbGame(
    val summary: GamesdbLocalized? = null,
)

@Serializable
data class SteamSearchResponse(
    val items: List<SteamSearchItem> = emptyList(),
)

@Serializable
data class SteamSearchItem(
    val id: Long = 0,
    val name: String = "",
)

@Serializable
data class SteamAppEnvelope(
    val success: Boolean = false,
    val data: SteamAppData? = null,
)

@Serializable
data class SteamAppData(
    val movies: List<SteamMovie> = emptyList(),
    val screenshots: List<SteamScreenshot> = emptyList(),
)

@Serializable
data class SteamMovie(
    @SerialName("hls_h264") val hls: String = "",
)

@Serializable
data class SteamScreenshot(
    @SerialName("path_full") val pathFull: String = "",
)

@Serializable
data class GamesdbLocalized(
    @SerialName("en-US") val enUS: String = "",
    @SerialName("*") val fallback: String = "",
)

/**
 * A game the user owns/played, from any source. Resolved to a GOG seed via (in order) a direct
 * GOG id, Steam appid, Epic namespace, then a normalized-title fallback.
 */
data class OwnedGameRef(
    val name: String,
    val gogId: String? = null,
    val steamAppId: Int? = null,
    val epicNamespace: String? = null,
    val playtime: Long = 0,
    val lastPlayed: Long = 0,
    val iconUrl: String? = null,
)

/** A single recommendation tile, ready for the UI. */
data class GogRecCard(
    val productId: Long,
    val title: String,
    val capsuleImage: String,
    val heroImage: String,
    val storeUrl: String,
    val affiliateUrl: String,
    val priceLabel: String?,
    val basePriceLabel: String?,
    val discountLabel: String?,
    val becausePlayed: String,
    val score: Double,
    val rating: Int? = null,
    val seedCount: Int = 0,
    val seedIconUrl: String? = null,
    val seedNames: List<String> = emptyList(),
)
