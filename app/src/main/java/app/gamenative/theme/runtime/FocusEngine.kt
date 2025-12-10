package app.gamenative.theme.runtime

import androidx.compose.runtime.*
import app.gamenative.theme.model.Direction
import app.gamenative.theme.model.SelectionMode
import kotlin.math.max
import kotlin.math.min

/**
 * Focus & navigation engine. Pure index math + lightweight Compose state helpers.
 *
 * Supports:
 * - Stationary vs moving selection
 * - Rows/cols (grids), wrap, snap-to-cell
 * - Page size and centered selection for carousels
 * - Persisting last focus per container key
 */
object FocusEngine {

    // ---- Public Config/State types ----

    data class Config(
        val rows: Int = 1,                 // for grids: rows per page (visible)
        val cols: Int = 1,                 // for grids: columns per page (visible)
        val wrapX: Boolean = true,         // wrap on horizontal edges
        val wrapY: Boolean = false,        // wrap on vertical edges
        val snapToCell: Boolean = true,    // ensure selection remains inside visible window
        val pageSize: Int? = null,         // for carousels: number of visible items
        val selectionMode: SelectionMode = SelectionMode.MOVING,
        val centeredSelection: Boolean = false, // for stationary carousel: keep selection centered when possible
    ) {
        init {
            require(rows >= 1) { "rows must be >= 1" }
            require(cols >= 1) { "cols must be >= 1" }
            pageSize?.let { require(it >= 1) { "pageSize must be >= 1" } }
        }
    }

    data class State(
        val totalItems: Int,
        val config: Config,
        val containerKey: String? = null,
        val selectedIndex: Int = 0,
        val firstVisibleIndex: Int = 0, // start of the visible window
    ) {
        init {
            require(totalItems >= 0)
            require(selectedIndex >= 0)
            require(firstVisibleIndex >= 0)
        }

        val visibleCount: Int
            get() = config.pageSize ?: (config.rows * config.cols)

        fun visibleRange(): IntRange {
            if (totalItems == 0) return IntRange.EMPTY
            val start = firstVisibleIndex.coerceIn(0, max(0, totalItems - 1))
            val endExclusive = min(totalItems, start + visibleCount)
            return start until endExclusive
        }

        fun ensureSelectionVisible(): State {
            if (totalItems == 0) return this
            val range = visibleRange()
            if (selectedIndex in range) return this
            // Move window to include selection, snapping by page if needed
            val page = visibleCount
            val newFirst = when {
                selectedIndex < range.first -> if (config.selectionMode == SelectionMode.STATIONARY) (selectedIndex / page) * page else selectedIndex
                else -> {
                    // selected beyond end
                    val start = selectedIndex - (page - 1)
                    if (config.selectionMode == SelectionMode.STATIONARY) (start / page) * page else start
                }
            }.coerceIn(0, max(0, totalItems - page))
            return copy(firstVisibleIndex = newFirst)
        }
    }

    // ---- Persist last focus per container ----

    private val lastFocusByKey = mutableStateMapOf<String, Int>()

    fun readLastFocus(containerKey: String): Int? = lastFocusByKey[containerKey]
    fun writeLastFocus(containerKey: String, index: Int) {
        lastFocusByKey[containerKey] = index
    }

    // ---- Navigation operations ----

    fun moveHorizontal(state: State, dir: Direction): State {
        return when (dir) {
            Direction.LEFT -> moveLeft(state)
            Direction.RIGHT -> moveRight(state)
            else -> state
        }
    }

    fun moveVertical(state: State, dir: Direction): State {
        return when (dir) {
            Direction.UP -> moveUp(state)
            Direction.DOWN -> moveDown(state)
            else -> state
        }
    }

    fun page(state: State, forward: Boolean): State {
        val page = state.visibleCount
        val delta = if (forward) page else -page
        return setSelectedIndex(state, state.selectedIndex + delta, axis = Axis.PRIMARY)
    }

    private enum class Axis { PRIMARY, H, V }

    fun moveLeft(s: State): State = setSelectedIndex(s, s.selectedIndex - 1, Axis.H)
    fun moveRight(s: State): State = setSelectedIndex(s, s.selectedIndex + 1, Axis.H)
    fun moveUp(s: State): State = setSelectedIndex(s, s.selectedIndex - s.config.cols, Axis.V)
    fun moveDown(s: State): State = setSelectedIndex(s, s.selectedIndex + s.config.cols, Axis.V)

    private fun setSelectedIndex(s: State, target: Int, axis: Axis): State {
        if (s.totalItems == 0) return s
        val normalized = normalizeTargetIndex(s, target, axis)
        var newState = s.copy(selectedIndex = normalized)

        // Window/scroll management
        newState = when (s.config.selectionMode) {
            SelectionMode.MOVING -> ensureVisibleMoving(newState)
            SelectionMode.STATIONARY -> ensureVisibleStationary(newState)
        }

        // Persist
        s.containerKey?.let { writeLastFocus(it, newState.selectedIndex) }
        return newState
    }

    private fun normalizeTargetIndex(s: State, target: Int, axis: Axis): Int {
        if (s.totalItems == 0) return 0
        val cols = s.config.cols
        val rows = s.config.rows
        var t = target
        // Horizontal movement:
        // - If single-row (e.g., carousel) or explicit pageSize set -> move across total range.
        // - Otherwise treat as grid row-local movement.
        if (axis == Axis.H) {
            if (s.config.rows == 1 || s.config.pageSize != null) {
                // Global wrap/clamp
                return when {
                    t < 0 -> if (s.config.wrapX) s.totalItems - 1 else 0
                    t >= s.totalItems -> if (s.config.wrapX) 0 else s.totalItems - 1
                    else -> t
                }
            } else {
                val row = s.selectedIndex / cols
                val rowStart = row * cols
                val rowEnd = min(rowStart + cols - 1, s.totalItems - 1)
                if (t < rowStart) {
                    t = if (s.config.wrapX) rowEnd else rowStart
                } else if (t > rowEnd) {
                    t = if (s.config.wrapX) rowStart else rowEnd
                }
                return t.coerceIn(0, s.totalItems - 1)
            }
        }
        // Vertical wrapping per column
        if (axis == Axis.V) {
            val col = s.selectedIndex % cols
            val maxRow = (s.totalItems - 1) / cols
            var row = s.selectedIndex / cols + if (target > s.selectedIndex) 1 else -1
            if (row < 0) row = if (s.config.wrapY) maxRow else 0
            if (row > maxRow) row = if (s.config.wrapY) 0 else maxRow
            val idx = row * cols + col
            return idx.coerceIn(0, s.totalItems - 1)
        }
        // Primary axis paging simply clamps/wraps globally
        return when {
            target < 0 -> if (s.config.wrapX || s.config.wrapY) s.totalItems - 1 else 0
            target >= s.totalItems -> if (s.config.wrapX || s.config.wrapY) 0 else s.totalItems - 1
            else -> target
        }
    }

    private fun ensureVisibleMoving(s: State): State {
        if (!s.config.snapToCell) return s
        val page = s.visibleCount
        val range = s.visibleRange()
        if (s.selectedIndex in range) return s
        val newFirst = when {
            s.selectedIndex < range.first -> s.selectedIndex
            else -> s.selectedIndex - (page - 1)
        }.coerceIn(0, max(0, s.totalItems - page))
        return s.copy(firstVisibleIndex = newFirst)
    }

    private fun ensureVisibleStationary(s: State): State {
        val page = s.visibleCount
        var anchorOffset = 0
        if (s.config.centeredSelection && page > 1) {
            anchorOffset = page / 2
        }
        val desiredFirst = (s.selectedIndex - anchorOffset).coerceIn(0, max(0, s.totalItems - page))
        return s.copy(firstVisibleIndex = desiredFirst)
    }

    // ---- Compose helpers ----

    @Composable
    fun rememberFocusState(
        containerKey: String,
        totalItems: Int,
        config: Config,
    ): MutableState<State> {
        val initialIndex = readLastFocus(containerKey) ?: 0
        val state = remember(containerKey, totalItems, config) {
            mutableStateOf(State(totalItems, config, containerKey, selectedIndex = initialIndex, firstVisibleIndex = 0))
        }
        // Keep totalItems updated while preserving selection in range
        LaunchedEffect(totalItems) {
            val cur = state.value
            val newSel = cur.selectedIndex.coerceIn(0, max(0, totalItems - 1))
            state.value = cur.copy(totalItems = totalItems, selectedIndex = newSel).ensureSelectionVisible()
        }
        return state
    }

    // Utility to compute index from (row, col) and vice versa
    fun indexOf(row: Int, col: Int, cols: Int): Int = row * cols + col
    fun rowOf(index: Int, cols: Int): Int = index / cols
    fun colOf(index: Int, cols: Int): Int = index % cols
}
