package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.steamcontroller.ScConfigStore
import app.gamenative.steamcontroller.ScKeyboardOverlayView
import app.gamenative.steamcontroller.ScKeyboardSpec
import app.gamenative.steamcontroller.ScMenuLabelTool
import app.gamenative.steamcontroller.ScMenuLocation
import app.gamenative.steamcontroller.ScMenuOverlayView
import app.gamenative.steamcontroller.ScMenuSpec
import app.gamenative.steamcontroller.ScOverlayLayout
import app.gamenative.steamcontroller.ScOverlayStore
import app.gamenative.ui.util.SnackbarManager
import kotlin.math.ceil
import kotlin.math.sqrt

/** Which overlay the editor is placing/sizing. */
enum class ScOverlayTarget { MENU, KEYBOARD }

/**
 * Drag + pinch live editor for an overlay's placement/size ([ScOverlayLayout], persisted by [ScOverlayStore]).
 * Shows the real overlay view over a neutral backdrop so the user sees exactly what they'll get in-game:
 * one-finger drag repositions, pinch scales. [target] picks the menu HUD ([ScMenuOverlayView]) or the split
 * keyboard ([ScKeyboardOverlayView]) — each has its own store namespace. [storeKey] is the game's container/appId
 * for a per-game override, or [ScOverlayStore.DEFAULT_KEY] when [isShared] (the global default).
 */
@Composable
fun ScOverlayEditorDialog(
    storeKey: String,
    isShared: Boolean,
    target: ScOverlayTarget,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = target == ScOverlayTarget.KEYBOARD
    // MENU only: the actual menus in this game's config (one per host surface, with their real kind + slot labels)
    // so we place each menu 1-by-1 and preview exactly what it is. Empty = the config has no radial/touch menus.
    val presentMenus = remember(storeKey) {
        if (keyboard || isShared) emptyList()
        else ScConfigStore.rawConfig(context, storeKey)?.let { ScMenuLabelTool.enumerate(it) }?.distinctBy { it.location } ?: emptyList()
    }
    // Placement selector = the present menus (no aggregate "All" — you place each individually). Default to the first.
    val menuOptions: List<ScMenuLocation?> = if (presentMenus.isEmpty()) listOf(null) else presentMenus.map { it.location }
    // Keyed by storeKey so switching configs re-seeds the selection from the new store's menus (never a stale one).
    var selectedMenu by remember(storeKey) { mutableStateOf(menuOptions.first()) }
    fun stored(sel: ScMenuLocation?): ScOverlayLayout = when {
        keyboard -> ScOverlayStore.forKeyboard(context, storeKey)
        sel != null -> ScOverlayStore.forMenu(context, storeKey, sel.name)
        else -> ScOverlayStore.forKey(context, storeKey)
    }
    var layout by remember { mutableStateOf(stored(selectedMenu)) }
    // Reload the layout when the selected menu changes (so each menu edits its own stored placement).
    LaunchedEffect(selectedMenu) { layout = stored(selectedMenu) }
    val menuView = remember { if (keyboard) null else ScMenuOverlayView(context) }
    val kbView = remember { if (keyboard) ScKeyboardOverlayView(context) else null }

    // Build the real preview spec for the selected menu (its actual kind + slot labels); the generic sample only
    // stands in when the config has no per-surface menus.
    fun specFor(sel: ScMenuLocation?): ScMenuSpec {
        val d = presentMenus.firstOrNull { it.location == sel } ?: return sampleSpec(radial = true)
        val labels = d.slotDefaults.ifEmpty { listOf("1") }
        return if (d.kind == "Radial") {
            ScMenuSpec(ScMenuSpec.Kind.RADIAL, labels, 0, 0, 0)
        } else {
            val cols = ceil(sqrt(labels.size.toDouble())).toInt().coerceAtLeast(1)
            val rows = ceil(labels.size.toDouble() / cols).toInt().coerceAtLeast(1)
            ScMenuSpec(ScMenuSpec.Kind.GRID, labels, cols, rows, 0)
        }
    }

    // Push the current layout + the selected menu's real sample into the overlay view whenever either changes.
    LaunchedEffect(layout, selectedMenu) {
        if (keyboard) {
            kbView!!.setLayout(layout)
            kbView.show(ScKeyboardSpec(leftCursor = 7, rightCursor = 2, shift = false))
        } else {
            menuView!!.setLayout(layout)
            menuView.showMenu(specFor(selectedMenu))
        }
    }

    // Auto-save: persist to the current scope on every user change (drag/pinch/stick/trigger/reset) — no Save button.
    // Loading a different menu's stored layout (the LaunchedEffect above) does NOT save, so opening + backing out never
    // writes a spurious override. ponytail: saves each gesture frame; SharedPreferences.apply() coalesces — debounce
    // only if it ever matters.
    fun saveLayout(sel: ScMenuLocation?, l: ScOverlayLayout) {
        when {
            keyboard -> ScOverlayStore.saveKeyboard(context, storeKey, l)
            sel != null -> ScOverlayStore.saveMenu(context, storeKey, sel.name, l)
            else -> ScOverlayStore.save(context, storeKey, l)
        }
    }
    fun applyLayout(l: ScOverlayLayout) { val c = l.clamped(); layout = c; saveLayout(selectedMenu, c) }

    // LB/RB cycle which menu you're placing (menuOptions from above). One entry = no cycle.
    val cycleMenu: (Int) -> Unit = { d ->
        if (menuOptions.size > 1) {
            val i = menuOptions.indexOf(selectedMenu).coerceAtLeast(0)
            selectedMenu = menuOptions[((i + d) % menuOptions.size + menuOptions.size) % menuOptions.size]
        }
    }
    // Controller hotkey for the touch-only Reset (stick/d-pad move the overlay, so a button — not focus-nav — triggers
    // it). Use-default stays touch-only (rare/destructive).
    val doReset = { applyLayout(if (keyboard) ScOverlayStore.KEYBOARD_DEFAULT else ScOverlayLayout()) }
    val selectedKind = presentMenus.firstOrNull { it.location == selectedMenu }?.kind

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Focusable root (scCaptureFocus) so the SC bridge's synthetic d-pad / zoom (trigger) / bumper keys reach us —
        // the controller is not an Android input device.
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
                .scCaptureFocus()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        // Left stick / d-pad = move (the bridge maps left-stick deflection to these d-pad keys).
                        Key.DirectionUp -> { applyLayout(layout.copy(cy = layout.cy - 0.02f)); true }
                        Key.DirectionDown -> { applyLayout(layout.copy(cy = layout.cy + 0.02f)); true }
                        Key.DirectionLeft -> { applyLayout(layout.copy(cx = layout.cx - 0.02f)); true }
                        Key.DirectionRight -> { applyLayout(layout.copy(cx = layout.cx + 0.02f)); true }
                        // Triggers = resize (RT bigger, LT smaller).
                        Key.ZoomIn -> { applyLayout(layout.copy(scale = layout.scale * 1.05f)); true }
                        Key.ZoomOut -> { applyLayout(layout.copy(scale = layout.scale / 1.05f)); true }
                        // Bumpers = switch which menu you're placing (menu editor only; no-op when there's one target).
                        Key.ButtonL1 -> { cycleMenu(-1); true }
                        Key.ButtonR1 -> { cycleMenu(1); true }
                        // Y = Reset (a touch-only action promoted to a button, since the stick moves the overlay).
                        Key.ButtonY -> { doReset(); true }
                        else -> false
                    }
                },
        ) {
            ScNavDialogCapture(onBack = onDismiss)  // B closes (the layout is already auto-saved)
            AndroidView(
                // Non-focusable so the overlay View can't steal focus from the Compose root — otherwise the bridge's
                // synthetic d-pad/zoom/bumper keys stop arriving (they only fire while the focusable Box holds focus).
                factory = { (menuView ?: kbView)!!.apply { isFocusable = false; isFocusableInTouchMode = false } },
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        applyLayout(
                            layout.copy(
                                scale = layout.scale * zoom,
                                cx = layout.cx + if (w > 0f) pan.x / w else 0f,
                                cy = layout.cy + if (h > 0f) pan.y / h else 0f,
                            ),
                        )
                    }
                },
            )

            // Bottom control panel — semi-transparent so the preview shows through; rounded to match the SC menus.
            // Auto-saves, so no Save/Cancel; the legend shows the controller controls (touch drag/pinch also work).
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)).padding(12.dp),
            ) {
                if (!keyboard) {
                    Text(
                        when {
                            selectedMenu == null -> "No per-surface menus in this config — placing the shared HUD default."
                            else -> "Placing:  ${selectedMenu?.label}${selectedKind?.let { "  ($it)" } ?: ""}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
                Text(
                    buildString {
                        append("Left stick: move   ·   LT / RT: smaller / bigger")
                        if (menuOptions.size > 1) append("   ·   LB / RB: switch menu")
                        append("   ·   Y: reset   ·   B: done")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Or drag / pinch on screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    ScActionChip("Reset", onClick = doReset)
                    if (!isShared) {
                        // Revert this scope to its fallback: a per-menu selection → the whole-HUD placement; the whole
                        // HUD → the global default; keyboard → the global keyboard default. Then close.
                        val perMenu = selectedMenu
                        val useDefault = {
                            when {
                                keyboard -> ScOverlayStore.clearKeyboard(context, storeKey)
                                perMenu != null -> ScOverlayStore.clearMenu(context, storeKey, perMenu.name)
                                else -> ScOverlayStore.clear(context, storeKey)
                            }
                            SnackbarManager.show(if (perMenu != null) "Using the HUD default" else "Using global default")
                            onDismiss()
                        }
                        ScActionChip(if (perMenu != null) "Use HUD default" else "Use global", onClick = useDefault)
                    }
                }
            }
        }
    }
}

/** A representative 8-slot menu so the user can judge size/position for both HUD kinds. */
private fun sampleSpec(radial: Boolean): ScMenuSpec =
    if (radial) {
        ScMenuSpec(ScMenuSpec.Kind.RADIAL, listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖"), 0, 0, 0)
    } else {
        ScMenuSpec(ScMenuSpec.Kind.GRID, listOf("1", "2", "3", "4", "5", "6", "7", "8"), 4, 2, 0)
    }
