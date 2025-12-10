package app.gamenative.theme.media

import app.gamenative.theme.model.Binding
import app.gamenative.theme.model.IntOrBinding
import app.gamenative.theme.model.MediaSource
import app.gamenative.theme.model.StringOrBinding

/**
 * Result of resolving a theme MediaSource into a concrete, renderable asset.
 */
sealed class ResolvedMedia {
    data class Image(
        val uri: String?,
        val usedFallback: Boolean,
        val errors: List<MediaError> = emptyList(),
    ) : ResolvedMedia()

    data class Video(
        val uri: String?,
        val posterUri: String?,
        val autoplay: Boolean,
        val loop: Boolean,
        val muted: Boolean,
        val usedFallback: Boolean,
        val errors: List<MediaError> = emptyList(),
    ) : ResolvedMedia()
}

/**
 * MediaSourceManager resolves image and video media sources from the theme model.
 * It applies graceful fallbacks (poster/fallback image) and keeps a simple cache for images.
 */
class MediaSourceManager(
    private val assetResolver: AssetResolver = AssetResolver(),
) {
    /**
     * Resolve the given [MediaSource] into [ResolvedMedia]. Never throws; errors are returned inline.
     * @param allowVideo If false, videos are not allowed and poster (or fallback image) will be returned instead.
     * @param bindingResolver Resolves a [Binding] to a concrete string (e.g., mapping `game.capsule` to a URI). May return null.
     * @param themeRoot Optional theme directory used to resolve relative paths.
     */
    fun resolve(
        media: MediaSource,
        allowVideo: Boolean,
        bindingResolver: (Binding) -> String?,
        themeRoot: String? = null,
    ): ResolvedMedia = when (media) {
        is MediaSource.Image -> resolveImage(media, bindingResolver, themeRoot)
        is MediaSource.Video -> resolveVideo(media, allowVideo, bindingResolver, themeRoot)
    }

    private fun resolveImage(
        image: MediaSource.Image,
        bindingResolver: (Binding) -> String?,
        themeRoot: String?,
    ): ResolvedMedia.Image {
        val primary = eval(image.src, bindingResolver)
        val fb = image.fallback?.let { eval(it, bindingResolver) }
        val res = assetResolver.resolveImage(
            logical = primary,
            fallbacks = listOf(fb),
            data = emptyMap(),
            themeRoot = themeRoot,
        )
        return ResolvedMedia.Image(uri = res.uri, usedFallback = res.usedFallback, errors = res.errors)
    }

    private fun resolveVideo(
        video: MediaSource.Video,
        allowVideo: Boolean,
        bindingResolver: (Binding) -> String?,
        themeRoot: String?,
    ): ResolvedMedia {
        val src = eval(video.src, bindingResolver)
        val poster = video.poster?.let { eval(it, bindingResolver) }
        val fbImg = video.fallbackImage?.let { eval(it, bindingResolver) }

        if (!allowVideo) {
            // Return poster (or fallback image) as an Image result with a warning.
            val posterRes = assetResolver.resolveImage(poster, fallbacks = listOf(fbImg), themeRoot = themeRoot)
            val errs = posterRes.errors + MediaError("VIDEO_NOT_ALLOWED", "Video playback is not allowed in this context; using poster/fallback image")
            return ResolvedMedia.Image(uri = posterRes.uri, usedFallback = posterRes.usedFallback, errors = errs)
        }

        // Try resolving the video source.
        val videoRes = assetResolver.resolveVideo(src, themeRoot = themeRoot)
        // Always resolve poster if provided (non-blocking); it is useful before first frame.
        val posterRes = if (poster != null) assetResolver.resolveImage(poster, themeRoot = themeRoot) else AssetResult(uri = null)

        return if (videoRes.uri != null) {
            ResolvedMedia.Video(
                uri = videoRes.uri,
                posterUri = posterRes.uri,
                autoplay = video.autoplay,
                loop = video.loop,
                muted = video.muted,
                usedFallback = false,
                errors = (videoRes.errors + posterRes.errors),
            )
        } else {
            // Video unavailable; fall back to fallbackImage, else poster.
            val fb = assetResolver.resolveImage(fbImg, themeRoot = themeRoot)
            if (fb.uri != null) {
                val errs = videoRes.errors + fb.errors + MediaError("VIDEO_FALLBACK_IMAGE_USED", "Video unavailable; using fallback image")
                ResolvedMedia.Image(uri = fb.uri, usedFallback = true, errors = errs)
            } else {
                val errs = videoRes.errors + posterRes.errors + MediaError("VIDEO_UNAVAILABLE", "Video unavailable; using poster if available")
                ResolvedMedia.Image(uri = posterRes.uri, usedFallback = true, errors = errs)
            }
        }
    }

    private fun eval(value: StringOrBinding, bindingResolver: (Binding) -> String?): String? = when (value) {
        is StringOrBinding.Literal -> value.value
        is StringOrBinding.Ref -> bindingResolver(value.binding)
    }
}
