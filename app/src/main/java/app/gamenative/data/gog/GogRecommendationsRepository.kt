package app.gamenative.data.gog

import android.content.Context
import app.gamenative.data.RecommendedGame
import app.gamenative.utils.Net
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber

/**
 * Turns the user's owned/played library into GOG seed games and generates recommendations from
 * GOG's recommendations-api, merged + ranked across seeds with "Because you played X" attribution.
 * The GOG store links are wrapped in the CJ affiliate deep link.
 */
object GogRecommendationsRepository {

    private const val REC_BASE = "https://recommendations-api.gog.com/v1/recommendations"
    private const val CJ_CLICK = "https://www.anrdoezrs.net/click-101723120-15554897?url="
    private const val FIXED_SEEDS = 12
    private const val ROTATING_SEEDS = 6
    private const val ROTATING_WEIGHT = 6.0
    private const val PER_SEED_LIMIT = 30
    private const val MAX_CARDS = 200
    private const val HERO_POOL = 10
    private const val RATING_NONE = -1
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    private val STRATEGIES = listOf("purchased_together", "similar")

    private val LOCALE_COUNTRY: String = java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    private val LOCALE_CURRENCY: String = runCatching {
        java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode
    }.getOrDefault("USD")
    private val CURRENCY_SYMBOL: String = runCatching {
        java.util.Currency.getInstance(LOCALE_CURRENCY).symbol
    }.getOrDefault("$")

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: List<GogRecCard>? = null

    @Volatile
    private var cacheAt: Long = 0

    @Volatile
    private var cacheDay: Long = -1

    private data class Seed(val gogId: String, val name: String, val weight: Double, val iconUrl: String?)

    suspend fun getRecommendations(
        context: Context,
        owned: List<OwnedGameRef>,
        userId: String?,
        daySeed: Long,
        forceRefresh: Boolean = false,
    ): List<GogRecCard> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cache?.let {
                if (cacheDay == daySeed && System.currentTimeMillis() - cacheAt in 0..CACHE_TTL_MS) return@withContext it
            }
        }

        val map = GogMapRepository.getMap(context) ?: return@withContext emptyList()
        val seeds = selectSeeds(map, owned, daySeed)
        if (seeds.isEmpty()) return@withContext emptyList()

        val ownedGogIds = owned.mapNotNull { resolveGogId(map, it) }.toHashSet()
        val ownedTitles = owned.asSequence()
            .map { GogMapRepository.normalizeTitle(it.name) }
            .filter { it.isNotBlank() }
            .toHashSet()

        val perSeed = coroutineScope {
            seeds.flatMap { seed ->
                STRATEGIES.map { strategy ->
                    async { seed to fetchStrategy(strategy, seed.gogId, userId) }
                }
            }.awaitAll()
        }

        val agg = HashMap<Long, Aggregate>()
        for ((seed, products) in perSeed) {
            for (p in products) {
                val d = p.details ?: continue
                if (!d.isAvailable || d.storeUrl.isBlank()) continue
                if (ownedGogIds.contains(p.productId.toString())) continue
                if (ownedTitles.contains(GogMapRepository.normalizeTitle(d.title))) continue
                val a = agg.getOrPut(p.productId) { Aggregate(p) }
                a.score += seed.weight * p.rating
                if (!a.seeds.containsKey(seed.name)) a.seeds[seed.name] = seed.iconUrl
            }
        }

        val cards = agg.values.sortedByDescending { it.score }.map { it.toCard() }.take(MAX_CARDS)
        cache = cards
        cacheAt = System.currentTimeMillis()
        cacheDay = daySeed
        cards
    }

    private val ratingCache = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    suspend fun getRating(productId: Long): Int? = withContext(Dispatchers.IO) {
        ratingCache[productId]?.let { return@withContext it.takeIf { r -> r != RATING_NONE } }
        val rating = fetchAverageRating(productId)?.let { Math.round(it.value * 20).toInt() }
        ratingCache[productId] = rating ?: RATING_NONE
        rating
    }

    /**
     * Builds a full [RecommendedGame] for a tapped recommendation, enriched with description /
     * hero image / release date from api.gog.com. Returns null if the id isn't a cached GOG rec
     * (so the caller can fall back to the daily recommendation).
     */
    suspend fun getRecommendedGame(productId: String): RecommendedGame? = withContext(Dispatchers.IO) {
        val id = productId.toLongOrNull() ?: return@withContext null
        val card = cache?.firstOrNull { it.productId == id } ?: return@withContext null

        coroutineScope {
            val detailD = async { fetchProductDetail(id) }
            val ratingD = async { fetchAverageRating(id) }
            val v2D = async { fetchV2Game(id) }
            val summaryD = async { fetchGamesdbSummary(id) }
            val steamD = async { fetchSteamMedia(id, card.title) }
            val detail = detailD.await()
            val rating = ratingD.await()
            val v2 = v2D.await()
            val gdbSummary = summaryD.await()
            val steam = steamD.await()

            val heroImage = detail?.images?.background
                ?.let { if (it.startsWith("//")) "https:$it" else it }
                ?: card.heroImage
            val htmlDesc = detail?.description?.let { it.full.ifBlank { it.lead } }.orEmpty()
            val description = stripHtml(gdbSummary?.takeIf { it.isNotBlank() } ?: htmlDesc)
            val gogVideos = detail?.videos.orEmpty().map { it.videoUrl }.filter { it.isNotBlank() }
            val gogScreenshots = detail?.screenshots.orEmpty()
                .map { it.formatterTemplateUrl }
                .filter { it.isNotBlank() }
                .map { it.replace("{formatter}", "ggvgl_2x") }
            val videos = steam?.videoUrl?.let { listOf(it) } ?: gogVideos
            val screenshots = steam?.screenshots?.takeIf { it.isNotEmpty() } ?: gogScreenshots
            val developer = v2?.embedded?.developers?.firstOrNull()?.name.orEmpty()
            val tags = v2?.embedded?.tags.orEmpty().map { it.name }.filter { it.isNotBlank() }

            RecommendedGame(
                id = productId,
                name = card.title,
                developer = developer,
                description = description,
                heroImageUrl = heroImage,
                capsuleImageUrl = card.capsuleImage,
                iconUrl = null,
                videoUrl = videos.firstOrNull(),
                releaseDate = detail?.releaseDate?.substringBefore("T"),
                reviewScore = rating?.let { Math.round(it.value * 20).toInt() },
                reviewCount = rating?.count,
                affiliateUrl = card.affiliateUrl,
                tags = tags,
                screenshots = screenshots,
                videos = videos,
                becausePlayed = card.becausePlayed.takeIf { it.isNotBlank() },
                becauseGames = card.seedNames,
            )
        }
    }

    suspend fun getDailyHero(
        context: Context,
        owned: List<OwnedGameRef>,
        userId: String?,
        daySeed: Long,
    ): RecommendedGame? = withContext(Dispatchers.IO) {
        val cards = getRecommendations(context, owned, userId, daySeed)
        if (cards.isEmpty()) return@withContext null
        val pool = minOf(HERO_POOL, cards.size)
        val index = daySeed.mod(pool.toLong()).toInt()
        getRecommendedGame(cards[index].productId.toString())
    }

    private fun fetchProductDetail(productId: Long): GogProductDetail? =
        getJson("https://api.gog.com/products/$productId?expand=description,videos")

    private fun fetchAverageRating(productId: Long): GogAverageRating? =
        getJson("https://reviews.gog.com/v1/products/$productId/averageRating")

    private fun fetchV2Game(productId: Long): GogV2Game? =
        getJson("https://api.gog.com/v2/games/$productId?locale=en-US")

    private data class SteamMedia(val videoUrl: String?, val screenshots: List<String>)

    private fun searchSteamAppId(title: String): String? {
        return try {
            val term = URLEncoder.encode(title, "UTF-8")
            val request = Request.Builder()
                .url("https://store.steampowered.com/api/storesearch/?term=$term&cc=us&l=english")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = (response.body?.string() ?: return null).removePrefix("﻿")
                val items = json.decodeFromString<SteamSearchResponse>(body).items
                val norm = GogMapRepository.normalizeTitle(title)
                items.firstOrNull { GogMapRepository.normalizeTitle(it.name) == norm }?.id?.toString()
            }
        } catch (e: Exception) {
            Timber.tag("GogRec").w(e, "Steam search failed for $title")
            null
        }
    }

    private fun fetchSteamMedia(productId: Long, title: String): SteamMedia? {
        val appId = GogMapRepository.steamAppIdForGog(productId.toString())
            ?: searchSteamAppId(title)
            ?: return null
        return try {
            val request = Request.Builder()
                .url("https://store.steampowered.com/api/appdetails?appids=$appId&cc=us&l=english")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = (response.body?.string() ?: return null).removePrefix("﻿")
                val data = json.decodeFromString<Map<String, SteamAppEnvelope>>(body)[appId]
                    ?.takeIf { it.success }?.data ?: return null
                val video = data.movies.firstOrNull()?.hls?.takeIf { it.isNotBlank() }
                val screenshots = data.screenshots.map { it.pathFull }.filter { it.isNotBlank() }
                if (video == null && screenshots.isEmpty()) null else SteamMedia(video, screenshots)
            }
        } catch (e: Exception) {
            Timber.tag("GogRec").w(e, "Steam media fetch failed for appId $appId")
            null
        }
    }

    private fun fetchGamesdbSummary(productId: Long): String? {
        val summary = getJson<GamesdbNode>(
            "https://gamesdb.gog.com/platforms/gog/external_releases/$productId",
        )?.game?.summary ?: return null
        return summary.enUS.ifBlank { summary.fallback }.takeIf { it.isNotBlank() }
    }

    private inline fun <reified T> getJson(url: String): T? {
        return try {
            val request = Request.Builder().url(url).build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.decodeFromString<T>(body)
            }
        } catch (e: Exception) {
            Timber.tag("GogRec").w(e, "Fetch failed: $url")
            null
        }
    }

    private fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun resolveGogId(map: GogMap, ref: OwnedGameRef): String? =
        ref.gogId
            ?: ref.steamAppId?.let { GogMapRepository.steamGogId(map, it) }
            ?: ref.epicNamespace?.let { GogMapRepository.epicGogId(map, it) }
            ?: GogMapRepository.titleGogId(map, ref.name)

    private fun selectSeeds(map: GogMap, owned: List<OwnedGameRef>, daySeed: Long): List<Seed> {
        data class Candidate(val gogId: String, val name: String, val playtime: Long, val lastPlayed: Long, val iconUrl: String?)

        val ranked = owned.mapNotNull { ref ->
            val gogId = resolveGogId(map, ref) ?: return@mapNotNull null
            Candidate(gogId, ref.name, ref.playtime, ref.lastPlayed, ref.iconUrl)
        }
            .groupBy { it.gogId }
            .map { (_, list) -> list.maxWithOrNull(compareBy({ it.lastPlayed }, { it.playtime }))!! }
            .sortedWith(compareByDescending<Candidate> { it.lastPlayed }.thenByDescending { it.playtime })

        val fixed = ranked.take(FIXED_SEEDS)
            .mapIndexed { index, c -> Seed(c.gogId, c.name, weight = (FIXED_SEEDS - index).toDouble(), iconUrl = c.iconUrl) }
        val rotating = ranked.drop(FIXED_SEEDS)
            .shuffled(java.util.Random(daySeed))
            .take(ROTATING_SEEDS)
            .map { c -> Seed(c.gogId, c.name, weight = ROTATING_WEIGHT, iconUrl = c.iconUrl) }
        return fixed + rotating
    }

    private fun fetchStrategy(strategy: String, gogId: String, userId: String?): List<GogRecProduct> {
        return try {
            val url = buildString {
                append("$REC_BASE/$strategy/$gogId?country_code=$LOCALE_COUNTRY&currency=$LOCALE_CURRENCY&limit=$PER_SEED_LIMIT")
                if (!userId.isNullOrBlank()) append("&user_id=$userId")
            }
            val request = Request.Builder().url(url).build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                json.decodeFromString<GogRecResponse>(body).products
            }
        } catch (e: Exception) {
            Timber.tag("GogRec").w(e, "Strategy $strategy failed for $gogId")
            emptyList()
        }
    }

    private class Aggregate(private val product: GogRecProduct) {
        var score = 0.0
        val seeds = LinkedHashMap<String, String?>()

        fun toCard(): GogRecCard {
            val d = product.details!!
            val price = product.pricing?.price
            val priceLabel = price?.let { formatCents(it.finalPrice) }
            val discounted = price != null && price.basePrice > price.finalPrice && price.basePrice > 0
            val basePriceLabel = if (discounted) formatCents(price!!.basePrice) else null
            val discountLabel = if (discounted) {
                "-${100 - (price!!.finalPrice * 100 / price.basePrice)}%"
            } else {
                null
            }
            val seedList = seeds.keys.toList()
            val because = when {
                seedList.isEmpty() -> ""
                seedList.size == 1 -> "Because you played ${seedList[0]}"
                else -> "Because you played ${seedList[0]} & ${seedList.size - 1} more"
            }
            return GogRecCard(
                productId = product.productId,
                title = d.title,
                capsuleImage = d.imageUrl.ifBlank { d.imageHorizontalUrl },
                heroImage = d.imageHorizontalUrl.ifBlank { d.imageUrl },
                storeUrl = d.storeUrl,
                affiliateUrl = CJ_CLICK + URLEncoder.encode(d.storeUrl, "UTF-8"),
                priceLabel = priceLabel,
                basePriceLabel = basePriceLabel,
                discountLabel = discountLabel,
                becausePlayed = because,
                score = score,
                seedCount = seedList.size,
                seedIconUrl = seeds.values.firstOrNull { !it.isNullOrBlank() },
                seedNames = seedList,
            )
        }
    }

    private fun formatCents(cents: Int): String {
        if (cents <= 0) return "Free"
        return CURRENCY_SYMBOL + String.format("%.2f", cents / 100.0)
    }
}
