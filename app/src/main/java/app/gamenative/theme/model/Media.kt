package app.gamenative.theme.model

/**
 * Describes a media source used by layers (image/video).
 */
sealed class MediaSource {
    /**
     * An image source, typically resolved to a URI by the AssetResolver at render time.
     * The value may be a literal string or a binding (e.g., `game.capsule`).
     */
    data class Image(
        /** Logical path or URI for the image. */
        val src: StringOrBinding,
        /** Optional fallback image to use if [src] fails to load. */
        val fallback: StringOrBinding? = null,
    ) : MediaSource()

    /**
     * A video source with safe defaults and options to control playback policy.
     */
    data class Video(
        /** Logical path or URI for the video. */
        val src: StringOrBinding,
        /** Poster image shown before the first frame or when paused. */
        val poster: StringOrBinding? = null,
        /** Whether video plays automatically when eligible (focused/selected and visible). */
        val autoplay: Boolean = false,
        /** Whether the video loops when it reaches the end. */
        val loop: Boolean = true,
        /** Whether audio is muted by default. */
        val muted: Boolean = true,
        /** Preload behavior for buffering data. */
        val preload: VideoPreloadPolicy = VideoPreloadPolicy.METADATA,
        /** Optional fallback image when video cannot play. */
        val fallbackImage: StringOrBinding? = null,
    ) : MediaSource()
}
