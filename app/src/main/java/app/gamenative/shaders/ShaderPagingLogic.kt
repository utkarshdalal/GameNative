package app.gamenative.shaders

/**
 * Pure paging decisions for the shader browser (spec 2026-08-12, M1 — paginação por
 * gamepad). Extracted as a JVM-testable object (pattern: ShaderDoubleClickLogic /
 * GamepadStickLogic) so the L1/R1 (single page, P2-style repeat gate) and L2/R2
 * (continuous paging, P5-style repeat) behavior is decided without Compose.
 */
object ShaderPagingLogic {

    /**
     * Next page after a [delta] step (typically -1/+1), clamped to the valid range:
     * never below 0 and never past the last page that actually has content
     * (0-based; `ceil(count / pageSize) - 1`, floor at 0 for empty lists).
     */
    fun decidePage(current: Int, delta: Int, count: Int, pageSize: Int): Int {
        if (count <= 0 || pageSize <= 0) return 0
        val maxPage = maxOf(0, ((count + pageSize - 1) / pageSize) - 1)
        return (current + delta).coerceIn(0, maxPage)
    }

    /**
     * Clamps a remembered focus-row index to a valid slot of the NEW page composition.
     * The clamp exists so a row that existed on the old page but not on the new one
     * (e.g. the "Show more" row of the last page) never requests a missing focus slot
     * — the focus protocol falls back to the last row of the new page instead.
     */
    fun clampRowIndex(index: Int, rowsInPage: Int): Int =
        if (rowsInPage <= 0) 0 else index.coerceIn(0, rowsInPage - 1)
}
