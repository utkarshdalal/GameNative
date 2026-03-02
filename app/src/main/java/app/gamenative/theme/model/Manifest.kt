package app.gamenative.theme.model

/**
 * Theme manifest describing compatibility and identification information.
 */
data class Manifest(
    /** Unique identifier of the theme (folder name friendly). */
    val id: String,
    /** Human readable version of the theme (semantic string, e.g., "1.0.0"). */
    val version: String,
    /** 
     * Theme engine version constraint this theme targets.
     * Supports Composer-like constraints:
     * - "1.0.0" - exact match
     * - "1.*" or "1.x" - any version with major version 1
     * - "^1.0.0" - >=1.0.0 and <2.0.0
     * - "~1.2.0" - >=1.2.0 and <1.3.0
     */
    val engineVersion: String,
    /** Minimum supported app version (semantic string, e.g., "1.2.0"). */
    val minAppVersion: String,
    /** Optional maximum supported app version (semantic string). */
    val maxAppVersion: String? = null,
)
