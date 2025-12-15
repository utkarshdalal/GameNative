package app.gamenative.theme.model

/**
 * Theme engine constants and versioning.
 */
object ThemeEngine {
    /**
     * The current semantic version of the Theme Engine.
     * Format: MAJOR.MINOR.PATCH
     */
    const val ENGINE_VERSION: String = "1.0.0"
    
    /**
     * The current major version number (for quick access).
     */
    const val ENGINE_MAJOR: Int = 1
    
    /**
     * Check if an engine version constraint matches the current engine version.
     * 
     * Supported constraint formats (Composer-like):
     * - "1.0.0" - exact match
     * - "1.*" or "1.x" or "1.x.x" - any version with major version 1
     * - "1.2.*" or "1.2.x" - any version 1.2.x
     * - "^1.0.0" - >=1.0.0 and <2.0.0 (compatible with major version)
     * - "~1.2.0" - >=1.2.0 and <1.3.0 (compatible with minor version)
     * - ">=1.0.0" - greater than or equal
     * - "<=2.0.0" - less than or equal
     * - ">1.0.0" - greater than
     * - "<2.0.0" - less than
     * 
     * @param constraint The version constraint from the theme manifest
     * @param engineVersion The actual engine version to check against (defaults to ENGINE_VERSION)
     * @return true if the constraint matches the engine version
     */
    fun matchesConstraint(constraint: String, engineVersion: String = ENGINE_VERSION): Boolean {
        val trimmed = constraint.trim()
        if (trimmed.isEmpty()) return false
        
        return when {
            // Wildcard patterns: 1.*, 1.x, 1.x.x
            trimmed.contains("*") || trimmed.lowercase().contains("x") -> {
                matchesWildcard(trimmed, engineVersion)
            }
            // Caret: ^1.0.0 means >=1.0.0 <2.0.0
            trimmed.startsWith("^") -> {
                matchesCaret(trimmed.substring(1), engineVersion)
            }
            // Tilde: ~1.2.0 means >=1.2.0 <1.3.0
            trimmed.startsWith("~") -> {
                matchesTilde(trimmed.substring(1), engineVersion)
            }
            // Comparison operators
            trimmed.startsWith(">=") -> {
                compareSemVer(engineVersion, trimmed.substring(2).trim()) >= 0
            }
            trimmed.startsWith("<=") -> {
                compareSemVer(engineVersion, trimmed.substring(2).trim()) <= 0
            }
            trimmed.startsWith(">") -> {
                compareSemVer(engineVersion, trimmed.substring(1).trim()) > 0
            }
            trimmed.startsWith("<") -> {
                compareSemVer(engineVersion, trimmed.substring(1).trim()) < 0
            }
            // Exact match
            else -> {
                compareSemVer(engineVersion, trimmed) == 0
            }
        }
    }
    
    /**
     * Match wildcard patterns like "1.*", "1.x", "1.2.*", "1.x.x"
     */
    private fun matchesWildcard(pattern: String, version: String): Boolean {
        val patternParts = pattern.replace("*", "x").lowercase().split(".")
        val versionParts = version.split(".")
        
        for (i in patternParts.indices) {
            val patternPart = patternParts[i]
            if (patternPart == "x") {
                // Wildcard matches anything from here on
                return true
            }
            val versionPart = versionParts.getOrNull(i)?.toIntOrNull() ?: return false
            val patternValue = patternPart.toIntOrNull() ?: return false
            if (versionPart != patternValue) return false
        }
        return true
    }
    
    /**
     * Caret matching: ^1.2.3 means >=1.2.3 and <2.0.0
     * Allows changes that do not modify the left-most non-zero digit.
     */
    private fun matchesCaret(constraintVersion: String, version: String): Boolean {
        val constraintParts = parseSemVer(constraintVersion)
        val versionParts = parseSemVer(version)
        
        // Must be >= constraint version
        if (compareSemVer(version, constraintVersion) < 0) return false
        
        // Major version must match (for versions >= 1.0.0)
        if (constraintParts[0] > 0) {
            return versionParts[0] == constraintParts[0]
        }
        // For 0.x.y, minor must match
        if (constraintParts[1] > 0) {
            return versionParts[0] == 0 && versionParts[1] == constraintParts[1]
        }
        // For 0.0.x, patch must match exactly
        return versionParts[0] == 0 && versionParts[1] == 0 && versionParts[2] == constraintParts[2]
    }
    
    /**
     * Tilde matching: ~1.2.3 means >=1.2.3 and <1.3.0
     * Allows patch-level changes.
     */
    private fun matchesTilde(constraintVersion: String, version: String): Boolean {
        val constraintParts = parseSemVer(constraintVersion)
        val versionParts = parseSemVer(version)
        
        // Must be >= constraint version
        if (compareSemVer(version, constraintVersion) < 0) return false
        
        // Major and minor must match
        return versionParts[0] == constraintParts[0] && versionParts[1] == constraintParts[1]
    }
    
    /**
     * Parse semantic version string into [major, minor, patch] array.
     */
    private fun parseSemVer(version: String): IntArray {
        val parts = version.trim().split(".")
        return intArrayOf(
            parts.getOrNull(0)?.toIntOrNull() ?: 0,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0
        )
    }
    
    /**
     * Compare two semantic versions.
     * @return negative if v1 < v2, 0 if equal, positive if v1 > v2
     */
    private fun compareSemVer(v1: String, v2: String): Int {
        val p1 = parseSemVer(v1)
        val p2 = parseSemVer(v2)
        
        for (i in 0..2) {
            val diff = p1[i] - p2[i]
            if (diff != 0) return diff
        }
        return 0
    }
}
