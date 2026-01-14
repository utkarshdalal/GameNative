package app.gamenative.service.gog.api

import android.util.Log
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles parsing of GOG manifest data
 * Separates parsing logic from network operations
 */
@Singleton
class GOGManifestParser @Inject constructor() {

    companion object {
        private const val TAG = "GOGManifestParser"
    }

    /**
     * Select the best build based on generation and preferences
     *
     * TEMPORARY: Currently only supports Generation 2
     *
     * @param builds List of available builds
     * @param preferredGeneration Preferred generation (1 or 2), null = auto-detect
     * @return Selected build or null if none suitable
     */
    fun selectBuild(builds: List<GOGBuild>, preferredGeneration: Int? = null): GOGBuild? {
        if (builds.isEmpty()) {
            Log.w(TAG, "No builds available")
            return null
        }

        // TEMPORARY: Only handle Gen 2 for now
        val filtered = builds.filter { it.generation == 2 }

        // TODO: Uncomment for Gen 1 support
        // val filtered = if (preferredGeneration != null) {
        //     builds.filter { it.generation == preferredGeneration }
        // } else {
        //     builds
        // }

        if (filtered.isEmpty()) {
            Log.w(TAG, "No Gen 2 builds found")
            return null
        }

        // TEMPORARY: Just take first Gen 2 build
        val selected = filtered.first()

        // TODO: Uncomment for Gen 1 support with preference logic
        // val selected = if (preferredGeneration == null) {
        //     filtered.maxByOrNull { it.generation } ?: filtered.first()
        // } else {
        //     filtered.first()
        // }

        return selected
    }

    /**
     * Filter depots based on language
     *
     * @param manifest Main manifest metadata
     * @param language Target language (e.g., "en-US")
     * @return Filtered list of depots matching language
     */
    fun filterDepotsByLanguage(manifest: GOGManifestMeta, language: String): List<Depot> {
        val filtered = manifest.depots.filter { depot ->
            depot.matchesLanguage(language)
        }

        Log.d(TAG, "Filtered ${filtered.size}/${manifest.depots.size} depots for language: $language")
        return filtered
    }

    /**
     * Separate base game files from DLC files
     *
     * @param files All depot files
     * @param baseProductId Base product ID
     * @return Pair of (base game files, DLC files)
     */
    fun separateBaseDLC(files: List<DepotFile>, baseProductId: String): Pair<List<DepotFile>, List<DepotFile>> {
        val baseFiles = mutableListOf<DepotFile>()
        val dlcFiles = mutableListOf<DepotFile>()

        files.forEach { file ->
            if (file.productId == null || file.productId == baseProductId) {
                baseFiles.add(file)
            } else {
                dlcFiles.add(file)
            }
        }

        Log.d(TAG, "Separated: ${baseFiles.size} base files, ${dlcFiles.size} DLC files")
        return Pair(baseFiles, dlcFiles)
    }

    /**
     * Separate support files (redistributables) from game files
     *
     * @param files All depot files
     * @return Pair of (game files, support files)
     */
    fun separateSupportFiles(files: List<DepotFile>): Pair<List<DepotFile>, List<DepotFile>> {
        val gameFiles = mutableListOf<DepotFile>()
        val supportFiles = mutableListOf<DepotFile>()

        files.forEach { file ->
            if (file.isSupportFile()) {
                supportFiles.add(file)
            } else {
                gameFiles.add(file)
            }
        }

        Log.d(TAG, "Separated: ${gameFiles.size} game files, ${supportFiles.size} support files")
        return Pair(gameFiles, supportFiles)
    }

    /**
     * Calculate total download size across multiple depot files
     *
     * @param files List of depot files
     * @return Total compressed size in bytes
     */
    fun calculateTotalSize(files: List<DepotFile>): Long {
        return files.sumOf { file ->
            file.chunks.sumOf { chunk ->
                chunk.compressedSize ?: chunk.size
            }
        }
    }

    /**
     * Calculate total uncompressed size
     *
     * @param files List of depot files
     * @return Total uncompressed size in bytes
     */
    fun calculateUncompressedSize(files: List<DepotFile>): Long {
        return files.sumOf { file ->
            file.chunks.sumOf { it.size }
        }
    }

    /**
     * Find DLC products in manifest
     *
     * @param manifest Main manifest metadata
     * @return List of DLC products (excluding base game)
     */
    fun findDLCProducts(manifest: GOGManifestMeta): List<Product> {
        return manifest.products.filter { it.productId != manifest.baseProductId }
    }

    /**
     * Check if manifest contains any DLC content
     *
     * @param manifest Main manifest metadata
     * @return True if DLC is present
     */
    fun hasDLC(manifest: GOGManifestMeta): Boolean {
        return findDLCProducts(manifest).isNotEmpty()
    }

    /**
     * Build a mapping of chunk MD5 -> secure CDN URL
     *
     * @param chunks List of chunk MD5 hashes
     * @param baseUrls List of base CDN URLs (e.g., https://gog-cdn-fastly.gog.com/...)
     * @return Map of chunk MD5 to download URL
     */
    fun buildChunkUrlMap(chunks: List<String>, baseUrls: List<String>): Map<String, String> {
        if (baseUrls.isEmpty()) {
            Log.w(TAG, "No base CDN URLs provided")
            return emptyMap()
        }

        // Use the first (highest priority) CDN URL as base
        val baseCdnUrl = baseUrls.first()
        
        // Build full URL for each chunk: baseUrl/aa/bb/aabbccdd...
        // Where aa/bb are first 4 chars of MD5 hash
        return chunks.associateWith { chunkMd5 ->
            if (chunkMd5.length >= 4) {
                val first2 = chunkMd5.substring(0, 2)
                val next2 = chunkMd5.substring(2, 4)
                "$baseCdnUrl/$first2/$next2/$chunkMd5"
            } else {
                "$baseCdnUrl/$chunkMd5"
            }
        }
    }

    /**
     * Extract all unique chunk hashes from depot files
     * Preserves order for secure link requests
     *
     * @param files List of depot files
     * @return List of unique compressed MD5 hashes
     */
    fun extractChunkHashes(files: List<DepotFile>): List<String> {
        val seen = mutableSetOf<String>()
        val ordered = mutableListOf<String>()

        files.forEach { file ->
            file.chunks.forEach { chunk ->
                if (seen.add(chunk.compressedMd5)) {
                    ordered.add(chunk.compressedMd5)
                }
            }
        }

        Log.d(TAG, "Extracted ${ordered.size} unique chunks from ${files.size} files")
        return ordered
    }

    /**
     * Detect generation from build metadata
     *
     * @param build Build metadata
     * @return 1 for legacy, 2 for modern GOG builds
     */
    fun detectGeneration(build: GOGBuild): Int {
        return build.generation
    }

    /**
     * Parse builds response JSON
     */
    fun parseBuilds(json: String): BuildsResponse {
        return BuildsResponse.fromJson(JSONObject(json))
    }

    /**
     * Parse manifest metadata JSON
     */
    fun parseManifest(json: String): GOGManifestMeta {
        return GOGManifestMeta.fromJson(JSONObject(json))
    }

    /**
     * Parse depot manifest JSON
     */
    fun parseDepotManifest(json: String): DepotManifest {
        return DepotManifest.fromJson(JSONObject(json))
    }

    /**
     * Parse secure links response JSON
     */
    fun parseSecureLinks(json: String): SecureLinksResponse {
        return SecureLinksResponse.fromJson(JSONObject(json))
    }
}
