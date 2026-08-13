package app.gamenative.shaders

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Browser paging decisions (spec 2026-08-12, M1): L1/R1 page one step (repeat-gated
 * by the UI), L2/R2 page continuously; the decision never leaves the valid range and
 * the focus clamp never points at a slot that does not exist on the new page.
 */
class ShaderPagingLogicTest {

    // ── decidePage: clamping ──

    @Test
    fun `page 0 with negative delta stays on page 0`() {
        assertEquals(0, ShaderPagingLogic.decidePage(current = 0, delta = -1, count = 25, pageSize = 12))
        assertEquals(0, ShaderPagingLogic.decidePage(current = 0, delta = -5, count = 25, pageSize = 12))
    }

    @Test
    fun `last page with positive delta stays on last page`() {
        // 25 presets / 12 per page -> pages 0, 1, 2 (maxPage 2)
        assertEquals(2, ShaderPagingLogic.decidePage(current = 2, delta = 1, count = 25, pageSize = 12))
        assertEquals(2, ShaderPagingLogic.decidePage(current = 2, delta = 3, count = 25, pageSize = 12))
    }

    @Test
    fun `normal step advances and rewinds exactly one page`() {
        assertEquals(1, ShaderPagingLogic.decidePage(current = 0, delta = 1, count = 25, pageSize = 12))
        assertEquals(1, ShaderPagingLogic.decidePage(current = 2, delta = -1, count = 25, pageSize = 12))
    }

    // ── decidePage: maxPage boundaries ──

    @Test
    fun `exact multiple of page size has one fewer page`() {
        // 24 presets / 12 per page -> pages 0 and 1 only (no empty last page)
        assertEquals(1, ShaderPagingLogic.decidePage(current = 0, delta = 1, count = 24, pageSize = 12))
        assertEquals(1, ShaderPagingLogic.decidePage(current = 2, delta = 0, count = 24, pageSize = 12))
    }

    @Test
    fun `single page list never moves`() {
        assertEquals(0, ShaderPagingLogic.decidePage(current = 0, delta = 1, count = 12, pageSize = 12))
        assertEquals(0, ShaderPagingLogic.decidePage(current = 0, delta = 1, count = 1, pageSize = 12))
    }

    @Test
    fun `empty list stays on page 0`() {
        assertEquals(0, ShaderPagingLogic.decidePage(current = 0, delta = 1, count = 0, pageSize = 12))
    }

    @Test
    fun `invalid page size stays on page 0`() {
        assertEquals(0, ShaderPagingLogic.decidePage(current = 1, delta = 1, count = 25, pageSize = 0))
        assertEquals(0, ShaderPagingLogic.decidePage(current = 1, delta = 1, count = 25, pageSize = -1))
    }

    // ── clampRowIndex: focus after paging ──

    @Test
    fun `row inside the new page is kept`() {
        assertEquals(3, ShaderPagingLogic.clampRowIndex(index = 3, rowsInPage = 10))
        assertEquals(0, ShaderPagingLogic.clampRowIndex(index = 0, rowsInPage = 1))
    }

    @Test
    fun `row beyond the new page clamps to the last row`() {
        // "Show more" row existed on the old page, not on the new one
        assertEquals(11, ShaderPagingLogic.clampRowIndex(index = 12, rowsInPage = 12))
        assertEquals(4, ShaderPagingLogic.clampRowIndex(index = 6, rowsInPage = 5))
    }

    @Test
    fun `negative row clamps to first row`() {
        assertEquals(0, ShaderPagingLogic.clampRowIndex(index = -1, rowsInPage = 10))
    }

    @Test
    fun `empty page clamps to row 0`() {
        assertEquals(0, ShaderPagingLogic.clampRowIndex(index = 5, rowsInPage = 0))
    }
}
