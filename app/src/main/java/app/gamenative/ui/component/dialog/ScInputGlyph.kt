package app.gamenative.ui.component.dialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.steamcontroller.ScMenuNav
import app.gamenative.steamcontroller.TritonProtocol
import app.gamenative.ui.icons.InputIcons

/**
 * Renders a controller button as GameNative's own Xbox glyph (Kenney input-prompt icons via [InputIcons]) instead of
 * a text badge, so the SC editors' button hints match the rest of the app's prompts (e.g. the library action bar).
 * Honors [PrefManager.swapFaceButtons] like GameNative's `GamepadButtonHint` does — the A/B/X/Y *icon* swaps to match
 * the user's controller labelling (the physical mapping is unchanged).
 */
private fun xboxGlyphRes(buttonBit: Int, swapFaceButtons: Boolean): Int? = when (buttonBit) {
    TritonProtocol.BTN_A -> if (swapFaceButtons) InputIcons.Xbox.buttonColorB else InputIcons.Xbox.buttonColorA
    TritonProtocol.BTN_B -> if (swapFaceButtons) InputIcons.Xbox.buttonColorA else InputIcons.Xbox.buttonColorB
    TritonProtocol.BTN_X -> if (swapFaceButtons) InputIcons.Xbox.buttonColorY else InputIcons.Xbox.buttonColorX
    TritonProtocol.BTN_Y -> if (swapFaceButtons) InputIcons.Xbox.buttonColorX else InputIcons.Xbox.buttonColorY
    TritonProtocol.BTN_LBUMPER -> InputIcons.Xbox.lb
    TritonProtocol.BTN_RBUMPER -> InputIcons.Xbox.rb
    // Xbox's "menu" glyph is the hamburger = Start, and "view" is the two-panes = Back/Select. On the Triton
    // BTN_VIEW is Start (it drives IDX_BUTTON_START) and BTN_MENU is Back, so the two cross over here.
    TritonProtocol.BTN_MENU -> InputIcons.Xbox.view
    TritonProtocol.BTN_VIEW -> InputIcons.Xbox.menu
    TritonProtocol.BTN_STEAM -> InputIcons.Xbox.guide   // no "Steam" glyph; the Guide button is the closest match
    else -> null
}

/** A single Xbox button glyph for the given SC [buttonBit], or nothing if that bit has no glyph. */
@Composable
fun ScButtonGlyph(buttonBit: Int, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val res = xboxGlyphRes(buttonBit, PrefManager.swapFaceButtons) ?: return
    Image(painter = painterResource(res), contentDescription = null, modifier = modifier.size(size))
}

/** The d-pad glyph (directional focus movement). */
@Composable
fun ScDpadGlyph(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(painter = painterResource(InputIcons.Xbox.dpad), contentDescription = null, modifier = modifier.size(size))
}

/** A compact "glyph + label" pair for a dialog's button-legend footer. */
@Composable
fun ScLegendHint(buttonBit: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ScButtonGlyph(buttonBit, size = 18.dp)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Like [ScLegendHint] but for glyphs that aren't a single button bit (a stick, a trigger pair): [icons] are
 *  [InputIcons] drawables shown side by side before [text]. */
@Composable
fun ScIconLegendHint(icons: List<Int>, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        icons.forEach { Image(painter = painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The button legend under an SC dialog: d-pad Move, A Select, B Back, plus LB/RB, Y and Start only when the dialog
 * handles them, so it can never advertise a button that does nothing there. Wraps on narrow screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScNavLegend(bumpers: Boolean, help: Boolean, close: Boolean, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ScDpadGlyph(size = 18.dp)
            Text("Move", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ScLegendHint(TritonProtocol.BTN_A, "Select")
        ScLegendHint(TritonProtocol.BTN_B, "Back")
        if (bumpers) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ScButtonGlyph(TritonProtocol.BTN_LBUMPER, size = 18.dp)
                ScLegendHint(TritonProtocol.BTN_RBUMPER, "Switch")
            }
        }
        if (help) ScLegendHint(TritonProtocol.BTN_Y, "Help")
        if (close) ScLegendHint(TritonProtocol.BTN_VIEW, "Close")
    }
}

/** One "glyph — description" row for the menu-nav help list, rendered from a fixed [ScMenuNav.Control]. */
@Composable
fun ScNavHelpRow(control: ScMenuNav.Control) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ScButtonGlyph(control.buttonBit, size = 26.dp)
        Text(control.desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The directions ("d-pad / left stick → move") help row. */
@Composable
fun ScNavHelpDirectionsRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ScDpadGlyph(size = 26.dp)
        Text(
            "${ScMenuNav.DIRECTIONS_HINT} — ${ScMenuNav.DIRECTIONS_DESC}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
