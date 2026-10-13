package app.gamenative.data

import app.gamenative.data.gog.GogMapRepository
import app.gamenative.utils.Net
import app.gamenative.utils.SteamUtils
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Loads the richer, public metadata exposed by supported storefronts when a game page is opened.
 * Failures are intentionally non-fatal: the UI keeps showing metadata already synced into the library.
 */
object StoreDetailsRepository {
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
    private const val MAX_CACHE_ENTRIES = 256
    private const val TAG = "StoreDetails"

    private data class CacheEntry(
        val loadedAt: Long,
        val details: StoreGameDetails,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val httpClient: OkHttpClient by lazy {
        Net.http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getDetails(
        libraryItem: LibraryItem,
        fallback: StoreGameDetails = StoreGameDetails(),
        locale: Locale = Locale.getDefault(),
    ): StoreGameDetails = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val localDetails = fallback.withoutTitleOnlyDescription(libraryItem.name)
        val localeKey = when (libraryItem.gameSource) {
            GameSource.GOG -> gogLocaleForAppLocale(locale)
            GameSource.STEAM,
            GameSource.EPIC,
            GameSource.AMAZON,
            -> SteamUtils.steamLanguageForAppLocale(locale)
            GameSource.CUSTOM_GAME -> "none"
        }
        val cacheKey = buildString {
            append(libraryItem.appId)
            append(':')
            append(localeKey)
            if (libraryItem.gameSource == GameSource.EPIC || libraryItem.gameSource == GameSource.AMAZON) {
                append(':')
                append(GogMapRepository.normalizeTitle(libraryItem.name))
            }
        }
        cache[cacheKey]
            ?.takeIf { now - it.loadedAt in 0..CACHE_TTL_MS }
            ?.let {
                return@withContext mergeStoreDetails(
                    source = libraryItem.gameSource,
                    fetched = it.details,
                    local = localDetails,
                )
            }

        cache.entries.forEach { (key, entry) ->
            if (now - entry.loadedAt !in 0..CACHE_TTL_MS) cache.remove(key, entry)
        }

        val fetched = try {
            when (libraryItem.gameSource) {
                GameSource.STEAM -> fetchSteam(libraryItem.gameId, locale)
                GameSource.GOG -> fetchGog(
                    productId = libraryItem.gameId,
                    title = libraryItem.name,
                    locale = locale,
                    localeTag = localeKey,
                )
                GameSource.EPIC,
                GameSource.AMAZON,
                -> fetchSteamSupplement(libraryItem.name, locale)
                GameSource.CUSTOM_GAME -> StoreGameDetails()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Store metadata fetch failed for ${libraryItem.appId}")
            StoreGameDetails()
        }

        if (fetched.hasContent) {
            if (cache.size >= MAX_CACHE_ENTRIES) {
                cache.entries.minByOrNull { it.value.loadedAt }
                    ?.let { cache.remove(it.key, it.value) }
            }
            cache[cacheKey] = CacheEntry(now, fetched)
        }
        mergeStoreDetails(
            source = libraryItem.gameSource,
            fetched = fetched,
            local = localDetails,
        )
    }

    private suspend fun fetchSteam(appId: Int, locale: Locale): StoreGameDetails = coroutineScope {
        if (appId <= 0) return@coroutineScope StoreGameDetails()
        val language = SteamUtils.steamLanguageForAppLocale(locale)
        val detailsRequest = async {
            getBody("https://store.steampowered.com/api/appdetails?appids=$appId&cc=us&l=$language")
        }
        val reviewsRequest = async {
            getBody(
                "https://store.steampowered.com/appreviews/$appId" +
                    "?json=1&language=all&purchase_type=all&num_per_page=0&l=$language",
            )
        }
        parseSteamStoreDetails(
            appId = appId,
            detailsJson = detailsRequest.await(),
            reviewsJson = reviewsRequest.await(),
        )
    }

    private suspend fun fetchGog(
        productId: Int,
        title: String,
        locale: Locale,
        localeTag: String,
    ): StoreGameDetails = coroutineScope {
        if (productId <= 0) return@coroutineScope StoreGameDetails()
        val detailsRequest = async {
            getBody("https://api.gog.com/products/$productId?expand=description,videos&locale=$localeTag")
        }
        val reviewsRequest = async {
            getBody("https://reviews.gog.com/v1/products/$productId/averageRating")
        }
        val tagsRequest = async {
            getBody("https://api.gog.com/v2/games/$productId?locale=$localeTag")
        }
        val detailsJson = detailsRequest.await()
        val reviewsJson = reviewsRequest.await()
        val tagsJson = tagsRequest.await()
        var details = parseGogStoreDetails(
            detailsJson = detailsJson,
            reviewsJson = reviewsJson,
            tagsJson = tagsJson,
        )
        var descriptionProductId = productId

        // Some retired/hidden GOG SKUs expose untranslated resource keys and point to the
        // current parent product. Use that product only to fill gaps in the owned SKU's data.
        val includedInProductId = parseGogIncludedInProductId(productId, tagsJson)
        if (includedInProductId != null) {
            val parentDetailsRequest = async {
                getBody(
                    "https://api.gog.com/products/$includedInProductId" +
                        "?expand=description,videos&locale=$localeTag",
                )
            }
            val parentTagsRequest = async {
                getBody("https://api.gog.com/v2/games/$includedInProductId?locale=$localeTag")
            }
            val parentReviewsRequest = async {
                getBody("https://reviews.gog.com/v1/products/$includedInProductId/averageRating")
            }
            val parentDetails = parseGogStoreDetails(
                detailsJson = parentDetailsRequest.await(),
                reviewsJson = parentReviewsRequest.await(),
                tagsJson = parentTagsRequest.await(),
            )
            if (details.description.isBlank() && parentDetails.description.isNotBlank()) {
                descriptionProductId = includedInProductId
            }
            details = details.mergedWith(parentDetails)
        }

        if (locale.language.equals("en", ignoreCase = true)) {
            return@coroutineScope details
        }

        // GOG localizes its storefront chrome and tags into more languages than the publisher
        // descriptions themselves. Detect that English fallback before consulting another store.
        val englishDescription = parseGogStoreDetails(
            detailsJson = getBody(
                "https://api.gog.com/products/$descriptionProductId" +
                    "?expand=description&locale=en-US",
            ),
            reviewsJson = null,
            tagsJson = null,
        ).description
        if (!shouldUseGogDescriptionFallback(details.description, englishDescription)) {
            return@coroutineScope details
        }

        val localizedSupplement = fetchSteamSupplement(
            title = title,
            locale = locale,
            allowReplacementBundleMatch = includedInProductId != null,
        )
        details.copy(
            description = localizedSupplement.description.ifBlank { details.description },
        )
    }

    /**
     * Epic and Amazon catalogs expose very limited gallery data. Resolve an exact title match on
     * Steam and use its public description, tags, trailers, and screenshots only as supplemental
     * presentation metadata. Reviews and links are intentionally omitted because they belong to a
     * different storefront.
     */
    private suspend fun fetchSteamSupplement(
        title: String,
        locale: Locale,
        allowReplacementBundleMatch: Boolean = false,
    ): StoreGameDetails {
        val language = SteamUtils.steamLanguageForAppLocale(locale)
        val appId = searchSteamAppId(title, language, allowReplacementBundleMatch)
            ?: return StoreGameDetails()
        val details = parseSteamStoreDetails(
            appId = appId,
            detailsJson = getBody(
                "https://store.steampowered.com/api/appdetails?appids=$appId&cc=us&l=$language",
            ),
            reviewsJson = null,
        )
        return details.copy(
            reviewPercentage = null,
            reviewCount = null,
            reviewSummary = null,
        )
    }

    private fun searchSteamAppId(
        title: String,
        language: String,
        allowReplacementBundleMatch: Boolean = false,
    ): Int? {
        if (title.isBlank()) return null
        val encodedTitle = URLEncoder.encode(title, Charsets.UTF_8.name())
        return parseSteamSearchAppId(
            title = title,
            searchJson = getBody(
                "https://store.steampowered.com/api/storesearch/" +
                    "?term=$encodedTitle&cc=us&l=$language",
            ),
            allowReplacementBundleMatch = allowReplacementBundleMatch,
        )
    }

    private fun getBody(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GameNative Android")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).d("Store request returned ${response.code}: $url")
                    null
                } else {
                    response.body?.string()?.removePrefix("\uFEFF")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Store request failed: $url")
            null
        }
    }
}

private fun storeLocaleTag(locale: Locale): String = locale.stripExtensions()
    .toLanguageTag()
    .takeUnless { it.isBlank() || it == "und" }
    ?: "en-US"

/** GOG requires one of its canonical regional locale tags; language-only tags fall back to English. */
internal fun gogLocaleForAppLocale(locale: Locale): String = when (locale.language.lowercase(Locale.ROOT)) {
    "da" -> "da-DK"
    "de" -> "de-DE"
    "en" -> "en-US"
    "es" -> "es-ES"
    "fr" -> "fr-FR"
    "it" -> "it-IT"
    "ja" -> "ja-JP"
    "ko" -> "ko-KR"
    "pl" -> "pl-PL"
    "pt" -> "pt-BR"
    "ro" -> "ro-RO"
    "ru" -> "ru-RU"
    "uk" -> "uk-UA"
    "zh" -> if (
        locale.script.equals("Hant", ignoreCase = true) ||
        locale.country.uppercase(Locale.ROOT) in setOf("HK", "MO", "TW")
    ) {
        "zh-Hant"
    } else {
        "zh-Hans"
    }
    else -> storeLocaleTag(locale)
}

private fun mergeStoreDetails(
    source: GameSource,
    fetched: StoreGameDetails,
    local: StoreGameDetails,
): StoreGameDetails = when (source) {
    GameSource.EPIC,
    GameSource.AMAZON,
    -> local.mergedWith(fetched)
    else -> fetched.mergedWith(local)
}

internal fun parseSteamSearchAppId(
    title: String,
    searchJson: String?,
    allowReplacementBundleMatch: Boolean = false,
): Int? {
    val normalizedTitle = GogMapRepository.normalizeTitle(title)
    if (normalizedTitle.isBlank()) return null
    val items = parseObject(searchJson)
        ?.optJSONArray("items")
        .objects()
    items.firstOrNull {
        GogMapRepository.normalizeTitle(it.optString("name")) == normalizedTitle
    }?.let { return it.optInt("id", 0).takeIf { appId -> appId > 0 } }

    if (!allowReplacementBundleMatch) return null
    val firstWord = normalizedTitle.substringBefore(' ')
    return items.firstOrNull {
        val candidate = GogMapRepository.normalizeTitle(it.optString("name"))
        candidate.startsWith("$firstWord ") && candidate.endsWith(" $normalizedTitle")
    }
        ?.optInt("id", 0)
        ?.takeIf { it > 0 }
}

internal fun StoreGameDetails.withoutTitleOnlyDescription(title: String): StoreGameDetails {
    if (description.isBlank()) return this
    val normalizedDescription = GogMapRepository.normalizeTitle(description)
    val normalizedTitle = GogMapRepository.normalizeTitle(title)
    return if (normalizedDescription.isNotBlank() && normalizedDescription == normalizedTitle) {
        copy(description = "")
    } else {
        this
    }
}

private val GOG_LOCALIZATION_KEY = Regex(
    pattern = "\\bproduct_(?:description|feature)_\\d+\\b",
    option = RegexOption.IGNORE_CASE,
)

internal fun sanitizeGogDescription(description: String): String = stripStoreHtml(description)
    .replace(GOG_LOCALIZATION_KEY, "")
    .replace(Regex(" *\\n+ *"), "\n")
    .trim()

internal fun shouldUseGogDescriptionFallback(
    localizedDescription: String,
    englishDescription: String,
): Boolean {
    if (localizedDescription.isBlank()) return true
    if (englishDescription.isBlank()) return false
    return localizedDescription.replace(Regex("\\s+"), " ").trim().equals(
        englishDescription.replace(Regex("\\s+"), " ").trim(),
        ignoreCase = true,
    )
}

internal fun parseGogIncludedInProductId(productId: Int, gameJson: String?): Int? =
    parseObject(gameJson)
        ?.optJSONObject("_links")
        ?.optJSONArray("isIncludedInGames")
        .objects()
        .asSequence()
        .map { it.optInt("id", 0) }
        .firstOrNull { it > 0 && it != productId }

internal fun parseSteamStoreDetails(
    appId: Int,
    detailsJson: String?,
    reviewsJson: String?,
): StoreGameDetails {
    val data = parseObject(detailsJson)
        ?.optJSONObject(appId.toString())
        ?.takeIf { it.optBoolean("success", false) }
        ?.optJSONObject("data")

    val description = data?.let { steamData ->
        sequenceOf(
            steamData.optString("about_the_game"),
            steamData.optString("detailed_description"),
            steamData.optString("short_description"),
        ).map(::stripStoreHtml).firstOrNull(String::isNotBlank).orEmpty()
    }.orEmpty()

    val screenshots = data?.optJSONArray("screenshots").objects()
        .mapNotNull { it.optString("path_full").asHttpUrl() }

    val videos = data?.optJSONArray("movies").objects().mapNotNull { movie ->
        movie.optJSONObject("mp4")?.optString("max").asHttpUrl()
            ?: movie.optJSONObject("webm")?.optString("max").asHttpUrl()
            ?: movie.optString("hls_h264").asHttpUrl()
    }

    val genres = data?.optJSONArray("genres").objects()
        .map { it.optString("description") }
    val categories = data?.optJSONArray("categories").objects()
        .map { it.optString("description") }

    val reviewSummary = parseObject(reviewsJson)?.optJSONObject("query_summary")
    val reviewCount = reviewSummary?.optInt("total_reviews", 0)?.takeIf { it > 0 }
    val positiveCount = reviewSummary?.optInt("total_positive", 0) ?: 0
    val reviewPercentage = reviewCount?.let { (positiveCount * 100.0 / it).roundToInt().coerceIn(0, 100) }

    return StoreGameDetails(
        description = description,
        reviewPercentage = reviewPercentage,
        reviewCount = reviewCount,
        reviewSummary = reviewSummary?.optString("review_score_desc")?.takeIf(String::isNotBlank),
        tags = (genres + categories).cleanStoreList(16),
        screenshots = screenshots.cleanStoreList(12),
        videos = videos.cleanStoreList(4),
    )
}

internal fun parseGogStoreDetails(
    detailsJson: String?,
    reviewsJson: String?,
    tagsJson: String?,
): StoreGameDetails {
    val details = parseObject(detailsJson)
    val game = parseObject(tagsJson)
    val descriptionObject = details?.optJSONObject("description")
    val description = sequenceOf(
        descriptionObject?.optString("full"),
        descriptionObject?.optString("lead"),
    ).filterNotNull().map(::sanitizeGogDescription).firstOrNull(String::isNotBlank).orEmpty()

    val screenshots = buildList<Pair<String, String>> {
        addAll(details?.optJSONArray("screenshots").objects().mapNotNull { screenshot ->
            screenshot.optString("formatter_template_url")
                .normalizeProtocolUrl()
                ?.let { it to "ggvgl_2x" }
        })
        addAll(
            game?.optJSONObject("_embedded")
                ?.optJSONArray("screenshots")
                .objects()
                .mapNotNull { screenshot ->
                    screenshot.optJSONObject("_links")
                        ?.optJSONObject("self")
                        ?.optString("href")
                        ?.normalizeProtocolUrl()
                        ?.let { it to "1600" }
                },
        )
    }.distinctBy { it.first.lowercase(Locale.ROOT) }
        .map { (template, formatter) -> template.replace("{formatter}", formatter) }
        .ifEmpty {
            listOfNotNull(
                details?.optJSONObject("images")
                    ?.optString("background")
                    ?.normalizeProtocolUrl(),
            )
        }
    val videos = details?.optJSONArray("videos").objects()
        .mapNotNull { it.optString("video_url").normalizeProtocolUrl() }

    val rating = parseObject(reviewsJson)
    val ratingCount = rating?.optInt("count", 0)?.takeIf { it > 0 }
    val ratingValue = ratingCount?.let {
        rating.optDouble("value", Double.NaN)
            .takeUnless(Double::isNaN)
            ?.let { value -> (value * 20.0).roundToInt().coerceIn(0, 100) }
    }

    val tags = game
        ?.optJSONObject("_embedded")
        ?.optJSONArray("tags")
        .objects()
        .map { it.optString("name") }

    return StoreGameDetails(
        description = description,
        reviewPercentage = ratingValue,
        reviewCount = ratingCount,
        tags = tags.cleanStoreList(16),
        screenshots = screenshots.cleanStoreList(12),
        videos = videos.cleanStoreList(4),
    )
}

/** Extracts any rich catalog fields present in Amazon's already-synced product payload. */
internal fun parseAmazonStoreDetails(productJson: String?): StoreGameDetails {
    val product = parseObject(productJson) ?: return StoreGameDetails()
    val details = product.optJSONObject("productDetail")?.optJSONObject("details")

    val description = sequenceOf(
        details?.optString("description"),
        details?.optString("longDescription"),
        details?.optString("shortDescription"),
        product.optString("description"),
    ).filterNotNull().map(::stripStoreHtml).firstOrNull(String::isNotBlank).orEmpty()

    val tags = buildList {
        listOf("genres", "categories", "features").forEach { key ->
            addAll(details?.optJSONArray(key).labels())
            addAll(product.optJSONArray(key).labels())
        }
    }
    val screenshots = buildList {
        listOf("screenshots", "images").forEach { key ->
            addAll(details?.optJSONArray(key).urls())
            addAll(product.optJSONArray(key).urls())
        }
        details?.optString("backgroundUrl1").asHttpUrl()?.let(::add)
        details?.optString("backgroundUrl2").asHttpUrl()?.let(::add)
    }
    val videos = buildList {
        listOf("videos", "trailers").forEach { key ->
            addAll(details?.optJSONArray(key).urls())
            addAll(product.optJSONArray(key).urls())
        }
    }

    return StoreGameDetails(
        description = description,
        tags = tags.cleanStoreList(16),
        screenshots = screenshots.cleanStoreList(12),
        videos = videos.cleanStoreList(4),
    )
}

private fun parseObject(value: String?): JSONObject? = value
    ?.takeIf(String::isNotBlank)
    ?.let { runCatching { JSONObject(it) }.getOrNull() }

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}

private fun JSONArray?.labels(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is String -> add(value)
                is JSONObject -> sequenceOf(
                    value.optString("name"),
                    value.optString("title"),
                    value.optString("label"),
                ).firstOrNull(String::isNotBlank)?.let(::add)
            }
        }
    }
}

private fun JSONArray?.urls(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is String -> value.asHttpUrl()?.let(::add)
                is JSONObject -> sequenceOf(
                    value.optString("videoUrl"),
                    value.optString("imageUrl"),
                    value.optString("full"),
                    value.optString("url"),
                ).firstNotNullOfOrNull { it.asHttpUrl() }?.let(::add)
            }
        }
    }
}

private fun String?.asHttpUrl(): String? = this
    ?.trim()
    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

private fun String.normalizeProtocolUrl(): String? {
    val value = trim()
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("https://") || value.startsWith("http://") -> value
        else -> null
    }
}

private fun List<String>.cleanStoreList(limit: Int): List<String> = asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase(Locale.ROOT) }
    .take(limit)
    .toList()

internal fun stripStoreHtml(html: String): String {
    if (html.isBlank()) return ""
    return html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(?:p|div|li|h[1-6])>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex(" *\\n+ *"), "\n")
        .trim()
}
