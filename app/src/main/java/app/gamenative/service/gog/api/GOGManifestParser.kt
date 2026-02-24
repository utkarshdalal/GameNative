package app.gamenative.service.gog.api

import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Handles parsing of GOG manifest data
 * Separates parsing logic from network operations
 */
@Singleton
class GOGManifestParser @Inject constructor() {

    companion object {
        private const val TAG = "GOG"
    }

    /**
     * Select a build for the given platform and generation.
     *
     * @param builds List of available builds
     * @param preferredGeneration Generation to select (1 = legacy, 2 = modern)
     * @param platform Target platform (e.g., "windows", "linux", "osx")
     * @return Selected build or null if none matching
     */
    fun selectBuild(
        builds: List<GOGBuild>,
        preferredGeneration: Int = 2,
        platform: String = "windows"
    ): GOGBuild? {
        if (builds.isEmpty()) {
            Timber.tag(TAG).w("No builds available")
            return null
        }

        val filtered = builds.filter {
            it.generation == preferredGeneration && it.platform.equals(platform, ignoreCase = true)
        }

        if (filtered.isEmpty()) {
            val summary = builds.joinToString { "Gen ${it.generation}/${it.platform}" }
            Timber.tag(TAG).w("No Gen $preferredGeneration builds for platform: $platform. Available: [$summary]")
            return null
        }

        val selected = filtered.first()
        Timber.tag(TAG).d("Selected build ${selected.buildId} (Gen ${selected.generation}, ${selected.platform})")
        return selected
    }

    /**
     * Filter depots based on language
     *
     * @param manifest Main manifest metadata
     * @param language Target language (e.g., "en-US") or is *
     * @return Filtered list of depots matching language
     */
    fun filterDepotsByLanguage(manifest: GOGManifestMeta, language: String): List<Depot> {
        val filtered = manifest.depots.filter { depot ->
            depot.matchesLanguage(language) || (depot.languages?.contains("*") == true)
        }

        Timber.tag(TAG).d("Filtered ${filtered.size}/${manifest.depots.size} depots for language: $language")
        return filtered
    }

    /**
     * Derives the effective language from the depots actually used for download.
     * Prefers the requested language if any depot matches it; otherwise uses the first concrete
     * language from the depots (e.g. after fallback to "*" or other languages).
     */
    fun getEffectiveLanguageFromDepots(depots: List<Depot>, requestedLanguage: String): String {
        if (depots.any { it.matchesLanguage(requestedLanguage) }) return requestedLanguage
        val fromDepots = depots.flatMap { it.languages }.firstOrNull { it != "*" }
        return fromDepots ?: requestedLanguage
    }

    /**
     * Filter depots based on OS bitness
     *
     * @param depots List of depots to filter
     * @param bitness Target bitness (e.g., "64", "32")
     * @return Filtered list of depots matching bitness
     */
    fun filterDepotsByBitness(depots: List<Depot>, bitness: String = "64"): List<Depot> {
        val filtered = depots.filter { depot ->
            depot.osBitness == null || depot.osBitness.contains(bitness)
        }

        Timber.tag(TAG).d("Filtered ${filtered.size}/${depots.size} depots for bitness: $bitness")
        return filtered
    }

    /**
     * Filter depots based on ownership
     *
     * @param depots List of depots to filter
     * @param ownedProductIds Set of product IDs the user owns
     * @return Filtered list of depots for owned products only
     */
    fun filterDepotsByOwnership(depots: List<Depot>, ownedProductIds: Set<String>): List<Depot> {
        val filtered = depots.filter { depot ->
            depot.productId in ownedProductIds
        }

        Timber.tag(TAG).d("Filtered ${filtered.size}/${depots.size} depots for owned products")
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

        Timber.tag(TAG).d("Separated: ${baseFiles.size} base files, ${dlcFiles.size} DLC files")
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

        Timber.tag(TAG).d("Separated: ${gameFiles.size} game files, ${supportFiles.size} support files")
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
            Timber.tag(TAG).w("No base CDN URLs provided")
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
     * Build a mapping of chunk MD5 -> secure CDN URL with per-product URLs
     * Each product (base game + DLCs) has its own CDN path with different URLs
     *
     * @param chunks List of chunk MD5 hashes
     * @param chunkToProductMap Map of chunk hash to product ID
     * @param productUrlMap Map of product ID to list of secure URLs for that product
     * @return Map of chunk MD5 to download URL
     */
    fun buildChunkUrlMapWithProducts(
        chunks: List<String>,
        chunkToProductMap: Map<String, String>,
        productUrlMap: Map<String, List<String>>
    ): Map<String, String> {
        val chunkUrlMap = mutableMapOf<String, String>()

        for (chunkMd5 in chunks) {
            val productId = chunkToProductMap[chunkMd5]
            if (productId == null) {
                Timber.tag(TAG).w("No product ID found for chunk $chunkMd5")
                continue
            }

            val productUrls = productUrlMap[productId]
            if (productUrls.isNullOrEmpty()) {
                Timber.tag(TAG).w("No URLs found for product $productId (chunk $chunkMd5)")
                continue
            }

            // Use the first (highest priority) CDN URL for this product
            val baseCdnUrl = productUrls.first()

            // Build full URL for chunk: baseUrl/aa/bb/aabbccdd...
            // Where aa/bb are first 4 chars of MD5 hash
            val chunkUrl = if (chunkMd5.length >= 4) {
                val first2 = chunkMd5.substring(0, 2)
                val next2 = chunkMd5.substring(2, 4)
                "$baseCdnUrl/$first2/$next2/$chunkMd5"
            } else {
                "$baseCdnUrl/$chunkMd5"
            }

            chunkUrlMap[chunkMd5] = chunkUrl
        }

        Timber.tag(TAG).d("Built ${chunkUrlMap.size} chunk URLs from ${productUrlMap.size} product(s)")
        return chunkUrlMap
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

        Timber.tag(TAG).d("Extracted ${ordered.size} unique chunks from ${files.size} files")
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
    fun parseDependencyManifest(json: String): GOGDependencyManifestMeta {
        return GOGDependencyManifestMeta.fromJson(JSONObject(json))
    }

    /**
     * Parse manifest metadata JSON (Gen 2 or Gen 1).
     * Gen 1 repository.json has "product" with "depots", "installDirectory", "rootGameID", "timestamp".
     */
    fun parseManifest(json: String): GOGManifestMeta {
        val obj = JSONObject(json)
        if (obj.has("product")) {
            val product = obj.getJSONObject("product")
            if (product.has("depots")) return parseManifestV1(obj)
        }
        return GOGManifestMeta.fromJson(obj)
    }

    /**
     * Parse Gen 1 (v1) repository.json to GOGManifestMeta.
     * Structure per heroic-gogdl: product.depots, product.installDirectory, product.rootGameID, product.timestamp, product.gameIDs.
     */
    fun parseManifestV1(json: JSONObject): GOGManifestMeta {
        val product = json.getJSONObject("product")
        val installDirectory = product.optString("installDirectory", "")
        val baseProductId = product.optString("rootGameID", "")
        val timestamp = product.optString("timestamp", "")

        val depotsArray = product.optJSONArray("depots")
        val depots = mutableListOf<Depot>()
        val dependencies = mutableListOf<String>()

        if (depotsArray != null) {
            for (i in 0 until depotsArray.length()) {
                val d = depotsArray.getJSONObject(i)
                if (d.has("redist")) {
                    dependencies.add(d.getString("redist"))
                    continue
                }
                val gameIds = d.optJSONArray("gameIDs")
                val productId = if (gameIds != null && gameIds.length() > 0) gameIds.getString(0) else ""
                val langs = mutableListOf<String>()
                val langArr = d.optJSONArray("languages")
                if (langArr != null) {
                    for (j in 0 until langArr.length()) {
                        val lang = langArr.getString(j)
                        langs.add(if (lang == "Neutral") "*" else lang)
                    }
                }
                // Gen 1: depots with no languages array are language-agnostic (main content); include them
                if (langs.isEmpty()) langs.add("*")
                depots.add(
                    Depot(
                        productId = productId,
                        languages = langs,
                        manifest = d.optString("manifest", ""),
                        compressedSize = d.optLong("size", 0),
                        size = d.optLong("size", 0),
                        osBitness = null
                    )
                )
            }
        }

        val products = mutableListOf<Product>()
        val gameIdsArray = product.optJSONArray("gameIDs")
        if (gameIdsArray != null) {
            for (i in 0 until gameIdsArray.length()) {
                val g = gameIdsArray.getJSONObject(i)
                val pid = g.optString("gameID", "")
                val nameObj = g.optJSONObject("name")
                val name = nameObj?.optString("en", nameObj.optString("English", pid)) ?: pid
                products.add(Product(productId = pid, name = name))
            }
        }

        Timber.tag(TAG).d("Parsed Gen 1 manifest: installDirectory=$installDirectory, depots=${depots.size}, timestamp=$timestamp")
        return GOGManifestMeta(
            baseProductId = baseProductId,
            installDirectory = installDirectory,
            depots = depots,
            dependencies = dependencies,
            products = products,
            productTimestamp = timestamp
        )
    }

    /**
     * Parse Gen 1 depot manifest (depot.files with path, hash, size, url, offset).
     */
    fun parseV1DepotManifest(json: String): List<V1DepotFile> {
        val obj = JSONObject(json)
        val depot = obj.optJSONObject("depot") ?: obj
        val filesArray = depot.optJSONArray("files") ?: return emptyList()
        val list = mutableListOf<V1DepotFile>()
        for (i in 0 until filesArray.length()) {
            val f = filesArray.getJSONObject(i)
            if (f.has("directory")) continue
            val path = f.optString("path", "").replace("\\", "/").removePrefix("/")
            val size = f.optLong("size", 0)
            val hash = f.optString("hash", "")
            val url = if (f.has("url") && !f.isNull("url")) f.getString("url") else null
            val offset = if (f.has("offset") && !f.isNull("offset")) f.optLong("offset", 0) else null
            val isSupport = f.optBoolean("support", false)
            list.add(
                V1DepotFile(
                    path = path,
                    size = size,
                    hash = hash,
                    url = url,
                    offset = offset,
                    isSupport = isSupport
                )
            )
        }
        return list
    }

    /**
     * Parse depot manifest JSON (Gen 2)
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

    /**
     * Decompress GOG manifest data (auto-detects zlib or gzip)
     */
    fun decompressManifest(data: ByteArray): String {
        // Check compression type by magic bytes
        val isGzipped = data.size >= 2 &&
            data[0] == 0x1f.toByte() &&
            data[1] == 0x8b.toByte()

        val isZlib = data.size >= 2 &&
            data[0] == 0x78.toByte() &&
            (
                data[1] == 0x9c.toByte() ||
                    data[1] == 0x01.toByte() ||
                    data[1] == 0xda.toByte()
                )

        return when {
            isGzipped -> {
                // Decompress gzip
                GZIPInputStream(ByteArrayInputStream(data)).use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }
            }

            isZlib -> {
                // Decompress zlib (same as Epic chunk decompression)
                val inflater = Inflater()
                try {
                    inflater.setInput(data)
                    val outputStream = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)

                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        if (count > 0) {
                            outputStream.write(buffer, 0, count)
                        } else if (inflater.needsInput()) {
                            // No more input data available but decompression not finished - malformed data
                            throw Exception("Incomplete or malformed zlib data: decompression ended prematurely")
                        }
                        // If count == 0 but !needsInput(), inflater is still processing, continue loop
                    }

                    outputStream.toString("UTF-8")
                } finally {
                    inflater.end()
                }
            }

            else -> {
                // Try as plain text
                String(data, Charsets.UTF_8)
            }
        }
    }
}
