package app.gamenative.service.amazon

import org.json.JSONObject

/** Shared hero resolution from Amazon product / entitlement JSON (`productDetail.details`). */
object AmazonArtwork {

    /** Layout / library grid: crown first, then store backgrounds. */
    const val GRID_HERO_ZOOM_SCALE = 1.15f

    fun detailsFromProductJson(productJson: String): JSONObject? {
        if (productJson.isEmpty()) return null
        return try {
            JSONObject(productJson)
                .optJSONObject("productDetail")
                ?.optJSONObject("details")
        } catch (_: Exception) {
            null
        }
    }

    fun resolveLayoutHeroUrl(details: JSONObject?): String {
        val crown = details?.optString("pgCrownImageUrl", "") ?: ""
        if (crown.isNotEmpty()) return crown
        val bg1 = details?.optString("backgroundUrl1", "") ?: ""
        if (bg1.isNotEmpty()) return bg1
        val bg2 = details?.optString("backgroundUrl2", "") ?: ""
        if (bg2.isNotEmpty()) return bg2
        return ""
    }

    /** App detail backdrop: backgrounds only (no crown). */
    fun resolveAppHeroUrl(details: JSONObject?): String {
        val bg1 = details?.optString("backgroundUrl1", "") ?: ""
        if (bg1.isNotEmpty()) return bg1
        val bg2 = details?.optString("backgroundUrl2", "") ?: ""
        if (bg2.isNotEmpty()) return bg2
        return ""
    }

    fun layoutHeroFromProductJson(productJson: String): String =
        resolveLayoutHeroUrl(detailsFromProductJson(productJson))

    fun appHeroFromProductJson(productJson: String): String =
        resolveAppHeroUrl(detailsFromProductJson(productJson))
}
