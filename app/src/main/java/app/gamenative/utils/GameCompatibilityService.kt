package app.gamenative.utils

import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.data.CommunityCompatibilityClassifier
import app.gamenative.data.CommunityCompatibilityVerdict
import app.gamenative.data.GameCompatibilityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Fetches per-game compatibility information from the GameNative API. */
object GameCompatibilityService {
    private const val API_BASE_URL = "https://api.gamenative.app/api/game-compat"
    private val httpClient = Net.http

    @Serializable
    data class CompatibilityTierMetrics(
        val key: String,
        val sessions: Int,
        val playable: Int,
        val playableRate: Double? = null,
        val medianFps: Double? = null,
        val lastSeen: String? = null,
        val ratedDevices: Int? = null,
        val okDevices: Int? = null,
    ) {
        val hasHardwareKey: Boolean get() = key.isNotBlank() && !key.trim().equals("null", ignoreCase = true)
    }

    @Serializable
    data class GameCompatibilityResponse(
        val gameName: String,
        val state: String? = null,
        val tier: String? = null,
        val tiers: Map<String, CompatibilityTierMetrics> = emptyMap(),
    )

    fun statusFor(response: GameCompatibilityResponse): GameCompatibilityStatus =
        when (CommunityCompatibilityClassifier.fromCompatibilityResponse(response).verdict) {
            CommunityCompatibilityVerdict.WORKS,
            CommunityCompatibilityVerdict.SHOULD_WORK,
            -> GameCompatibilityStatus.GPU_COMPATIBLE
            CommunityCompatibilityVerdict.MAY_WORK -> GameCompatibilityStatus.COMPATIBLE
            CommunityCompatibilityVerdict.WONT_WORK -> GameCompatibilityStatus.NOT_COMPATIBLE
            CommunityCompatibilityVerdict.MIXED,
            CommunityCompatibilityVerdict.UNKNOWN,
            -> GameCompatibilityStatus.UNKNOWN
        }

    fun badgeProperties(gameName: String): Map<String, Any> {
        val response = GameCompatibilityCache.getCached(gameName) ?: return emptyMap()
        return badgeProperties(response)
    }

    internal fun badgeProperties(response: GameCompatibilityResponse): Map<String, Any> = buildMap {
        val summary = CommunityCompatibilityClassifier.fromCompatibilityResponse(response)
        put("compat_badge", summary.verdict.name)
        response.state?.let { put("compat_server_state", it) }
        put("compat_evidence_tier", summary.evidenceTier.name)
    }

    /** Returns valid requested entries; missing/old count-only entries remain retryable. */
    suspend fun fetchCompatibility(
        gameNames: List<String>,
        gpuName: String,
    ): Map<String, GameCompatibilityResponse>? = withContext(Dispatchers.IO) {
        if (gameNames.isEmpty()) return@withContext emptyMap()

        try {
            val requestBody = JSONObject().apply {
                put("gameNames", JSONArray(gameNames))
                put("gpuName", gpuName)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.isNotBlank()) {
                    put("socModel", Build.SOC_MODEL)
                }
                // The backend does not split legacy/modern yet. Send it now so the server can
                // add that distinction later without another client request-shape change.
                put("modernBuild", BuildConfig.MODERN_ANDROID)
            }

            PlayIntegrity.signingCertSha256?.let { requestBody.put("signingCertSha256", it) }

            val attestation = KeyAttestationHelper.getAttestationFields("https://api.gamenative.app")
            if (attestation != null) {
                requestBody.put("nonce", attestation.first)
                requestBody.put("attestationChain", JSONArray(attestation.second))
            }

            val bodyString = requestBody.toString()
            val body = bodyString.toRequestBody("application/json".toMediaType())
            val integrityToken = PlayIntegrity.requestToken(bodyString.toByteArray())
            val requestBuilder = Request.Builder()
                .url(API_BASE_URL)
                .post(body)
                .header("Content-Type", "application/json")
            if (integrityToken != null) {
                requestBuilder.header("X-Integrity-Token", integrityToken)
            }

            Timber.tag("GameCompatibilityService").i("Requesting compatibility batch: ${gameNames.size} games")
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("GameCompatibilityService")
                        .w("API request failed - HTTP ${response.code}")
                    return@withContext null
                }
                val responseBody = response.body?.string() ?: return@withContext null
                val result = parseCompatibilityResponse(responseBody, gameNames)
                Timber.tag("GameCompatibilityService")
                    .i("Fetched compatibility batch: ${result.size}/${gameNames.size} results")
                result
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag("GameCompatibilityService")
                .e(error, "Error fetching compatibility data: ${error.message}")
            null
        }
    }

    internal fun parseCompatibilityResponse(
        responseBody: String,
        requestedGameNames: List<String>,
    ): Map<String, GameCompatibilityResponse> {
        val root = JSONObject(responseBody)
        val results = if (root.has("results")) {
            requireNotNull(root.optJSONObject("results")) { "Invalid compatibility results" }
        } else {
            root
        }
        // Unwrapped state/tier responses are supported. Old run-only responses cannot establish
        // a hardware-specific verdict: skip them and retain last-known evidence instead.
        return requestedGameNames.mapNotNull { gameName ->
            val gameData = results.optJSONObject(gameName) ?: return@mapNotNull null
            val state = gameData.optNullableString("state") ?: return@mapNotNull null
            gameName to GameCompatibilityResponse(
                gameName = gameName,
                state = state,
                tier = gameData.optNullableString("tier"),
                tiers = parseTiers(gameData.optJSONObject("tiers")),
            )
        }.toMap()
    }

    private fun parseTiers(tiersJson: JSONObject?): Map<String, CompatibilityTierMetrics> {
        if (tiersJson == null) return emptyMap()
        return buildMap {
            val keys = tiersJson.keys()
            while (keys.hasNext()) {
                val tierName = keys.next()
                val tier = tiersJson.optJSONObject(tierName) ?: continue
                put(
                    tierName,
                    CompatibilityTierMetrics(
                        key = tier.optNullableString("key").orEmpty(),
                        sessions = tier.optInt("sessions", 0),
                        playable = tier.optInt("playable", 0),
                        playableRate = tier.optNullableDouble("playableRate"),
                        medianFps = tier.optNullableDouble("medianFps"),
                        lastSeen = tier.optNullableString("lastSeen"),
                        ratedDevices = tier.optNullableInt("ratedDevices"),
                        okDevices = tier.optNullableInt("okDevices"),
                    ),
                )
            }
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        (opt(name) as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun JSONObject.optNullableInt(name: String): Int? =
        // Preserve invalid counters so they cannot be mistaken for absent rating feedback.
        if (!has(name) || isNull(name)) null else optInt(name, -1)

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it.isFinite() }
}
