package app.gamenative.theme.runtime

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.gamenative.theme.model.Anchor
import app.gamenative.theme.model.Dimension
import app.gamenative.theme.model.DimOffset
import app.gamenative.theme.model.DimSize

/**
 * Shared utility functions for the theme engine.
 * Consolidates common operations used across LayerRenderers, ThemedGameGrid,
 * FixedElementRenderer, and LayoutEngine.
 */
object ThemeUtils {

    /**
     * Convert a [Dimension] to [Dp], resolving relative dimensions using parent size.
     *
     * @param d The dimension to convert
     * @param parentW Parent width for RelW calculations
     * @param parentH Parent height for RelH calculations
     */
    fun dimToDp(d: Dimension, parentW: Dp, parentH: Dp): Dp = when (d) {
        is Dimension.Px -> d.value.dp
        is Dimension.RelW -> parentW * d.fraction
        is Dimension.RelH -> parentH * d.fraction
        is Dimension.Unspecified -> Dp.Unspecified
    }

    /**
     * Parse CSS-like corner radius string into a [RoundedCornerShape].
     *
     * Supports 1-4 values following CSS shorthand convention:
     * - "8" = all corners 8dp
     * - "8 4" = top-left/bottom-right 8dp, top-right/bottom-left 4dp
     * - "8 4 2" = top-left 8dp, top-right/bottom-left 4dp, bottom-right 2dp
     * - "8 4 2 1" = top-left 8dp, top-right 4dp, bottom-right 2dp, bottom-left 1dp
     *
     * @param value CSS-like corner radius string, or null for no rounding
     * @return Appropriate [RoundedCornerShape]
     */
    fun parseCornerRadius(value: String?): RoundedCornerShape {
        if (value.isNullOrBlank()) return RoundedCornerShape(0.dp)
        val parts = value.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
        return when (parts.size) {
            0 -> RoundedCornerShape(0.dp)
            1 -> RoundedCornerShape(parts[0].dp)
            2 -> RoundedCornerShape(
                topStart = parts[0].dp,
                topEnd = parts[1].dp,
                bottomEnd = parts[0].dp,
                bottomStart = parts[1].dp
            )
            3 -> RoundedCornerShape(
                topStart = parts[0].dp,
                topEnd = parts[1].dp,
                bottomEnd = parts[2].dp,
                bottomStart = parts[1].dp
            )
            else -> RoundedCornerShape(
                topStart = parts[0].dp,
                topEnd = parts[1].dp,
                bottomEnd = parts[2].dp,
                bottomStart = parts[3].dp
            )
        }
    }

    /**
     * Result of calculating element placement within a parent container.
     */
    data class Placement(
        /** Computed X offset from parent's top-left */
        val x: Dp,
        /** Computed Y offset from parent's top-left */
        val y: Dp,
        /** Computed width */
        val width: Dp,
        /** Computed height */
        val height: Dp
    )

    /**
     * Calculate element placement within a parent container, accounting for anchor point.
     *
     * The anchor determines which point of the element the position refers to:
     * - TOP_LEFT: position is from top-left (default CSS behavior)
     * - TOP_RIGHT: position.x is distance from right edge inward
     * - BOTTOM_LEFT: position.y is distance from bottom edge upward
     * - CENTER: position is offset from center
     * - etc.
     *
     * @param parentSize Size of the parent container
     * @param position Raw position from theme definition
     * @param size Optional explicit size; if null, uses defaultSize
     * @param defaultSize Fallback size when size is null
     * @param anchor Anchor point for positioning
     * @return Calculated [Placement] with absolute coordinates
     */
    fun calculatePlacement(
        parentSize: DpSize,
        position: DimOffset,
        size: DimSize?,
        defaultSize: DpSize,
        anchor: Anchor
    ): Placement {
        // Resolve dimensions - keep Dp.Unspecified for unspecified dimensions
        val resolvedW = size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
        val resolvedH = size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
        
        // For anchor calculations, use defaultSize when dimension is unspecified
        val wForCalc = if (resolvedW == null || resolvedW == Dp.Unspecified) defaultSize.width else resolvedW
        val hForCalc = if (resolvedH == null || resolvedH == Dp.Unspecified) defaultSize.height else resolvedH
        
        // Final dimensions: use resolved value if specified, otherwise null markers via Dp.Unspecified
        val w = resolvedW ?: defaultSize.width
        val h = resolvedH ?: defaultSize.height
        
        val rawX = dimToDp(position.x, parentSize.width, parentSize.height)
        val rawY = dimToDp(position.y, parentSize.width, parentSize.height)

        val x = when (anchor) {
            Anchor.TOP_LEFT, Anchor.CENTER_LEFT, Anchor.BOTTOM_LEFT -> rawX
            Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> (parentSize.width - wForCalc) / 2 + rawX
            Anchor.TOP_RIGHT, Anchor.CENTER_RIGHT, Anchor.BOTTOM_RIGHT -> parentSize.width - rawX - wForCalc
        }

        val y = when (anchor) {
            Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> rawY
            Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT -> (parentSize.height - hForCalc) / 2 + rawY
            Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> parentSize.height - rawY - hForCalc
        }

        return Placement(x, y, w, h)
    }

    /**
     * Simplified placement calculation when you already have resolved dimensions.
     *
     * @param rawX Raw X position in Dp
     * @param rawY Raw Y position in Dp
     * @param elementWidth Element width in Dp
     * @param elementHeight Element height in Dp
     * @param parentWidth Parent width in Dp
     * @param parentHeight Parent height in Dp
     * @param anchor Anchor point for positioning
     * @return Calculated [Placement] with absolute coordinates
     */
    fun calculateAnchoredPosition(
        rawX: Dp,
        rawY: Dp,
        elementWidth: Dp,
        elementHeight: Dp,
        parentWidth: Dp,
        parentHeight: Dp,
        anchor: Anchor
    ): Placement {
        val x = when (anchor) {
            Anchor.TOP_LEFT, Anchor.CENTER_LEFT, Anchor.BOTTOM_LEFT -> rawX
            Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> (parentWidth - elementWidth) / 2 + rawX
            Anchor.TOP_RIGHT, Anchor.CENTER_RIGHT, Anchor.BOTTOM_RIGHT -> parentWidth - elementWidth - rawX
        }

        val y = when (anchor) {
            Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> rawY
            Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT -> (parentHeight - elementHeight) / 2 + rawY
            Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> parentHeight - elementHeight - rawY
        }

        return Placement(x, y, elementWidth, elementHeight)
    }

    /**
     * Calculate element position where x,y represents the ABSOLUTE position of the anchor point.
     * 
     * Unlike [calculateAnchoredPosition] which treats x,y as offsets from edges (CSS-like),
     * this function treats x,y as the exact coordinates where the anchor point should be placed.
     * 
     * For example:
     * - topLeft with x=80, y=100: element's top-left corner is at (80, 100)
     * - topRight with x=280, y=100: element's top-right corner is at (280, 100), so left edge = 280 - width
     * - center with x=200, y=150: element's center is at (200, 150)
     * 
     * @param anchorX Absolute X coordinate where the anchor point should be
     * @param anchorY Absolute Y coordinate where the anchor point should be
     * @param elementWidth Element width
     * @param elementHeight Element height
     * @param anchor Which point of the element the coordinates refer to
     * @return Calculated position for the element's top-left corner
     */
    fun calculateAbsoluteAnchoredPosition(
        anchorX: Dp,
        anchorY: Dp,
        elementWidth: Dp,
        elementHeight: Dp,
        anchor: Anchor
    ): Placement {
        val x = when (anchor) {
            Anchor.TOP_LEFT, Anchor.CENTER_LEFT, Anchor.BOTTOM_LEFT -> anchorX
            Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> anchorX - elementWidth / 2
            Anchor.TOP_RIGHT, Anchor.CENTER_RIGHT, Anchor.BOTTOM_RIGHT -> anchorX - elementWidth
        }

        val y = when (anchor) {
            Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> anchorY
            Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT -> anchorY - elementHeight / 2
            Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> anchorY - elementHeight
        }

        return Placement(x, y, elementWidth, elementHeight)
    }

}

// Convenience top-level function aliases for cleaner imports
fun dimToDp(d: Dimension, parentW: Dp, parentH: Dp): Dp = ThemeUtils.dimToDp(d, parentW, parentH)
fun parseCornerRadius(value: String?): RoundedCornerShape = ThemeUtils.parseCornerRadius(value)

