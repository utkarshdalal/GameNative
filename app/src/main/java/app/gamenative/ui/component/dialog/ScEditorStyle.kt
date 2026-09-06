package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Shared visual language for the Steam-Controller editors, so every SC menu / sub-menu looks the same and matches
 * GameNative's app style. The selection ring (rotating gradient), Xbox button glyphs, scrollbar, and rounded text
 * field live in their own files ([ScSelectionRing]/[ScNavItem], [ScButtonGlyph], [ScScrollbar], [ScTextEditField]);
 * this file holds the chip + section-header primitives.
 */

/** Section header matching GameNative's OptionSectionHeader: uppercased, primary @ 0.8, labelMedium, 1.5× spacing. */
@Composable
fun ScSectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * The oval chip used everywhere in the SC editors (matches GameNative's FlowFilterChip): filled-primary when
 * [selected], 2dp-primary outline when not, 16dp radius. No outer padding — the nav-selection ring is drawn at this
 * element's bounds, so padding would leave a gap between the ring and the pill; space chips via the parent's
 * arrangement. When !enabled it dims and stops responding to taps.
 */
@Composable
fun ScChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    // Colour language: SOLID purple + white text = the active / currently-bound choice; hollow WHITE outline = an
    // available option. (The rotating gradient ring, drawn separately, is the nav cursor — a third, distinct state.)
    val active = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val option = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(active, shape)
                else Modifier.border(2.dp, option, shape),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else option,
        )
    }
}

/** An action button styled as an [ScChip] — [filled] (primary) for the primary action (Save/Done), outline otherwise. */
@Composable
fun ScActionChip(label: String, onClick: () -> Unit, filled: Boolean = false) =
    ScChip(label = label, selected = filled, onClick = onClick)
