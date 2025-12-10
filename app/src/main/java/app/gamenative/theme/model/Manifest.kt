package app.gamenative.theme.model

/**
 * Theme manifest describing compatibility and identification information.
 */
data class Manifest(
    /** Unique identifier of the theme (folder name friendly). */
    val id: String,
    /** Human readable version of the theme (semantic string, e.g., "1.0.0"). */
    val version: String,
    /** Theme engine major version this theme targets. Must equal [ThemeEngine.ENGINE_MAJOR]. */
    val engineVersion: Int,
    /** Minimum supported app version (semantic string, e.g., "1.2.0"). */
    val minAppVersion: String,
    /** Optional maximum supported app version (semantic string). */
    val maxAppVersion: String? = null,
)
