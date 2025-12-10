package app.gamenative.theme.model

/**
 * Declarative visual state applied to a [Card].
 */
data class State(
    /** State name (e.g., one of [StandardState] or a custom name). */
    val name: String,
    /** Visual modifiers to apply when this state is active. */
    val modifiers: Modifiers = Modifiers(),
)

/**
 * Collection of optional modifiers used to alter presentation for a state.
 */
data class Modifiers(
    /** Uniform or per-axis scaling. */
    val scale: ScaleModifier? = null,
    /** Translation offset in pixels. */
    val translate: TranslateModifier? = null,
    /** Opacity multiplier [0f..1f]. */
    val opacity: OpacityModifier? = null,
    /** Solid color overlay. */
    val overlay: OverlayModifier? = null,
    /** Border stroke. */
    val border: BorderModifier? = null,
    /** Drop shadow. */
    val shadow: ShadowModifier? = null,
    /** Gaussian blur radius. */
    val blur: BlurModifier? = null,
    /** Color tint applied to eligible layers. */
    val tint: TintModifier? = null,
    /** Swap a specific layer's media source. */
    val swapSource: SwapSourceModifier? = null,
)

/** Scales content; if [y] is null, [x] is used for both axes. */
data class ScaleModifier(
    /** Scale factor on the X axis, where 1.0 means 100%. */
    val x: FloatOrBinding,
    /** Optional scale factor on the Y axis; defaults to [x] if null. */
    val y: FloatOrBinding? = null,
)

/** Translates content by the given pixel offsets. */
data class TranslateModifier(
    /** Horizontal offset in pixels (negative allowed). */
    val dxPx: FloatOrBinding,
    /** Vertical offset in pixels (negative allowed). */
    val dyPx: FloatOrBinding,
)

/** Sets the overall opacity multiplier [0..1]. */
data class OpacityModifier(
    /** Opacity multiplier [0..1]. */
    val value: FloatOrBinding,
)

/** Draws a solid color overlay. */
data class OverlayModifier(
    /** Overlay color (ARGB). */
    val color: IntOrBinding,
)

/** Draws a border around the item bounds. */
data class BorderModifier(
    /** Stroke width in pixels. */
    val widthPx: FloatOrBinding,
    /** Stroke color (ARGB). */
    val color: IntOrBinding,
    /** Optional corner radius in pixels. */
    val cornerRadiusPx: FloatOrBinding? = null,
)

/** Renders a drop shadow. */
data class ShadowModifier(
    /** Blur radius in pixels. */
    val radiusPx: FloatOrBinding,
    /** Shadow color (ARGB). */
    val color: IntOrBinding,
    /** Offset vector for the shadow. */
    val offset: DimOffset = DimOffset(Dimension.Px(0f), Dimension.Px(0f)),
)

/** Applies a Gaussian blur effect. */
data class BlurModifier(
    /** Blur radius in pixels. */
    val radiusPx: FloatOrBinding,
)

/** Applies a tint to color-eligible layers (images/video). */
data class TintModifier(
    /** Tint color (ARGB). */
    val color: IntOrBinding,
)

/** Swaps the media source of a target layer. */
data class SwapSourceModifier(
    /** Target layer identifier to swap. */
    val layerId: String,
    /** New media source to apply in this state. */
    val newSource: MediaSource,
)

/**
 * Transition between two states with animation configuration.
 */
data class Transition(
    /** Source state name. */
    val from: String,
    /** Destination state name. */
    val to: String,
    /** Duration of the transition in milliseconds. */
    val durationMs: Int,
    /** Optional start delay in milliseconds. */
    val delayMs: Int = 0,
    /** Easing function for the animation curve. */
    val easing: Easing = Easing.EASE_IN_OUT,
    /** If true, the animation may be interrupted by a new state change. */
    val interruptible: Boolean = true,
)
