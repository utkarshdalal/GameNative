package app.gamenative.service.gog

import org.json.JSONException
import org.json.JSONObject

/**
 * Parses a single `account/getFilteredProducts` page fetched with `hiddenFlag=1`, where every
 * returned product is hidden on GOG.
 *
 * Malformed JSON or structurally invalid pages throw instead of silently producing an empty hidden
 * set, so pagination in [GOGApiClient.getHiddenGameIds] can stay all-or-nothing.
 */
object GogFilteredProductsParser {

    /** One response page: hidden product IDs plus the total page count (0 = no hidden games). */
    data class Page(
        val hiddenProductIds: Set<String>,
        val totalPages: Int,
    )

    /**
     * Parses one `hiddenFlag=1` page. Every product ID is a hidden product ID. A product ID must be
     * present and non-blank (number or string), otherwise the page is rejected. `totalPages` may be
     * 0, which is a valid empty hidden set.
     */
    fun parseHiddenPage(rawJson: String): Page {
        val root = try {
            JSONObject(rawJson)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed getFilteredProducts response", e)
        }

        val products = root.optJSONArray("products")
            ?: throw IllegalArgumentException("getFilteredProducts response is missing products")
        val totalPages = root.optInt("totalPages", -1)
        if (totalPages < 0) {
            throw IllegalArgumentException("getFilteredProducts response has invalid totalPages: $totalPages")
        }

        val hiddenProductIds = buildSet {
            for (i in 0 until products.length()) {
                val product = products.optJSONObject(i)
                    ?: throw IllegalArgumentException("getFilteredProducts product $i is not an object")
                val id = when (val rawId = product.opt("id")) {
                    null -> throw IllegalArgumentException("getFilteredProducts product $i is missing id")
                    is Number -> rawId.toString()
                    is String -> rawId.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("getFilteredProducts product $i has a blank id")
                    else -> throw IllegalArgumentException("getFilteredProducts product $i has an invalid id")
                }
                add(id)
            }
        }

        return Page(hiddenProductIds = hiddenProductIds, totalPages = totalPages)
    }
}
