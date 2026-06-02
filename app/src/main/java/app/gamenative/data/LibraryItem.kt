package app.gamenative.data

import app.gamenative.Constants
import app.gamenative.utils.CustomGameScanner

enum class GameSource(val containerPrefix: String) {
    STEAM("STEAM_"),
    CUSTOM_GAME("CUSTOM_GAME_"),
    GOG("GOG_"),
    EPIC("EPIC_"),
    AMAZON("AMAZON_"),
    // Add other platforms here..
    ;

    // container/app-id prefix scheme lives ONLY here. the canonical id form is
    // <containerPrefix><numericId> (e.g. "STEAM_440"). matches/idOf replace the hand-rolled
    // appId-startsWith / removePrefix calls formerly scattered across services + save-sync.
    fun matches(containerId: String): Boolean = containerId.startsWith(containerPrefix)
    fun idOf(containerId: String): String = containerId.removePrefix(containerPrefix)

    companion object {
        // the source whose prefix this container id carries, or null if none match.
        fun fromContainerId(containerId: String): GameSource? =
            entries.firstOrNull { it.matches(containerId) }
        // the id portion after the recognized prefix (whole string if no known prefix).
        fun idPart(containerId: String): String =
            fromContainerId(containerId)?.idOf(containerId) ?: containerId
    }
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
    // runtime indicator surface for library badge + detail screen.
    // string-typed (matches Container.runtime field) so we don't drag in winlator types.
    // default "wine" mirrors Container.RUNTIME_WINE so non-installed entries are inert.
    val runtime: String = "wine",
) {
    val clientIconUrl: String
        get() = when (gameSource) {
            GameSource.STEAM -> if (iconHash.isNotEmpty()) {
                Constants.Library.ICON_URL + "${gameId}/$iconHash.ico"
            } else {
                ""
            }
            GameSource.CUSTOM_GAME -> {
                // Attempt to resolve a local icon from the selected/unique exe folder
                val localPath = CustomGameScanner.findIconFileForCustomGame(appId)
                if (!localPath.isNullOrEmpty()) {
                    if (localPath.startsWith("file://")) localPath else "file://$localPath"
                } else {
                    ""
                }
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
