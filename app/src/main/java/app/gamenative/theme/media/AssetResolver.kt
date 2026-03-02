package app.gamenative.theme.media

import java.io.File
import java.util.LinkedHashMap

/**
 * Error produced while resolving media assets.
 */
data class MediaError(
    val code: String,
    val message: String,
)

/** Result of resolving a logical media reference to a concrete URI. */
data class AssetResult(
    /** Fully-qualified URI or file path. For local files, returns a file:// URI. */
    val uri: String?,
    /** True if a fallback path was used instead of the primary. */
    val usedFallback: Boolean = false,
    /** Collected non-fatal errors and warnings. */
    val errors: List<MediaError> = emptyList(),
)

/**
 * Simple in-memory LRU cache for resolved assets (string-in -> string-out).
 * Keys should include enough context (e.g., media kind and allowVideo flag).
 */
class MediaCache(private val capacity: Int = 128) {
    private val map = object : LinkedHashMap<String, String>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > capacity
        }
    }

    fun get(key: String): String? = synchronized(map) { map[key] }
    fun put(key: String, value: String) { synchronized(map) { map[key] = value } }
    fun clear() { synchronized(map) { map.clear() } }
}

/**
 * Abstraction for optional remote checks/fetches. Implementations may perform HEAD
 * requests to verify existence. Default stub returns false (not found).
 */
interface RemoteFetcher {
    /** Returns true if the remote resource appears reachable. Default: false (no network). */
    fun exists(url: String): Boolean = false
}

/**
 * AssetResolver maps logical names (e.g., "game.capsule") or theme-relative paths
 * to concrete URIs. It performs local existence checks and uses a small cache for images.
 */
class AssetResolver(
    private val imageCache: MediaCache = MediaCache(),
    private val remoteFetcher: RemoteFetcher = object : RemoteFetcher {},
) {
    /**
     * Resolve an image path. Applies simple caching and fallback chain.
     * @param logical Primary logical path or URI (e.g., "assets/card.png", "game.capsule", "http://...").
     * @param fallbacks Optional ordered list of fallback logical paths.
     * @param data Optional map providing values for logical keys (e.g., game.capsule -> file://...)
     * @param themeRoot Optional theme directory for resolving relative paths.
     */
    fun resolveImage(
        logical: String?,
        fallbacks: List<String?> = emptyList(),
        data: Map<String, String> = emptyMap(),
        themeRoot: String? = null,
    ): AssetResult {
        if (logical.isNullOrBlank()) {
            return resolveWithFallbacks(null, fallbacks, data, themeRoot, isVideo = false)
        }
        val cacheKey = "img|$logical|${themeRoot ?: ""}"
        imageCache.get(cacheKey)?.let { cached ->
            return AssetResult(uri = cached, usedFallback = false, errors = emptyList())
        }
        val primary = resolveOne(logical, data, themeRoot, isVideo = false)
        if (primary.uri != null) {
            imageCache.put(cacheKey, primary.uri)
            return primary
        }
        val fb = resolveWithFallbacks(logical, fallbacks, data, themeRoot, isVideo = false)
        if (fb.uri != null) imageCache.put(cacheKey, fb.uri)
        return fb
    }

    /** Resolve a video path (no caching here; caching is coordinated by the video manager). */
    fun resolveVideo(
        logical: String?,
        data: Map<String, String> = emptyMap(),
        themeRoot: String? = null,
    ): AssetResult {
        if (logical.isNullOrBlank()) return AssetResult(uri = null, errors = listOf(MediaError("VIDEO_SRC_MISSING", "Video source is empty")))
        return resolveOne(logical, data, themeRoot, isVideo = true)
    }

    // --- Internal helpers ---

    private fun resolveWithFallbacks(
        primary: String?,
        fallbacks: List<String?>,
        data: Map<String, String>,
        themeRoot: String?,
        isVideo: Boolean,
    ): AssetResult {
        val errs = mutableListOf<MediaError>()
        if (!primary.isNullOrBlank()) {
            val p = resolveOne(primary, data, themeRoot, isVideo)
            errs += p.errors
            if (p.uri != null) return p
        }
        for (fb in fallbacks) {
            if (fb.isNullOrBlank()) continue
            val r = resolveOne(fb, data, themeRoot, isVideo)
            errs += r.errors
            if (r.uri != null) return AssetResult(uri = r.uri, usedFallback = true, errors = errs)
        }
        val label = if (isVideo) "video" else "image"
        errs += MediaError(code = "${label.uppercase()}_NOT_FOUND", message = "No $label found after trying primary and fallbacks")
        return AssetResult(uri = null, usedFallback = true, errors = errs)
    }

    private fun resolveOne(
        logical: String,
        data: Map<String, String>,
        themeRoot: String?,
        isVideo: Boolean,
    ): AssetResult {
        // 1) If data map provides a concrete value for this logical key, use it.
        data[logical]?.let { mapped ->
            return verifyAndNormalize(mapped, themeRoot)
        }
        // 2) Already a full URI?
        if (isUri(logical)) return verifyAndNormalize(logical, themeRoot)
        // 3) Windows absolute path? normalize to file://
        if (looksLikeWindowsPath(logical)) return verifyAndNormalize("file://$logical", themeRoot)
        // 4) Theme-relative path
        if (themeRoot != null) {
            val candidate = File(themeRoot, logical)
            return if (candidate.exists()) AssetResult(uri = toFileUri(candidate))
            else AssetResult(uri = null, errors = listOf(MediaError("FILE_NOT_FOUND", "File not found: ${candidate.absolutePath}")))
        }
        // 5) Unknown logical key without mapping
        return AssetResult(
            uri = null,
            errors = listOf(MediaError(code = "UNKNOWN_LOGICAL", message = "Unknown logical path: $logical")),
        )
    }

    private fun verifyAndNormalize(uriOrPath: String, themeRoot: String?): AssetResult {
        // Try to parse as URI; if it has no scheme, treat as file path.
        return try {
            val u = java.net.URI(uriOrPath)
            val scheme = u.scheme?.lowercase()
            when (scheme) {
                null -> { // treat as file system path
                    val f = File(uriOrPath)
                    if (f.exists()) AssetResult(uri = toFileUri(f))
                    else AssetResult(uri = null, errors = listOf(MediaError("FILE_NOT_FOUND", "File not found: ${f.absolutePath}")))
                }
                "http", "https" -> {
                    val ok = remoteFetcher.exists(uriOrPath)
                    if (ok) AssetResult(uri = uriOrPath) else AssetResult(uri = null, errors = listOf(MediaError("REMOTE_UNAVAILABLE", "Remote not reachable: $uriOrPath")))
                }
                "file" -> {
                    val f = File(u)
                    if (f.exists()) AssetResult(uri = toFileUri(f))
                    else AssetResult(uri = null, errors = listOf(MediaError("FILE_NOT_FOUND", "File not found: ${f.absolutePath}")))
                }
                else -> AssetResult(uri = u.toString()) // Other schemes (content:// etc.) assumed valid
            }
        } catch (e: Exception) {
            // Fallback: assume it's a file path
            val f = File(uriOrPath)
            if (f.exists()) AssetResult(uri = toFileUri(f))
            else AssetResult(uri = null, errors = listOf(MediaError("FILE_NOT_FOUND", "File not found: ${f.absolutePath}")))
        }
    }

    private fun toFileUri(file: File): String = "file://" + file.absolutePath.replace('\\', '/')

    private fun isUri(s: String): Boolean = s.startsWith("file:") || s.contains("://") || s.startsWith("content://")
    private fun looksLikeWindowsPath(s: String): Boolean = s.length >= 3 && s[1] == ':' && (s[2] == '\\' || s[2] == '/')
}
