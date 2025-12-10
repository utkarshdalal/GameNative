package app.gamenative.theme.model

/**
 * Measurement unit for positions and sizes.
 *
 * - Px: absolute pixels.
 * - RelW: fraction of container width (0..1), e.g., 0.5 = 50% of width.
 * - RelH: fraction of container height (0..1), e.g., 0.5 = 50% of height.
 */
sealed class Dimension {
    /** Absolute pixels. */
    data class Px(val value: Float) : Dimension()
    /** Relative to container width [0..1]. */
    data class RelW(val fraction: Float) : Dimension()
    /** Relative to container height [0..1]. */
    data class RelH(val fraction: Float) : Dimension()
}

/** Simple 2D size in [Dimension] units. */
data class DimSize(
    /** Width dimension. */
    val width: Dimension,
    /** Height dimension. */
    val height: Dimension,
)

/** Simple 2D offset in [Dimension] units. */
data class DimOffset(
    /** X position from the left. */
    val x: Dimension,
    /** Y position from the top. */
    val y: Dimension,
)
