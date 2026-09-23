package app.gamenative.data

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

object RecommendationRepository {

    private const val API_URL = "https://api.gamenative.app/api/games/hero"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    private const val MOCK_HERO_RESPONSE = false

    internal val MOCK_HERO_JSON = """
        {
          "recommendation": null,
          "featured": {
            "campaignId": "mock-whisk",
            "title": "Whisk",
            "appId": 3602270,
            "developer": "Double Dusk Inc.",
            "heroImageUrl": "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3602270/92fb97a2832c9c075165c43d14d974c730ca716b/library_hero.jpg",
            "capsuleImageUrl": "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3602270/435512f90bdf39498f17fcbd103b19fa1223a430/library_capsule.jpg",
            "screenshots": [
              "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3602270/e0a52a09cd85472aecfb430ad086aae040cb100c/ss_e0a52a09cd85472aecfb430ad086aae040cb100c.1920x1080.jpg",
              "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3602270/77719570b08d1b46facf8477df20aa5b97b54573/ss_77719570b08d1b46facf8477df20aa5b97b54573.1920x1080.jpg",
              "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3602270/08fd286888a7efb1e782a5106fdb1b1f237bcdb2/ss_08fd286888a7efb1e782a5106fdb1b1f237bcdb2.1920x1080.jpg"
            ],
            "tags": ["Action", "Indie"],
            "status": "COMING_SOON",
            "description": {
              "en": "Whisk is a two-player platformer about shared movement and communication. Coordinate jumps, climbs and throws with a partner to get every Dreamcat home."
            },
            "actions": [
              { "type": "WISHLIST", "url": "https://store.steampowered.com/app/3602270/", "store": "Steam", "style": "primary" },
              { "type": "GET_DEMO", "url": "https://store.steampowered.com/app/3602270/", "appId": 4320000 },
              { "type": "VISIT", "url": "https://store.steampowered.com/app/3602270/" }
            ]
          },
          "bootAds": [
            {
              "campaignId": "mock-matiks-quiz",
              "template": "quiz_card",
              "imageUrl": "",
              "title": {
                "en": "Matiks"
              },
              "action": {
                "type": "VISIT",
                "url": "https://matiks.com/?utm_source=gamenative&utm_medium=boot_ad&utm_campaign=mock-matiks-quiz",
                "style": "primary"
              },
              "appId": null,
              "maxShowsPerDay": 0,
              "questions": [
                {
                  "prompt": {
                    "en": "17 × 6 = ?"
                  },
                  "options": [
                    "98",
                    "102",
                    "112"
                  ],
                  "correctIndex": 1,
                  "timerSeconds": 7,
                  "winBody": {
                    "en": "Faster than 81% of players. Try a real opponent →"
                  },
                  "loseBody": {
                    "en": "It was 102. Get your rematch →"
                  }
                },
                {
                  "prompt": {
                    "en": "What comes next? 3, 6, 12, 24, …"
                  },
                  "options": [
                    "36",
                    "42",
                    "48"
                  ],
                  "correctIndex": 2,
                  "timerSeconds": 7,
                  "winBody": {
                    "en": "Doubling spotted. Matiks players see it in under 2 seconds →"
                  },
                  "loseBody": {
                    "en": "It doubles: 48. Get your rematch →"
                  }
                },
                {
                  "prompt": {
                    "en": "45% of 60 = ?"
                  },
                  "options": [
                    "24",
                    "27",
                    "30"
                  ],
                  "correctIndex": 1,
                  "timerSeconds": 7,
                  "winBody": {
                    "en": "Clean. Try a real opponent →"
                  },
                  "loseBody": {
                    "en": "45% of 60 is 27. Get your rematch →"
                  }
                },
                {
                  "prompt": {
                    "en": "Which is bigger?"
                  },
                  "options": [
                    "7⁄8",
                    "8⁄9"
                  ],
                  "correctIndex": 1,
                  "timerSeconds": 5,
                  "winBody": {
                    "en": "You sure you didn't guess? Prove it on Matiks →"
                  },
                  "loseBody": {
                    "en": "8⁄9 wins by a hair (0.889 vs 0.875). Rematch →"
                  }
                },
                {
                  "prompt": {
                    "en": "Make 24 with 3, 3, 8, 8 — possible?"
                  },
                  "options": [
                    "Yes",
                    "No"
                  ],
                  "correctIndex": 0,
                  "timerSeconds": 7,
                  "winBody": {
                    "en": "Yes: 8÷(3−8÷3) = 24. You saw it? Play people who see it faster →"
                  },
                  "loseBody": {
                    "en": "Yes — 8÷(3−8÷3) = 24. Wait, what? Matiks does this daily →"
                  }
                },
                {
                  "prompt": {
                    "en": "Solve: 99 + 98 + 97 = ?"
                  },
                  "options": [
                    "294",
                    "292",
                    "296"
                  ],
                  "correctIndex": 0,
                  "timerSeconds": 7,
                  "winBody": {
                    "en": "You just did what Matiks players do 50 times a day →"
                  },
                  "loseBody": {
                    "en": "3×100 − 6 = 294. Get your rematch →"
                  }
                }
              ]
            },
            {
              "campaignId": "mock-bootdev-quiz",
              "template": "quiz_card",
              "imageUrl": "",
              "title": {
                "en": "Boot.dev"
              },
              "action": {
                "type": "VISIT",
                "url": "https://www.boot.dev/?promo=GAMENATIVE&utm_source=gamenative&utm_medium=boot_ad&utm_campaign=bootdev-quiz-example",
                "style": "primary"
              },
              "maxShowsPerDay": 0,
              "questions": [
                {
                  "prompt": {
                    "en": "What does this print?"
                  },
                  "code": "x = [1, 2, 3]\nprint(x * 2)",
                  "options": [
                    "[2, 4, 6]",
                    "[1, 2, 3, 1, 2, 3]",
                    "error"
                  ],
                  "correctIndex": 1,
                  "timerSeconds": 10,
                  "winBody": {
                    "en": "Sequence repetition — you know your Python. Go deeper on Boot.dev →"
                  },
                  "loseBody": {
                    "en": "It duplicates the list. Learn why on Boot.dev — code GAMENATIVE for 25% off"
                  }
                },
                {
                  "prompt": {
                    "en": "What does this print?"
                  },
                  "code": "print(\"ha\" * 3 + \"!\")",
                  "options": [
                    "hahaha!",
                    "ha3!",
                    "error"
                  ],
                  "correctIndex": 0,
                  "timerSeconds": 10,
                  "winBody": {
                    "en": "hahaha! indeed. Level up on Boot.dev — code GAMENATIVE for 25% off"
                  },
                  "loseBody": {
                    "en": "Strings multiply in Python: hahaha!. Learn why on Boot.dev →"
                  }
                },
                {
                  "prompt": {
                    "en": "Which one is NOT a real programming language?"
                  },
                  "options": [
                    "Rust",
                    "Brainfuck",
                    "Vermin",
                    "COBOL"
                  ],
                  "correctIndex": 2,
                  "timerSeconds": 10,
                  "winBody": {
                    "en": "Vermin isn't real (yet). The other three? All learnable on Boot.dev →"
                  },
                  "loseBody": {
                    "en": "Vermin was the fake — yes, Brainfuck is real. Boot.dev teaches the useful ones →"
                  }
                },
                {
                  "prompt": {
                    "en": "What does this print?"
                  },
                  "code": "print(0.1 + 0.2 == 0.3)",
                  "options": [
                    "True",
                    "False"
                  ],
                  "correctIndex": 1,
                  "timerSeconds": 10,
                  "winBody": {
                    "en": "Floats lie and you knew it. Boot.dev explains the why →"
                  },
                  "loseBody": {
                    "en": "0.1 + 0.2 is 0.30000000000000004. Floats lie — Boot.dev explains why. Code GAMENATIVE for 25% off"
                  }
                }
              ]
            },
            {
              "campaignId": "mock-whisk-boot",
              "template": "video_card",
              "imageUrl": "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3602270/92fb97a2832c9c075165c43d14d974c730ca716b/library_hero.jpg",
              "videoUrl": "https://video.akamai.steamstatic.com/store_trailers/3602270/375401990/4aacd3fefc6bb18a96e07680487c46adb91ca04a/1767578512/microtrailer.mp4",
              "appId": 3602270,
              "screenshots": [
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3602270/e0a52a09cd85472aecfb430ad086aae040cb100c/ss_e0a52a09cd85472aecfb430ad086aae040cb100c.1920x1080.jpg",
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3602270/77719570b08d1b46facf8477df20aa5b97b54573/ss_77719570b08d1b46facf8477df20aa5b97b54573.1920x1080.jpg"
              ],
              "title": {
                "en": "Whisk"
              },
              "body": {
                "en": "A two-player platformer about shared movement. Get every Dreamcat home."
              },
              "action": {
                "type": "WISHLIST",
                "url": "https://store.steampowered.com/app/3602270/",
                "store": "Steam",
                "style": "primary"
              },
              "maxShowsPerDay": 0
            }
          ]
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    // Latest featured from the most recent fetch. Kept in memory (not the disk cache) so the
    // featured decision is always live and never served stale from the daily recommendation cache.
    @Volatile private var lastFeatured: FeaturedItem? = null

    /**
     * Static recommendation + optional featured for the All-tab hero slot.
     * Always fetches so featured reflects the current campaign; the recommendation stays stable
     * for the day via its own cache; the cache is the offline fallback.
     */
    suspend fun getHero(context: Context): HeroResponse =
        withContext(Dispatchers.IO) {
            val fetched = if (MOCK_HERO_RESPONSE) parseHero(MOCK_HERO_JSON) else fetchRemote()
            if (fetched != null) {
                lastFeatured = fetched.featured
                val bootAds = fetched.bootAds.ifEmpty { listOfNotNull(fetched.bootAd) }
                BootAdRepository.store(bootAds)
                if (PrefManager.bootScreenAdsEnabled) BootAdRepository.prefetchVideos(context, bootAds)
                return@withContext HeroResponse(
                    recommendation = stableRecommendation(fetched.recommendation),
                    featured = fetched.featured,
                )
            }
            // Offline: last stable recommendation (or bundled), no featured.
            HeroResponse(
                recommendation = loadCachedRecommendation() ?: loadBundledFallback(context),
                featured = null,
            )
        }

    suspend fun getCurrentRecommendation(context: Context): RecommendedGame? =
        getHero(context).recommendation

    /** Latest featured (if any) — used by the detail screen; no network. */
    fun getCachedFeatured(): FeaturedItem? = lastFeatured

    /** The day's cached static recommendation, if any; no network. */
    fun getCachedRecommendation(): RecommendedGame? = loadCachedRecommendation()

    // The recommendation the library hero currently shows: personalized when the user opted in,
    // else the static pick. Set by LibraryViewModel; read by the boot splash so both agree.
    @Volatile private var currentHeroRecommendation: RecommendedGame? = null

    fun setCurrentHeroRecommendation(rec: RecommendedGame?) {
        currentHeroRecommendation = rec
    }

    fun getCurrentHeroRecommendation(): RecommendedGame? = currentHeroRecommendation ?: loadCachedRecommendation()

    // Today's personalized Discover cards (empty unless the user opted in); the boot splash
    // rotates through them so it isn't stuck on the single hero pick.
    @Volatile private var recommendationPool: List<app.gamenative.data.gog.GogRecCard> = emptyList()

    fun setRecommendationPool(cards: List<app.gamenative.data.gog.GogRecCard>) {
        recommendationPool = cards
    }

    fun getRecommendationPool(): List<app.gamenative.data.gog.GogRecCard> = recommendationPool

    fun getFeaturedGame(context: Context): RecommendedGame? =
        lastFeatured?.toRecommendedGame(context)

    private fun fetchRemote(): HeroResponse? {
        return try {
            val mediaType = "application/json".toMediaType()
            val body = "{}".toRequestBody(mediaType)
            val request = Request.Builder()
                .url(API_URL)
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                parseHero(responseBody)
            }
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Remote hero fetch failed, will try fallback")
            null
        }
    }

    /**
     * Keeps the recommendation stable for a day: reuses the cached pick until it goes stale,
     * then adopts (and caches) the freshly fetched one.
     */
    private fun stableRecommendation(fresh: RecommendedGame?): RecommendedGame? {
        val cached = loadCachedRecommendation()
        val cacheAgeMs = System.currentTimeMillis() - PrefManager.recommendationCacheTimestamp
        if (cached != null && cacheAgeMs in 0..CACHE_TTL_MS) return cached

        if (fresh != null) {
            PrefManager.recommendationCacheJson = json.encodeToString(fresh)
            PrefManager.recommendationCacheTimestamp = System.currentTimeMillis()
            return fresh
        }
        return cached
    }

    private fun loadCachedRecommendation(): RecommendedGame? {
        val cached = PrefManager.recommendationCacheJson
        if (cached.isEmpty()) return null
        return try {
            parseRecommendation(cached)
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Failed to parse cached recommendation")
            null
        }
    }

    private fun parseHero(body: String): HeroResponse? {
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("{") -> {
                val hero = runCatching { json.decodeFromString<HeroResponse>(body) }.getOrNull()
                // A payload that carries the bootAds key (even empty) is the current shape: an
                // empty list means "no campaigns" and must clear the cache, not fall to legacy.
                val currentShape = runCatching { json.parseToJsonElement(body).jsonObject.containsKey("bootAds") }.getOrDefault(false)
                if (hero != null && (currentShape || hero.recommendation != null || hero.featured != null || hero.bootAd != null || hero.bootAds.isNotEmpty())) {
                    hero
                } else {
                    // Legacy: a single recommendation object.
                    runCatching { json.decodeFromString<RecommendedGame>(body) }
                        .getOrNull()
                        ?.let { HeroResponse(recommendation = it) }
                }
            }
            // Legacy: the old bare array response.
            trimmed.startsWith("[") ->
                json.decodeFromString<List<RecommendedGame>>(body).firstOrNull()
                    ?.let { HeroResponse(recommendation = it) }
            else -> null
        }
    }

    /** Cache holds a bare recommendation object; tolerate legacy hero/array shapes too. */
    private fun parseRecommendation(body: String): RecommendedGame? {
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("[") ->
                json.decodeFromString<List<RecommendedGame>>(body).firstOrNull()
            trimmed.startsWith("{") ->
                runCatching { json.decodeFromString<HeroResponse>(body).recommendation }.getOrNull()
                    ?: runCatching { json.decodeFromString<RecommendedGame>(body) }.getOrNull()
            else -> null
        }
    }

    private fun loadBundledFallback(context: Context): RecommendedGame? {
        return try {
            val body = context.assets.open("recommendations.json").bufferedReader().use { it.readText() }
            val list = json.decodeFromString<List<RecommendedGame>>(body)
            list.firstOrNull()
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Bundled recommendation fallback unavailable")
            null
        }
    }
}
