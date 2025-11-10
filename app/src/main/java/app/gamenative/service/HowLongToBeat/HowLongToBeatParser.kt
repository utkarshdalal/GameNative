package app.gamenative.service.HowLongToBeat.howlongtobeat

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Internal helper class to parse HTML and create HowLongToBeatEntry instances
 */
internal object HowLongToBeatParser {

    /**
     * Parses the passed HTML to generate a HowLongToBeatEntry.
     * All the DOM parsing and element traversing is done here.
     *
     * @param html The HTML from HLTB detail page
     * @param id The HLTB internal game id
     * @return HowLongToBeatEntry representing the page
     */
    fun parseDetails(html: String, id: String): HowLongToBeatEntry {
        val doc: Document = Jsoup.parse(html)

        var gameName = ""
        var imageUrl = ""
        val timeLabels = mutableListOf<List<String>>()
        var gameplayMain = 0.0
        var gameplayMainExtra = 0.0
        var gameplayComplete = 0.0

        // Parse game name
        doc.select("div[class*=GameHeader_profile_header__]").firstOrNull()?.let { element ->
            gameName = element.text().trim()
        }

        // Parse image URL
        doc.select("div[class*=GameHeader_game_image__] img").firstOrNull()?.let { element ->
            imageUrl = element.attr("src")
        }

        // Parse game description
        val gameDescription = doc.select(".in.back_primary.shadow_box div[class*=GameSummary_large__]")
            .text()

        // Parse platforms
        val platforms = mutableListOf<String>()
        doc.select("div[class*=GameSummary_profile_info__]").forEach { element ->
            val metaData = element.text()
            if (metaData.contains("Platforms:")) {
                platforms.addAll(
                    metaData
                        .replace("\n", "")
                        .replace("Platforms:", "")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                )
            }
        }

        // Parse time labels and values
        doc.select("div[class*=GameStats_game_times__] li").forEach { liElement ->
            val type = liElement.select("h4").text()
            val timeText = liElement.select("h5").text()
            val time = parseTime(timeText)

            when {
                type.startsWith("Main Story") || type.startsWith("Single-Player") || type.startsWith("Solo") -> {
                    gameplayMain = time
                    timeLabels.add(listOf("gameplayMain", type))
                }
                type.startsWith("Main + Sides") || type.startsWith("Co-Op") -> {
                    gameplayMainExtra = time
                    timeLabels.add(listOf("gameplayMainExtra", type))
                }
                type.startsWith("Completionist") || type.startsWith("Vs.") -> {
                    gameplayComplete = time
                    timeLabels.add(listOf("gameplayComplete", type))
                }
            }
        }

        return HowLongToBeatEntry(
            id = id,
            name = gameName,
            description = gameDescription,
            platforms = platforms,
            imageUrl = imageUrl,
            timeLabels = timeLabels,
            gameplayMain = gameplayMain,
            gameplayMainExtra = gameplayMainExtra,
            gameplayCompletionist = gameplayComplete,
            similarity = 0.7, // We can adjust this to be fuzzier on the search
            searchTerm = gameName
        )
    }

    /**
     * Utility method used for parsing a given input text (like "44½") as double (like "44.5").
     * The input text represents the amount of hours needed to play this game.
     *
     * @param text Representing the hours (e.g., "44½ Hours", "50 Mins", "5 Hours - 12 Hours")
     * @return The parsed time as double
     */
    private fun parseTime(text: String): Double {
        // '65½ Hours/Mins'; '--' if not known
        if (text.startsWith("--")) {
            return 0.0
        }

        if (text.contains(" - ")) {
            return handleRange(text)
        }

        return getTime(text)
    }

    /**
     * Parses a range of numbers and creates the average.
     *
     * @param text Like "5 Hours - 12 Hours" or "2½ Hours - 33½ Hours"
     * @return The arithmetic median of the range
     */
    private fun handleRange(text: String): Double {
        val parts = text.split(" - ")
        if (parts.size != 2) {
            return 0.0
        }

        val min = getTime(parts[0])
        val max = getTime(parts[1])
        return (min + max) / 2.0
    }

    /**
     * Parses a string to get a number
     *
     * @param text Can be "12 Hours" or "5½ Hours" or "50 Mins"
     * @return The time, parsed from text
     */
    private fun getTime(text: String): Double {
        val trimmedText = text.trim()

        // Extract unit (Hours or Mins)
        val spaceIndex = trimmedText.indexOf(' ')
        if (spaceIndex == -1) {
            return 0.0
        }

        val timeUnit = trimmedText.substring(spaceIndex + 1).trim()

        // Check for Mins, then assume 1 hour at least
        if (timeUnit == "Mins") {
            return 1.0
        }

        // Extract time value
        val timeString = trimmedText.substring(0, spaceIndex)

        // Handle fraction (½)
        if (timeString.contains("½")) {
            val wholePartEnd = timeString.indexOf("½")
            val wholePart = if (wholePartEnd > 0) {
                timeString.substring(0, wholePartEnd).toIntOrNull() ?: 0
            } else {
                0
            }
            return wholePart + 0.5
        }

        // Parse regular integer
        return timeString.toIntOrNull()?.toDouble() ?: 0.0
    }
}
