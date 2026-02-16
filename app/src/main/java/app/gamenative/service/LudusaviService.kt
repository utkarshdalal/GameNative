package app.gamenative.service

import app.gamenative.data.LudusaviManifestCache
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.ludusavi.LudusaviManifest
import app.gamenative.data.ludusavi.LudusaviPathMapper
import app.gamenative.db.PluviaDatabase
import com.charleskorn.kaml.Yaml
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
        private const val MANIFEST_URL = "https://raw.githubusercontent.com/mtkennerly/ludusavi-manifest/master/data/manifest.yaml"
        private const val CACHE_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        
        /**
         * Known broken games where Steam UFS data is incorrect
         * Will always check Ludusavi for these games
         */
        private val KNOWN_BROKEN_GAMES = setOf(
            1313140, // Cult of the Lamb
            814370,  // Monster Sanctuary
            1486940, // Bastard Bonds
            // Add more as discovered
        )
    }
    
    private var cachedManifest: LudusaviManifest? = null
    
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
            val manifest = getManifest() ?: return@withContext null
            
            // Find game by Steam ID
            val game = manifest.games.values.find { 
                it.steam?.id == steamAppId 
            }
            
            if (game == null) {
                Timber.d("Game $steamAppId not found in Ludusavi manifest")
                return@withContext null
            }
            
            // Convert Ludusavi paths to SaveFilePatterns
            val patterns = game.files
                .filter { (_, entry) -> LudusaviPathMapper.shouldProcessEntry(entry) }
                .mapNotNull { (path, _) -> LudusaviPathMapper.translateToPattern(path) }
            
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
     * Get the Ludusavi manifest, fetching from network if needed
     */
    private suspend fun getManifest(): LudusaviManifest? = withContext(Dispatchers.IO) {
        // Return cached in-memory manifest if available
        cachedManifest?.let { return@withContext it }
        
        // Check database cache
        val dao = database.ludusaviManifestCacheDao()
        val cached = dao.get()
        
        if (cached != null && !isCacheExpired(cached.lastUpdated)) {
            Timber.d("Loading Ludusavi manifest from database cache")
            try {
                val manifest = Yaml.default.decodeFromString(
                    LudusaviManifest.serializer(),
                    cached.manifestYaml,
                )
                cachedManifest = manifest
                return@withContext manifest
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse cached manifest, will re-fetch")
                dao.clear()
            }
        }
        
        // Fetch from network
        try {
            Timber.i("Downloading Ludusavi manifest from $MANIFEST_URL")
            val manifestYaml = downloadManifest()
            
            val manifest = Yaml.default.decodeFromString(
                LudusaviManifest.serializer(),
                manifestYaml,
            )
            
            // Cache to database
            dao.insert(
                LudusaviManifestCache(
                    manifestYaml = manifestYaml,
                    lastUpdated = System.currentTimeMillis(),
                )
            )
            
            cachedManifest = manifest
            Timber.i("Successfully loaded Ludusavi manifest with ${manifest.games.size} games")
            manifest
        } catch (e: Exception) {
            Timber.e(e, "Failed to download/parse Ludusavi manifest")
            null
        }
    }
    
    /**
     * Download manifest from GitHub
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
        cachedManifest = null
        Timber.i("Cleared Ludusavi manifest cache")
    }
}
