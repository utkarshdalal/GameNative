package app.gamenative.service.HowLongToBeat.howlongtobeat

/**
 * Typings for an Entry in HowLongToBeat
 */
data class HowLongToBeatEntry(
    /** Unique game identifier from HLTB */
    val id: String,

    /** Game name/title */
    val name: String,

    /** Game description */
    val description: String,

    /** Platforms the game is available on (e.g., "PlayStation 4", "PC") */
    val platforms: List<String>,

    /** URL to game cover image */
    val imageUrl: String,

    /** Array of time label mappings, e.g., [["gameplayMain", "Main Story"], ["gameplayMainExtra", "Main + Extras"]] */
    val timeLabels: List<List<String>>,

    /** Main story completion time in hours */
    val gameplayMain: Double,

    /** Main story + extras completion time in hours */
    val gameplayMainExtra: Double,

    /** Completionist/100% completion time in hours */
    val gameplayCompletionist: Double,

    /** Similarity score between search query and result (0.0-1.0) */
    val similarity: Double,

    /** Original search term used to find this entry */
    val searchTerm: String
) {
    /** Deprecated alias for platforms, kept for backward compatibility */
    @Deprecated("Use platforms instead", ReplaceWith("platforms"))
    val playableOn: List<String> = platforms
}
