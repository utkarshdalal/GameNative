package app.gamenative.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached Ludusavi manifest data for offline access and performance
 */
@Entity(tableName = "ludusavi_manifest_cache")
data class LudusaviManifestCache(
    @PrimaryKey
    val id: Int = 1, // Single row cache
    
    /**
     * Raw YAML manifest content from Ludusavi
     */
    val manifestYaml: String,
    
    /**
     * Timestamp when manifest was last fetched (System.currentTimeMillis())
     */
    val lastUpdated: Long,
    
    /**
     * Manifest version/etag for cache invalidation
     */
    val version: String? = null,
)
