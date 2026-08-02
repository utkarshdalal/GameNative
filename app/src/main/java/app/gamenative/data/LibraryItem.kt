package app.gamenative.data

import app.gamenative.Constants

enum class GameSource {
    STEAM,
    CUSTOM_GAME,
    GOG,
    EPIC,
    AMAZON
    // Add other platforms here..
}

enum class GameCompatibilityStatus {
    NOT_COMPATIBLE,
    UNKNOWN,
    COMPATIBLE,
    GPU_COMPATIBLE,
    RECOMMENDED,
}

/** Library list item. */
data class LibraryItem(
    val index: Int = 0,
    val appId: String = "",
    val name: String = "",
    val iconHash: String = "",
    val capsuleImageUrl: String = "",
    val headerImageUrl: String = "",
    val heroImageUrl: String = "",
    val gridHeroImageScale: Float = 1f,
    val isShared: Boolean = false,
    val gameSource: GameSource = GameSource.STEAM,
    val compatibilityStatus: GameCompatibilityStatus? = null,
    val sizeBytes: Long = 0L,
    val isInstalled: Boolean = false,
    val isRecommended: Boolean = false,
    val recommendedGameId: String = "",
    val recRating: Int? = null,
    val recDiscount: String? = null,
    val recPrice: String? = null,
    val recBasePrice: String? = null,
    val recSeedCount: Int = 0,
    val recSeedIconUrl: String? = null,
    val recStoreCard: Boolean = false,
    val recSource: String = "",
    val isFeatured: Boolean = false,
) {
    val clientIconUrl: String
        get() = when (gameSource) {
            GameSource.STEAM -> if (iconHash.isNotEmpty()) {
                Constants.Library.ICON_URL + "${gameId}/$iconHash.ico"
            } else {
                ""
            }
            GameSource.CUSTOM_GAME -> {
                // Return empty; icons are fetched asynchronously in UI components
                // to avoid blocking the main thread with filesystem scans.
                ""
            }
            GameSource.GOG -> {
                // GoG Images are typically the full URL, but have fallback just in case.
                if (iconHash.isEmpty()) {
                    ""
                } else if (iconHash.startsWith("http")) {
                    iconHash
                } else {
                    "${GOGGame.GOG_IMAGE_BASE_URL}/$iconHash"
                }
            }
            GameSource.EPIC -> {
                iconHash
            }
            GameSource.AMAZON -> {
                iconHash
            }
        }

    /** Numeric game ID extracted from the source-prefixed appId; returns 0 if parsing fails. */
    val gameId: Int
        get() = appId.removePrefix("${gameSource.name}_").toIntOrNull() ?: 0
}
