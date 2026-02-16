package app.gamenative.service

import app.gamenative.data.LudusaviManifestCache
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.ludusavi.LudusaviPathMapper
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for fetching and parsing Ludusavi manifest data
 * https://github.com/mtkennerly/ludusavi-manifest
 */
@Singleton
class LudusaviService @Inject constructor(
    private val database: PluviaDatabase,
) {
    
    companion object {
        // Ludusavi manifest is ~15MB YAML, too large for kaml's code point limit
        // We'll download and parse it manually to extract only what we need
        private const val MANIFEST_URL = "https://raw.githubusercontent.com/mtkennerly/ludusavi-manifest/master/data/manifest.yaml"
        private const val CACHE_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        
        /**
         * Known broken games where Steam UFS data is incorrect
         * Will always check Ludusavi for these games
         */
        private val KNOWN_BROKEN_GAMES = setOf(
            1313140, // Cult of the Lamb
            814370,  // Monster Sanctuary
            // Add more as discovered
        )
    }
    
    /**
     * Check if a game is known to have broken Steam UFS data
     */
    fun isKnownBrokenGame(steamAppId: Int): Boolean {
        return steamAppId in KNOWN_BROKEN_GAMES
    }
    
    /**
     * Get save file patterns for a Steam game from Ludusavi manifest
     * 
     * @param steamAppId The Steam App ID to look up
     * @return List of SaveFilePattern or null if game not found or manifest unavailable
     */
    suspend fun getPatterns(steamAppId: Int): List<SaveFilePattern>? = withContext(Dispatchers.IO) {
        try {
            // Download manifest text (don't parse the whole thing)
            val manifestYaml = getManifestYaml() ?: return@withContext null
            
            // Extract just the game entry we need using string parsing
            val gameSection = extractGameSection(manifestYaml, steamAppId) ?: run {
                Timber.d("Game $steamAppId not found in Ludusavi manifest")
                return@withContext null
            }
            
            // Parse file paths from the extracted section
            val patterns = parseFilePaths(gameSection)
            
            if (patterns.isEmpty()) {
                Timber.d("No Windows patterns found for game $steamAppId")
                return@withContext null
            }
            
            Timber.i("Found ${patterns.size} save patterns for game $steamAppId from Ludusavi")
            patterns.forEach { pattern ->
                Timber.d("  - ${pattern.root.name}/${pattern.path}/${pattern.pattern}")
            }
            
            patterns
        } catch (e: Exception) {
            Timber.e(e, "Failed to get patterns for game $steamAppId")
            null
        }
    }
    
    /**
     * Get the manifest YAML text (cached or downloaded)
     */
    private suspend fun getManifestYaml(): String? = withContext(Dispatchers.IO) {
        // Check database cache
        val dao = database.ludusaviManifestCacheDao()
        val cached = dao.get()
        
        if (cached != null && !isCacheExpired(cached.lastUpdated)) {
            Timber.d("Loading Ludusavi manifest from database cache")
            return@withContext cached.manifestYaml
        }
        
        // Fetch from network
        try {
            Timber.i("Downloading Ludusavi manifest from $MANIFEST_URL")
            val manifestYaml = downloadManifest()
            
            // Cache to database
            dao.insert(
                LudusaviManifestCache(
                    manifestYaml = manifestYaml,
                    lastUpdated = System.currentTimeMillis(),
                )
            )
            
            Timber.i("Successfully downloaded Ludusavi manifest (${manifestYaml.length} bytes)")
            manifestYaml
        } catch (e: Exception) {
            Timber.e(e, "Failed to download Ludusavi manifest")
            null
        }
    }
    
    /**
     * Extract a game section from the YAML by finding its steam.id entry
     */
    private fun extractGameSection(yaml: String, steamAppId: Int): String? {
        // Find the steam.id line matching our app ID
        // Pattern: "  steam:\n    id: {steamAppId}"
        val steamIdPattern = Regex("""  steam:\s*\n\s+id:\s*$steamAppId\s*$""", RegexOption.MULTILINE)
        val match = steamIdPattern.find(yaml) ?: return null
        
        // Find the start of this game entry (back to the game name at indent level 0)
        val beforeMatch = yaml.substring(0, match.range.first)
        // Game names are at root level (no indent) and start with a capital letter
        val gameStartPattern = Regex("""^([A-Z0-9][^\n:]*):\s*$""", RegexOption.MULTILINE)
        val gameMatches = gameStartPattern.findAll(beforeMatch).toList()
        val gameNameMatch = gameMatches.lastOrNull() ?: return null
        
        val gameStart = gameNameMatch.range.first
        
        // Find the end of this game entry (next game at indent level 0)
        val afterMatch = yaml.substring(match.range.last)
        val nextGameMatch = Regex("""^[A-Z0-9]""", RegexOption.MULTILINE).find(afterMatch)
        val gameEnd = if (nextGameMatch != null) {
            match.range.last + nextGameMatch.range.first
        } else {
            yaml.length
        }
        
        return yaml.substring(gameStart, gameEnd)
    }
    
    /**
     * Parse file paths from a game YAML section
     */
    private fun parseFilePaths(gameYaml: String): List<SaveFilePattern> {
        val patterns = mutableListOf<SaveFilePattern>()
        
        // Find the files: section
        val filesMatch = Regex("""^\s+files:\s*$""", RegexOption.MULTILINE).find(gameYaml) ?: return patterns
        val filesStart = filesMatch.range.last
        
        // Find where files section ends (next property at same or lower indent level)
        val rest = gameYaml.substring(filesStart)
        val nextSectionMatch = Regex("""^\s{2,4}[a-z]+:\s*$""", RegexOption.MULTILINE).find(rest)
        val filesEnd = if (nextSectionMatch != null) {
            filesStart + nextSectionMatch.range.first
        } else {
            gameYaml.length
        }
        
        val filesSection = gameYaml.substring(filesStart, filesEnd)
        
        // Extract all file paths (keys under files: that start with placeholders)
        // Pattern matches lines like: "<home>/path/to/file":
        val pathPattern = Regex("""^\s{4,}(["']?<.+?):\s*$""", RegexOption.MULTILINE)
        pathPattern.findAll(filesSection).forEach { match ->
            var path = match.groupValues[1].trim()
            // Only process paths that start with < (placeholder paths)
            if (path.startsWith("\"<") || path.startsWith("'<") || path.startsWith("<")) {
                // Remove surrounding quotes if present
                path = path.trim('"').trim('\'')
                LudusaviPathMapper.translateToPattern(path)?.let { patterns.add(it) }
            }
        }
        
        return patterns
    }
    
    /**
     * Download manifest from GitHub (YAML format)
     */
    private fun downloadManifest(): String {
        val url = URL(MANIFEST_URL)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000 // 30 seconds
            connection.readTimeout = 60000    // 60 seconds
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP error $responseCode")
            }
            
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * Check if cached manifest is expired
     */
    private fun isCacheExpired(lastUpdated: Long): Boolean {
        val age = System.currentTimeMillis() - lastUpdated
        return age > CACHE_EXPIRY_MS
    }
    
    /**
     * Clear cached manifest (for testing/debugging)
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        database.ludusaviManifestCacheDao().clear()
        Timber.i("Cleared Ludusavi manifest cache")
    }
}
