package app.gamenative.data.gog

import android.content.Context
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
    private const val MAX_SEEDS = 8
    private const val PER_SEED_LIMIT = 20
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: List<GogRecCard>? = null

    @Volatile
    private var cacheAt: Long = 0

    private data class Seed(val gogId: String, val name: String, val weight: Double)

    suspend fun getRecommendations(
        context: Context,
        owned: List<OwnedGameRef>,
        userId: String?,
        forceRefresh: Boolean = false,
    ): List<GogRecCard> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cache?.let {
                if (System.currentTimeMillis() - cacheAt in 0..CACHE_TTL_MS) return@withContext it
            }
        }

        val map = GogMapRepository.getMap(context) ?: return@withContext emptyList()
        val seeds = selectSeeds(map, owned)
        if (seeds.isEmpty()) return@withContext emptyList()

        val perSeed = coroutineScope {
            seeds.map { seed ->
                async { seed to fetchStrategy("purchased_together", seed.gogId, userId) }
            }.awaitAll()
        }

        val ownedGogIds = seeds.map { it.gogId }.toHashSet()
        val agg = HashMap<Long, Aggregate>()
        for ((seed, products) in perSeed) {
            for (p in products) {
                val d = p.details ?: continue
                if (!d.isAvailable || d.storeUrl.isBlank()) continue
                if (ownedGogIds.contains(p.productId.toString())) continue
                val a = agg.getOrPut(p.productId) { Aggregate(p) }
                a.score += seed.weight * p.rating
                a.seeds.add(seed.name)
            }
        }

        val cards = agg.values.sortedByDescending { it.score }.map { it.toCard() }
        cache = cards
        cacheAt = System.currentTimeMillis()
        cards
    }

    private fun selectSeeds(map: GogMap, owned: List<OwnedGameRef>): List<Seed> {
        data class Candidate(val gogId: String, val name: String, val playtime: Long, val lastPlayed: Long)

        return owned.mapNotNull { ref ->
            val gogId = ref.gogId
                ?: ref.steamAppId?.let { GogMapRepository.steamGogId(map, it) }
                ?: ref.epicNamespace?.let { GogMapRepository.epicGogId(map, it) }
                ?: GogMapRepository.titleGogId(map, ref.name)
                ?: return@mapNotNull null
            Candidate(gogId, ref.name, ref.playtime, ref.lastPlayed)
        }
            .groupBy { it.gogId }
            .map { (_, list) -> list.maxWithOrNull(compareBy({ it.lastPlayed }, { it.playtime }))!! }
            .sortedWith(compareByDescending<Candidate> { it.lastPlayed }.thenByDescending { it.playtime })
            .take(MAX_SEEDS)
            .mapIndexed { index, c -> Seed(c.gogId, c.name, weight = (MAX_SEEDS - index).toDouble()) }
    }

    private fun fetchStrategy(strategy: String, gogId: String, userId: String?): List<GogRecProduct> {
        return try {
            val url = buildString {
                append("$REC_BASE/$strategy/$gogId?country_code=US&currency=USD&limit=$PER_SEED_LIMIT")
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
        val seeds = LinkedHashSet<String>()

        fun toCard(): GogRecCard {
            val d = product.details!!
            val price = product.pricing?.price
            val priceLabel = price?.let { formatCents(it.finalPrice) }
            val discountLabel = price?.let {
                if (it.basePrice > it.finalPrice && it.basePrice > 0) {
                    "-${100 - (it.finalPrice * 100 / it.basePrice)}%"
                } else {
                    null
                }
            }
            val seedList = seeds.toList()
            val because = when {
                seedList.isEmpty() -> ""
                seedList.size == 1 -> "Because you played ${seedList[0]}"
                else -> "Because you played ${seedList[0]} & ${seedList.size - 1} more"
            }
            return GogRecCard(
                productId = product.productId,
                title = d.title,
                imageUrl = d.imageHorizontalUrl.ifBlank { d.imageUrl },
                storeUrl = d.storeUrl,
                affiliateUrl = CJ_CLICK + URLEncoder.encode(d.storeUrl, "UTF-8"),
                priceLabel = priceLabel,
                discountLabel = discountLabel,
                becausePlayed = because,
                score = score,
            )
        }
    }

    private fun formatCents(cents: Int): String {
        if (cents <= 0) return "Free"
        return "$" + String.format("%.2f", cents / 100.0)
    }
}
