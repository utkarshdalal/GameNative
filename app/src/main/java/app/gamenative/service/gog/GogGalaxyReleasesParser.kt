package app.gamenative.service.gog

import org.json.JSONException
import org.json.JSONObject

/** Parses one page from Galaxy's bulk `/users/{userId}/releases` endpoint. */
object GogGalaxyReleasesParser {

    /** Identity used to validate duplicate Galaxy items across the complete cursor walk. */
    data class ReleaseIdentity(
        val platformId: String,
        val externalId: String,
    )

    /** The structural fields that determine whether a release is a hidden-state observation. */
    data class ReleaseObservation(
        val owned: Boolean,
        val hidden: Boolean?,
    )

    /** The observations and pagination metadata carried by one releases response. */
    data class Page(
        val observations: Map<String, Boolean>,
        val releaseObservations: Map<ReleaseIdentity, ReleaseObservation>,
        val nextPageToken: String?,
        val totalCount: Int?,
        val rawItemCount: Int,
    )

    /**
     * Parses one releases page. Every item must provide platform and ownership so the parser can
     * safely determine whether it is relevant. Owned GOG releases additionally require an external
     * ID and an actual Boolean hidden value; irrelevant releases do not need those fields.
     */
    fun parsePage(rawJson: String): Page {
        val root = try {
            JSONObject(rawJson)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed Galaxy releases response", e)
        }

        val items = root.optJSONArray("items")
            ?: throw IllegalArgumentException("Galaxy releases response is missing items")
        val releaseObservations = buildMap {
            for (i in 0 until items.length()) {
                val release = items.optJSONObject(i)
                    ?: throw IllegalArgumentException("Galaxy release $i is not an object")
                val platformId = release.opt("platform_id") as? String
                    ?: throw IllegalArgumentException("Galaxy release $i has an invalid platform_id")
                if (platformId.isBlank()) {
                    throw IllegalArgumentException("Galaxy release $i has a blank platform_id")
                }

                val isOwned = release.opt("owned") as? Boolean
                    ?: throw IllegalArgumentException("Galaxy release $i has an invalid owned value")
                if (platformId != GOG_PLATFORM_ID || !isOwned) continue
                val externalId = parseExternalId(release.opt("external_id"), i)
                val hidden = release.opt("hidden") as? Boolean
                    ?: throw IllegalArgumentException("Galaxy release $i has an invalid hidden value")
                val identity = ReleaseIdentity(platformId, externalId)
                val observation = ReleaseObservation(isOwned, hidden)
                val previous = this[identity]
                if (previous != null && previous != observation) {
                    throw IllegalArgumentException(
                        "Galaxy releases response has conflicting observations for $identity",
                    )
                }
                put(identity, observation)
            }
        }

        return Page(
            observations = releaseObservations
                .asSequence()
                .filter { (identity, observation) ->
                    identity.platformId == GOG_PLATFORM_ID && observation.owned
                }
                .associate { (identity, observation) ->
                    identity.externalId to requireNotNull(observation.hidden)
                },
            releaseObservations = releaseObservations,
            nextPageToken = parseNextPageToken(root.opt("next_page_token")),
            totalCount = parseOptionalCount(root.opt("total_count")),
            rawItemCount = items.length(),
        )
    }

    /** Reads the required Galaxy release product identifier. */
    private fun parseExternalId(rawValue: Any?, index: Int): String {
        val value = when (rawValue) {
            is Number -> rawValue.toString()
            is String -> rawValue
            else -> throw IllegalArgumentException("Galaxy release $index has an invalid external_id")
        }
        if (value.isBlank()) {
            throw IllegalArgumentException("Galaxy release $index has a blank external_id")
        }
        return value
    }

    /** Reads an optional Galaxy continuation token. */
    private fun parseNextPageToken(rawValue: Any?): String? {
        if (rawValue == null || rawValue == JSONObject.NULL) return null
        val value = rawValue as? String
            ?: throw IllegalArgumentException("Galaxy releases response has an invalid next_page_token")
        if (value.isEmpty()) return null
        if (value.isBlank()) {
            throw IllegalArgumentException("Galaxy releases response has a blank next_page_token")
        }
        return value
    }

    /** Reads an optional Galaxy response item count. */
    private fun parseOptionalCount(rawValue: Any?): Int? {
        if (rawValue == null || rawValue == JSONObject.NULL) return null
        val rawNumber = rawValue as? Number
            ?: throw IllegalArgumentException("Galaxy releases response has an invalid total_count")
        val value = rawNumber.toDouble()
        if (!value.isFinite() || value < 0 || value > Int.MAX_VALUE || value % 1.0 != 0.0) {
            throw IllegalArgumentException("Galaxy releases response has an invalid total_count")
        }
        return value.toInt()
    }

    private const val GOG_PLATFORM_ID = "gog"
}
