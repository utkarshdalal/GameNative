package app.gamenative.service.gog

import org.json.JSONException
import org.json.JSONObject

/** Parses one unfiltered `account/getFilteredProducts` response page. */
object GogFilteredProductsParser {

    /** Product observations and the server-reported number of pages. */
    data class Page(
        val observations: Map<String, Boolean>,
        val totalPages: Int,
    )

    /**
     * Parses one unfiltered page. Every product must provide an explicit Boolean `isHidden` value;
     * missing, null, string, and numeric values are rejected. Duplicate IDs are accepted only when
     * they carry the same observation.
     */
    fun parsePage(rawJson: String): Page {
        val root = try {
            JSONObject(rawJson)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed getFilteredProducts response", e)
        }

        val products = root.optJSONArray("products")
            ?: throw IllegalArgumentException("getFilteredProducts response is missing products")
        val totalPages = parseTotalPages(root.opt("totalPages"))
        val observations = buildMap {
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
                val isHidden = product.opt("isHidden") as? Boolean
                    ?: throw IllegalArgumentException(
                        "getFilteredProducts product $i has an invalid isHidden value",
                    )
                val previous = this[id]
                if (previous != null && previous != isHidden) {
                    throw IllegalArgumentException(
                        "getFilteredProducts response has conflicting observations for id $id",
                    )
                }
                put(id, isHidden)
            }
        }

        return Page(observations = observations, totalPages = totalPages)
    }

    /** Validates and returns the total page count from a gog.com response. */
    private fun parseTotalPages(rawValue: Any?): Int {
        val rawNumber = rawValue as? Number
            ?: throw IllegalArgumentException("getFilteredProducts response has invalid totalPages")
        val value = rawNumber.toDouble()
        if (!value.isFinite() || value < 0 || value > Int.MAX_VALUE || value % 1.0 != 0.0) {
            throw IllegalArgumentException("getFilteredProducts response has invalid totalPages")
        }
        return value.toInt()
    }
}
