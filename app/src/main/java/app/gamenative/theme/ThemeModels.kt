package app.gamenative.theme

/** A theme folder installed on this device. */
data class InstalledTheme(
    /** Folder name under the themes directory; unique. */
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val description: String? = null,
    val enabled: Boolean = false,
    val options: List<ThemeOption> = emptyList(),
    /** [ThemeSpec.setFields] of this theme alone with its current options. Empty = nothing carries over. */
    val appliedFields: List<String> = emptyList(),
    val totalRules: Int = 0,
    val appliedRules: Int = 0,
    /** Names of themes this one depends on that are not installed. */
    val missingDependencies: List<String> = emptyList(),
    /** Shipped with the app (the "Custom" theme); cannot be deleted. */
    val builtIn: Boolean = false,
    /** Newer version offered by the theme store, or null. */
    val updateAvailable: String? = null,
    /** Id of this theme in the GameNative community list, when it was installed from or shared to it. */
    val communityId: String? = null,
    /** True when this folder was installed from the community list; false for a theme shared from this device. */
    val communityInstalled: Boolean = false,
) {
    /** This device shared the theme to the community list (it was not installed from there). */
    val sharedFromHere: Boolean get() = communityId != null && !communityInstalled
}

sealed interface ThemeOption {
    /** Stable key to pass back to [ThemeManager]. */
    val key: String
    val label: String

    data class Choice(
        override val key: String,
        override val label: String,
        val kind: Kind,
        val values: List<String>,
        val selected: String,
    ) : ThemeOption {
        enum class Kind { DROPDOWN, CHECKBOX, SLIDER }
    }

    /** Colour picker that belongs to a patch; shown only while [visible]. */
    data class Color(
        override val key: String,
        override val label: String,
        val argb: Int,
        val visible: Boolean = true,
    ) : ThemeOption

    /** Image picker that belongs to a patch; shown only while [visible]. */
    data class Image(
        override val key: String,
        override val label: String,
        val path: String?,
        val visible: Boolean = true,
    ) : ThemeOption
}

/** A theme listed by the online theme store. */
data class StoreTheme(
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val description: String? = null,
    val target: String? = null,
    val downloads: Int = 0,
    val previewImageUrl: String? = null,
    val downloadBlobId: String? = null,
)

/** A theme shared by a GameNative user. */
data class CommunityTheme(
    val id: String,
    val name: String,
    val description: String? = null,
    val authorName: String? = null,
    val version: Int = 1,
    val downloads: Int = 0,
    val sizeBytes: Long = 0,
    /** Appearance values of the theme at its defaults, for drawing a preview without downloading it. */
    val preview: ThemeSpec? = null,
    /** Uploaded by the signed-in account. */
    val mine: Boolean = false,
    val updatedAt: String? = null,
    /** Taken off the public list; only reported in the account's own uploads. */
    val hidden: Boolean = false,
    /** Why a [hidden] theme is off the list: reports, copyright or other. */
    val hiddenReason: String? = null,
)

/** Current upload terms of the community list; [version] is sent with every upload. */
data class CommunityTerms(
    val version: String,
    val uploadTerms: String,
    val copyrightPolicy: String,
    val contact: String? = null,
)

data class CommunityPage(
    val themes: List<CommunityTheme>,
    val hasMore: Boolean,
)

enum class CommunitySort { POPULAR, NEW }

/** Why an installed theme cannot be shared. */
enum class ShareBlocker {
    /** The theme styles another app's interface; only themes written with this app's own variables can be shared. */
    NOT_NATIVE,
    TOO_LARGE,
    /** The theme sets nothing. */
    EMPTY,
}

/**
 * Failure of a community request. [code] is one of: unauthorized, invalid_theme, text_rejected, too_large, rate_limited,
 * not_found, terms_required, terms_outdated (the accepted terms are not the current ones), forbidden (this account may
 * not upload, or the upload replaces a removed theme), network, server.
 */
class CommunityException(val code: String, message: String? = null) : Exception(message ?: code)
