package app.gamenative.data.ludusavi

import app.gamenative.data.SaveFilePattern
import app.gamenative.enums.PathType
import timber.log.Timber

/**
 * Maps Ludusavi path placeholders to GameNative PathType enum and SaveFilePattern.
 * 
 * Ludusavi uses placeholders like winLocalAppData, winAppData, winDocuments, winSavedGames, game.
 * GameNative uses PathType enum which maps to Wine prefix paths.
 */
object LudusaviPathMapper {
    
    /**
     * Converts a Ludusavi path template to a SaveFilePattern.
     * 
     * @param ludusaviPath The path template from Ludusavi manifest
     * @return SaveFilePattern or null if path type is not supported
     */
    fun translateToPattern(ludusaviPath: String): SaveFilePattern? {
        // Handle base installation directory placeholder
        if (ludusaviPath.contains("<base>")) {
            Timber.d("Skipping <base> placeholder path: $ludusaviPath")
            return null
        }
        
        return when {
            ludusaviPath.startsWith("<winLocalAppData>") -> {
                parseWindowsPath(
                    pathType = PathType.WinAppDataLocalLow,
                    ludusaviPath = ludusaviPath,
                    prefix = "<winLocalAppData>",
                )
            }
            
            ludusaviPath.startsWith("<winAppData>") -> {
                parseWindowsPath(
                    pathType = PathType.WinAppDataRoaming,
                    ludusaviPath = ludusaviPath,
                    prefix = "<winAppData>",
                )
            }
            
            ludusaviPath.startsWith("<winDocuments>") -> {
                parseWindowsPath(
                    pathType = PathType.WinMyDocuments,
                    ludusaviPath = ludusaviPath,
                    prefix = "<winDocuments>",
                )
            }
            
            ludusaviPath.startsWith("<winSavedGames>") -> {
                parseWindowsPath(
                    pathType = PathType.WinSavedGames,
                    ludusaviPath = ludusaviPath,
                    prefix = "<winSavedGames>",
                )
            }
            
            ludusaviPath.startsWith("<game>") -> {
                parseWindowsPath(
                    pathType = PathType.GameInstall,
                    ludusaviPath = ludusaviPath,
                    prefix = "<game>",
                )
            }
            
            // Skip Linux/Mac paths
            ludusaviPath.startsWith("<home>") ||
            ludusaviPath.startsWith("<xdgData>") ||
            ludusaviPath.startsWith("<xdgConfig>") -> {
                Timber.d("Skipping non-Windows path: $ludusaviPath")
                null
            }
            
            else -> {
                Timber.w("Unknown Ludusavi path type: $ludusaviPath")
                null
            }
        }
    }
    
    /**
     * Parse a Windows-style Ludusavi path into SaveFilePattern components.
     * 
     * @param pathType The PathType enum to use
     * @param ludusaviPath Full path from Ludusavi
     * @param prefix The placeholder prefix to remove
     */
    private fun parseWindowsPath(
        pathType: PathType,
        ludusaviPath: String,
        prefix: String,
    ): SaveFilePattern {
        // Remove prefix and leading slash
        val relativePath = ludusaviPath.removePrefix(prefix).removePrefix("/")
        
        // Handle glob patterns like **/*.sav or Saves/*
        val parts = relativePath.split("/")
        
        // Find where the glob pattern starts (contains * or **)
        val firstGlobIndex = parts.indexOfFirst { it.contains("*") }
        
        val pathPart: String
        val patternPart: String
        
        if (firstGlobIndex >= 0) {
            // Everything before glob is path, glob itself is pattern
            val pathComponents = parts.take(firstGlobIndex)
            val patternComponents = parts.drop(firstGlobIndex)
            
            pathPart = pathComponents.joinToString("/")
            patternPart = patternComponents.lastOrNull() ?: "*"
        } else {
            // No glob - treat entire path as directory with wildcard pattern
            pathPart = relativePath
            patternPart = "*"
        }
        
        // Determine if recursive scanning is needed
        val isRecursive = relativePath.contains("**") || parts.size > 2
        
        return SaveFilePattern(
            root = pathType,
            path = pathPart,
            pattern = patternPart,
            recursive = if (isRecursive) 1 else 0,
        )
    }
    
    /**
     * Checks if a file entry should be processed based on OS and store conditions.
     * 
     * @param entry The LudusaviFileEntry to check
     * @return true if entry applies to Windows Steam games
     */
    fun shouldProcessEntry(entry: LudusaviFileEntry): Boolean {
        // If no conditions, entry applies to all platforms/stores
        if (entry.conditions.isEmpty()) {
            return true
        }
        
        // Check if any condition matches Windows + Steam
        return entry.conditions.any { condition ->
            val osMatches = condition.os == null || condition.os == "windows"
            val storeMatches = condition.store == null || condition.store == "steam"
            osMatches && storeMatches
        }
    }
}
