package app.gamenative.utils

import app.gamenative.api.ApiResult
import app.gamenative.api.SupportersApi
import timber.log.Timber

data class KofiSupporter(
    val name: String? = null,
    val oneOff: Boolean? = null,
    val total: Double? = null,
)

/**
 * Fetch supporters from the worker API.
 */
suspend fun fetchKofiSupporters(): List<KofiSupporter> {
    return when (val result = SupportersApi.fetch()) {
        is ApiResult.Success -> result.data.map {
            KofiSupporter(name = it.name, oneOff = it.oneOff, total = it.total)
        }
        is ApiResult.HttpError -> {
            Timber.e("Failed to fetch Ko-fi supporters: HTTP ${result.code} ${result.message}")
            emptyList()
        }
        is ApiResult.NetworkError -> {
            Timber.e(result.exception, "Failed to fetch Ko-fi supporters: network error")
            emptyList()
        }
    }
}
