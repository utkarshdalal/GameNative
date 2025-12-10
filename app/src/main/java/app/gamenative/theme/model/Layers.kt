package app.gamenative.theme.model

/**
 * Base layer definition used inside a [Card].
 */
sealed class Layer {
    /** Optional developer identifier for the layer. */
    abstract val id: String?

    /** Common absolute position of the layer within the template canvas. */
    abstract val position: DimOffset

    /** Optional explicit size; if omitted, content/intrinsic or template size may be used. */
    abstract val size: DimSize?

    /** Opacity multiplier [0f..1f]. */
    abstract val opacity: FloatOrBinding?

    /**
     * Renders an image.
     */
    data class ImageLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        /** Source image or binding to an image path. */
        val source: MediaSource.Image,
        /**
         * Corner radius in CSS-like syntax:
         * - "8" = all corners 8
         * - "8 4" = top-left/bottom-right 8, top-right/bottom-left 4
         * - "8 4 2" = top-left 8, top-right/bottom-left 4, bottom-right 2
         * - "8 4 2 1" = top-left 8, top-right 4, bottom-right 2, bottom-left 1
         */
        val cornerRadius: String? = null,
        /** Optional tint color (ARGB). */
        val tintColor: IntOrBinding? = null,
    ) : Layer()

    /**
     * Renders a video.
     */
    data class VideoLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        /** Source video with playback options. */
        val source: MediaSource.Video,
        /** Optional corner radius. */
        val cornerRadius: FloatOrBinding? = null,
    ) : Layer()

    /**
     * Solid overlay rectangle, commonly used for dimming or highlight.
     */
    data class OverlayLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        /** Fill color (ARGB). */
        val color: IntOrBinding,
        /**
         * Corner radius in CSS-like syntax:
         * - "8" = all corners 8
         * - "8 4" = top-left/bottom-right 8, top-right/bottom-left 4
         * - "8 4 2" = top-left 8, top-right/bottom-left 4, bottom-right 2
         * - "8 4 2 1" = top-left 8, top-right 4, bottom-right 2, bottom-left 1
         */
        val cornerRadius: String? = null,
    ) : Layer()

    /**
     * Drop shadow rendered for the rectangle defined by [size] at [position].
     */
    data class ShadowLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        /** Shadow blur radius. */
        val radius: FloatOrBinding,
        /** Shadow color (ARGB). */
        val color: IntOrBinding,
        /** Shadow offset relative to [position]. */
        val offset: DimOffset = DimOffset(Dimension.Px(0f), Dimension.Px(0f)),
    ) : Layer()

    /**
     * Border stroke around the rectangle defined by [size] at [position].
     */
    data class BorderLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        /** Stroke width. */
        val strokeWidth: FloatOrBinding,
        /** Border color (ARGB). */
        val color: IntOrBinding,
        /**
         * Corner radius in CSS-like syntax:
         * - "8" = all corners 8
         * - "8 4" = top-left/bottom-right 8, top-right/bottom-left 4
         * - "8 4 2" = top-left 8, top-right/bottom-left 4, bottom-right 2
         * - "8 4 2 1" = top-left 8, top-right 4, bottom-right 2, bottom-left 1
         */
        val cornerRadius: String? = null,
    ) : Layer()

    /**
     * Text rendering layer.
     */
    data class TextLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        /** Text content or binding. */
        val text: StringOrBinding,
        /** Text color (ARGB). */
        val color: IntOrBinding,
        /** Text size. */
        val textSize: FloatOrBinding,
        /** Optional max lines for wrapping/truncation. */
        val maxLines: Int? = null,
        /** Text alignment: "left", "center", or "right". Defaults to "left". */
        val textAlign: String = "left",
    ) : Layer()

    /**
     * Backdrop effect layer (e.g., blur + optional tint behind content).
     */
    data class BackdropLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        /** Blur radius. */
        val blurRadius: FloatOrBinding? = null,
        /** Optional tint color (ARGB). */
        val tintColor: IntOrBinding? = null,
    ) : Layer()
}
