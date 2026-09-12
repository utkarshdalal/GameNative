package app.gamenative.ui.component.dialog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Explicit item-by-item d-pad selection for a Steam-Controller editor dialog. Compose's own focus traversal
 * (`moveFocus`) proved unreliable inside these Dialog + FlowRow + verticalScroll layouts (the key was consumed but
 * focus never moved — confirmed on-device), so navigable controls are modelled as a grid of (line, col) cells: d-pad
 * up/down changes [line], left/right changes [col] within that line, and A activates the selected cell. Works for ANY
 * controller (it only needs d-pad + A), independent of the pad cursor which still points/clicks freely.
 *
 * Each navigable control wraps its content in [ScNavItem], which registers the cell + its activation, draws the
 * selection highlight, and scrolls itself into view when selected. Lines are numbered by the caller in visual order.
 */
class ScNavState {
    var line by mutableIntStateOf(0)
        private set
    var col by mutableIntStateOf(0)
        private set

    // Bounds are derived dynamically from the registered cells (not cached), so navigation only ever visits cells
    // that currently exist — this makes it robust to content that changes under it (e.g. switching command-picker
    // tabs disposes one set of cells and registers another).
    private val activators = mutableStateMapOf<Long, () -> Unit>()
    // Cells that consume left/right themselves (e.g. a slider: d-pad L/R adjusts its value) instead of moving columns.
    private val horizontals = mutableStateMapOf<Long, (Int) -> Unit>()

    private fun keyOf(l: Int, c: Int) = (l.toLong() shl 32) or (c.toLong() and 0xffffffffL)
    private fun lineOf(k: Long) = (k ushr 32).toInt()
    private fun colOf(k: Long) = (k and 0xffffffffL).toInt()
    private fun lines() = activators.keys.map(::lineOf).distinct().sorted()
    private fun colsOn(l: Int) = activators.keys.filter { lineOf(it) == l }.map(::colOf).distinct().sorted()

    fun register(l: Int, c: Int, onHorizontal: ((Int) -> Unit)? = null, onActivate: () -> Unit) {
        activators[keyOf(l, c)] = onActivate
        if (onHorizontal != null) horizontals[keyOf(l, c)] = onHorizontal else horizontals.remove(keyOf(l, c))
    }
    fun unregister(l: Int, c: Int) { activators.remove(keyOf(l, c)); horizontals.remove(keyOf(l, c)) }

    fun moveVertical(d: Int) {
        val ls = lines()
        if (ls.isEmpty()) return
        val idx = ls.indexOf(line).let { if (it >= 0) it else ls.indexOfFirst { l -> l >= line }.let { j -> if (j < 0) ls.lastIndex else j } }
        line = ls[(idx + d).coerceIn(0, ls.lastIndex)]
        val cs = colsOn(line)
        if (cs.isNotEmpty() && col !in cs) col = cs.minByOrNull { kotlin.math.abs(it - col) } ?: cs.first()
    }

    fun moveHorizontal(d: Int) {
        // If the selected cell adjusts itself with L/R (a slider), let it consume the input instead of moving columns.
        horizontals[keyOf(line, col)]?.let { it(d); return }
        val cs = colsOn(line)
        if (cs.isEmpty()) return
        val idx = cs.indexOf(col).let { if (it < 0) 0 else it }
        col = cs[(idx + d).coerceIn(0, cs.lastIndex)]
    }

    /** Snap selection to the first existing cell (call when the navigable content is replaced, e.g. on a tab change). */
    fun reset() {
        val ls = lines()
        line = ls.firstOrNull() ?: 0
        col = colsOn(line).firstOrNull() ?: 0
    }

    fun activate() { activators[keyOf(line, col)]?.invoke() }

    fun isSelected(l: Int, c: Int) = line == l && col == c
}

/**
 * Wraps a navigable editor control: registers its (line,col) cell + activation with [state], highlights it when
 * selected, and scrolls it into view (within an enclosing verticalScroll) when selected. Pass [modifier] =
 * `Modifier.fillMaxWidth()` for full-width rows; leave default for inline chips so they keep their flow width.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScNavItem(
    state: ScNavState,
    line: Int,
    col: Int = 0,
    modifier: Modifier = Modifier,
    /** Optional L/R handler so this cell adjusts itself with d-pad left/right (a slider) instead of changing columns. */
    onHorizontal: ((Int) -> Unit)? = null,
    onActivate: () -> Unit,
    content: @Composable () -> Unit,
) {
    val bring = remember { BringIntoViewRequester() }
    // Register STABLE wrappers that always call the latest lambdas: the DisposableEffect only re-runs when (line,col)
    // change, so a lambda capturing mutable state (e.g. the selected action-set index) would otherwise be frozen at
    // first registration — which made "Delete" always remove the initially-selected set.
    val latestActivate = rememberUpdatedState(onActivate)
    val latestHorizontal = rememberUpdatedState(onHorizontal)
    DisposableEffect(line, col) {
        state.register(line, col, onHorizontal = if (latestHorizontal.value != null) { d -> latestHorizontal.value?.invoke(d) } else null) { latestActivate.value() }
        onDispose { state.unregister(line, col) }
    }
    val selected = state.isSelected(line, col)
    LaunchedEffect(selected) { if (selected) runCatching { bring.bringIntoView() } }
    // Radius matches the chip buttons (16dp) so the highlight hugs their corners instead of leaving gaps.
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .bringIntoViewRequester(bring)
            // A subtle fill keeps the selection readable on plain rows (the QuickMenu items sit on their own cards).
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape) else Modifier)
            // The QuickMenu's rotating gradient ring, driven synchronously by [selected] (see ScSelectionRing).
            .scSelectionRing(selected, shape, width = 2.dp),
    ) {
        // QuickMenu pattern: white content normally, primary (purple) when selected. Rows/labels that don't set an
        // explicit color inherit this; chips/summaries that set their own color are unaffected.
        val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * The standard controller-navigable container for an SC editor dialog's content: a focusable [Column] that drives
 * [state] (d-pad up/down = lines, left/right = columns or a slider's value, A = activate) and registers on the
 * [ScNavDialogStack] so the SC bridge routes keys here and B runs [onBack]. Optional [onBumper] handles LB/RB. Wrap
 * the dialog's content in this and place [ScNavItem]s inside.
 */
/**
 * Captures + holds keyboard focus for a controller-nav dialog root. A freshly-opened Compose Dialog window isn't
 * focused on frame 1 (so `onPreviewKeyEvent` wouldn't fire), so this hammers `requestFocus` until it lands, then keeps
 * the node focusable. Bundles the `focusRequester + onFocusChanged + focusable` + retry loop every SC dialog root
 * repeated; pair it with your own `.onPreviewKeyEvent { … }`.
 */
@Composable
fun Modifier.scCaptureFocus(): Modifier {
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(80) { if (hasFocus) return@LaunchedEffect; runCatching { focus.requestFocus() }; delay(25) }
    }
    return this.focusRequester(focus).onFocusChanged { hasFocus = it.hasFocus }.focusable()
}

@Composable
fun ScNavDialogColumn(
    state: ScNavState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onBumper: ((Int) -> Unit)? = null,
    /** Optional Y-button handler (e.g. open a Help dialog); null = ignored. */
    onHelp: (() -> Unit)? = null,
    /** Optional Start-button handler (e.g. close the editor back to the game); null = ignored. */
    onClose: (() -> Unit)? = null,
    /** When true, the content scrolls inside the [ScScrollbar] (the same auto-hiding accent bar the main bindings list
     *  uses) instead of the caller adding its own scroll modifier — keeps every SC dialog's scrollbar identical. */
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val keyHandling = Modifier
        .scCaptureFocus()
        .onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (ev.key) {
                Key.DirectionDown -> { state.moveVertical(1); true }
                Key.DirectionUp -> { state.moveVertical(-1); true }
                Key.DirectionRight -> { state.moveHorizontal(1); true }
                Key.DirectionLeft -> { state.moveHorizontal(-1); true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> { state.activate(); true }
                Key.ButtonL1 -> if (onBumper != null) { onBumper(-1); true } else false
                Key.ButtonR1 -> if (onBumper != null) { onBumper(1); true } else false
                Key.ButtonY -> if (onHelp != null) { onHelp(); true } else false
                Key.ButtonStart -> if (onClose != null) { onClose(); true } else false
                else -> false
            }
        }
    val body: @Composable ColumnScope.() -> Unit = {
        ScNavDialogCapture(onBack = onBack)
        content()
    }
    if (scrollable) {
        val scroll = rememberScrollState()
        ScScrollbar(scroll, modifier) {
            // Inset the content from the right so the scrollbar (on the outer edge) doesn't overlap chips/rows.
            Column(Modifier.verticalScroll(scroll).padding(end = 18.dp).then(keyHandling), content = body)
        }
    } else {
        Column(modifier.then(keyHandling), content = body)
    }
}
