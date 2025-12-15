package app.gamenative.theme.model

/**
 * Base layer definition used inside a [Card].
 * All layers share common positioning and visibility properties.
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

    /** Anchor point for positioning. Determines which point of the layer the x,y refers to. */
    abstract val anchor: Anchor

    /** Visibility condition based on orientation. Defaults to ALWAYS (visible in all orientations). */
    abstract val visibility: Visibility

    /**
     * Renders an image.
     */
    data class ImageLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
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
        /**
         * How the image should be scaled within its bounds (CSS-like):
         * - "cover" = Fill container, crop if needed (default)
         * - "contain" = Fit entire image within container
         * - "stretch" / "fill" = Stretch to fill exactly
         */
        val scaleType: String = "cover",
    ) : Layer()

    /**
     * Renders a video.
     */
    data class VideoLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
        /** Source video with playback options. */
        val source: MediaSource.Video,
        /** Optional corner radius. */
        val cornerRadius: FloatOrBinding? = null,
    ) : Layer()

    /**
     * A drawable rectangle shape. Can be used as background, overlay, or any filled rectangle.
     * Supports fill color, optional border, and rounded corners.
     */
    data class RectLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
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
        /** Border/stroke width in dp. If null or 0, no border is drawn. */
        val borderWidth: FloatOrBinding? = null,
        /** Border/stroke color (ARGB). Only used if borderWidth > 0. */
        val borderColor: IntOrBinding? = null,
    ) : Layer()

    /**
     * Drop shadow rendered for the rectangle defined by [size] at [position].
     */
    data class ShadowLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
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
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
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
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
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
        /** Font weight: "normal", "bold", "light", "medium", "semibold", etc. */
        val fontWeight: String = "normal",
        /** Font style: "normal" or "italic". */
        val fontStyle: String = "normal",
    ) : Layer()

    /**
     * Backdrop effect layer (e.g., blur + optional tint behind content).
     */
    data class BackdropLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
        /** Blur radius. */
        val blurRadius: FloatOrBinding? = null,
        /** Optional tint color (ARGB). */
        val tintColor: IntOrBinding? = null,
    ) : Layer()

    /**
     * Button layer - renders a clickable button (visual only, card handles click).
     */
    data class ButtonLayer(
        override val id: String? = null,
        override val position: DimOffset,
        override val size: DimSize? = null,
        override val opacity: FloatOrBinding? = null,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
        /** Button label text or binding. */
        val text: StringOrBinding,
        /** Button background color (ARGB). */
        val backgroundColor: IntOrBinding,
        /** Button text color (ARGB). */
        val textColor: IntOrBinding,
        /** Text size. */
        val textSize: FloatOrBinding = FloatOrBinding.Literal(14f),
        /** Corner radius for button shape. */
        val cornerRadius: String? = null,
    ) : Layer()
}
