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
)

/** A single recommendation tile, ready for the UI. */
data class GogRecCard(
    val productId: Long,
    val title: String,
    val imageUrl: String,
    val storeUrl: String,
    val affiliateUrl: String,
    val priceLabel: String?,
    val discountLabel: String?,
    val becausePlayed: String,
    val score: Double,
)
