package app.gamenative.service.HowLongToBeat

import app.gamenative.service.HowLongToBeat.howlongtobeat.HltbSearch
import app.gamenative.service.HowLongToBeat.howlongtobeat.HowLongToBeatEntry
import app.gamenative.service.HowLongToBeat.howlongtobeat.HowLongToBeatParser
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


/**
 * Main service class for interacting with HowLongToBeat API
 * This has been adapted to Kotlin from the excellent work by https://github.com/ckatzorke/howlongtobeat
 */
class HowLongToBeatService {

    private val hltb = HltbSearch();

    /**
     * Searches for games matching the query string
     *
     * @param query The search query (game name)
     * @return List of matching HowLongToBeatEntry instances, sorted by similarity
     */
    suspend fun search(query: String): List<HowLongToBeatEntry> {
        val searchTerms = query.split(" ")
        val searchResponse = hltb.search(searchTerms)

        val hltbEntries = mutableListOf<HowLongToBeatEntry>()

        for (resultEntry in searchResponse.data) {
            val entry = HowLongToBeatEntry(
                id = resultEntry.game_id.toString(),
                name = resultEntry.game_name,
                description = "", // No description in search results
                platforms = resultEntry.profile_platform?.split(", ") ?: emptyList(),
                imageUrl = HltbSearch.Companion.IMAGE_URL + resultEntry.game_image,
                timeLabels = listOf(
                    listOf("Main", "Main"),
                    listOf("Main + Extra", "Main + Extra"),
                    listOf("Completionist", "Completionist")
                ),
                gameplayMain = (resultEntry.comp_main / 3600.0).roundToInt().toDouble(),
                gameplayMainExtra = (resultEntry.comp_plus / 3600.0).roundToInt().toDouble(),
                gameplayCompletionist = (resultEntry.comp_100 / 3600.0).roundToInt().toDouble(),
                similarity = calcDistancePercentage(resultEntry.game_name, query),
                searchTerm = query
            )
            hltbEntries.add(entry)
        }

        // Sort by similarity (highest first)
        return hltbEntries.sortedByDescending { it.similarity }
    }

    /**
     * Get HowLongToBeatEntry from game id, by fetching the detail page and parsing it
     *
     * @param gameId The HLTB internal game id
     * @return HowLongToBeatEntry for the game
     */
    suspend fun detail(gameId: String): HowLongToBeatEntry {
        val detailPage = hltb.detailHtml(gameId)
        return HowLongToBeatParser.parseDetails(detailPage, gameId)
    }

    companion object {
        /**
         * Calculates the similarity of two strings based on the Levenshtein distance
         * in relation to the string lengths.
         *
         * @param text1 First string
         * @param text2 Second string
         * @return Similarity ratio between 0.0 and 1.0 (1.0 = identical)
         */
        fun calcDistancePercentage(text1: String, text2: String): Double {
            val distance = levenshteinDistance(text1.lowercase(), text2.lowercase())
            val maxLength = max(text1.length, text2.length)

            if (maxLength == 0) return 1.0

            return 1.0 - (distance.toDouble() / maxLength.toDouble())
        }

        /**
         * Calculates the Levenshtein distance between two strings
         *
         * @param s1 First string
         * @param s2 Second string
         * @return The Levenshtein distance
         */
        private fun levenshteinDistance(s1: String, s2: String): Int {
            val len1 = s1.length
            val len2 = s2.length

            // Create a 2D array to store distances
            val dp = Array(len1 + 1) { IntArray(len2 + 1) }

            // Initialize first column and row
            for (i in 0..len1) {
                dp[i][0] = i
            }
            for (j in 0..len2) {
                dp[0][j] = j
            }

            // Fill the dp array
            for (i in 1..len1) {
                for (j in 1..len2) {
                    val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1

                    dp[i][j] = min(
                        min(
                            dp[i - 1][j] + 1,      // deletion
                            dp[i][j - 1] + 1       // insertion
                        ),
                        dp[i - 1][j - 1] + cost    // substitution
                    )
                }
            }

            return dp[len1][len2]
        }
    }
}
