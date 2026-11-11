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
)

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
