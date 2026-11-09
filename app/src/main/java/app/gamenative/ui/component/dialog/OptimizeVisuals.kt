package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.billing.Membership
import app.gamenative.optimize.Entitlement
import app.gamenative.optimize.EntitlementLine
import app.gamenative.optimize.OptimizeController
import app.gamenative.optimize.OptimizeProfile
import app.gamenative.optimize.OptimizeStatus
import app.gamenative.optimize.orAppWide
import app.gamenative.ui.component.IconTile
import app.gamenative.ui.component.OutcomePill
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme

/** The icon and accent color that stand for one Optimize diagnosis or goal. */
data class OptimizeAccent(val icon: ImageVector, val color: Color)

/** Frame rate cyan, stutter purple, heat warning, memory pink, anything else the generic purple. */
@Composable
fun optimizeAccentFor(cls: String?): OptimizeAccent = when (cls) {
    "gpu", "cpu_single", "cpu_multi" -> OptimizeAccent(Icons.Filled.Speed, PluviaTheme.colors.accentCyan)
    "present_locked" -> OptimizeAccent(Icons.Filled.GraphicEq, PluviaTheme.colors.accentPurple)
    "thermal_gpu" -> OptimizeAccent(Icons.Filled.Thermostat, PluviaTheme.colors.accentWarning)
    "memory" -> OptimizeAccent(Icons.Filled.Memory, PluviaTheme.colors.accentPink)
    else -> OptimizeAccent(Icons.Rounded.AutoAwesome, PluviaTheme.colors.accentPurple)
}

@Composable
fun optimizeProfileAccent(profile: OptimizeProfile): OptimizeAccent = when (profile) {
    OptimizeProfile.BALANCED -> OptimizeAccent(Icons.Filled.Tune, PluviaTheme.colors.accentCyan)
    OptimizeProfile.PERFORMANCE -> OptimizeAccent(Icons.Filled.Speed, PluviaTheme.colors.accentCyan)
    OptimizeProfile.STABILITY -> OptimizeAccent(Icons.Filled.GraphicEq, PluviaTheme.colors.accentPurple)
    OptimizeProfile.POWER -> OptimizeAccent(Icons.Filled.Thermostat, PluviaTheme.colors.accentWarning)
    OptimizeProfile.OFF -> OptimizeAccent(Icons.Filled.PowerSettingsNew, PluviaTheme.colors.textMuted)
}

/** Goals in the order they are offered; Off is shown apart, below them. */
val OPTIMIZE_GOAL_ORDER = listOf(
    OptimizeProfile.BALANCED,
    OptimizeProfile.PERFORMANCE,
    OptimizeProfile.STABILITY,
    OptimizeProfile.POWER,
)

/**
 * The goal chooser: one selectable card per goal, the suggested one marked, Off as a muted row at the
 * bottom when [includeOff]. Every card takes d-pad focus.
 */
@Composable
fun OptimizeGoalCards(
    selected: OptimizeProfile?,
    suggested: OptimizeProfile?,
    enabled: Boolean,
    onSelect: (OptimizeProfile) -> Unit,
    modifier: Modifier = Modifier,
    includeOff: Boolean = false,
    firstModifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OPTIMIZE_GOAL_ORDER.forEachIndexed { index, profile ->
            OptimizeGoalCard(
                profile = profile,
                selected = profile == selected,
                suggested = profile == suggested,
                enabled = enabled,
                onClick = { onSelect(profile) },
                modifier = if (index == 0) firstModifier else Modifier,
            )
        }
        if (includeOff) {
            OptimizeGoalCard(
                profile = OptimizeProfile.OFF,
                selected = selected == OptimizeProfile.OFF,
                suggested = false,
                enabled = enabled,
                onClick = { onSelect(OptimizeProfile.OFF) },
                muted = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun OptimizeGoalCard(
    profile: OptimizeProfile,
    selected: Boolean,
    suggested: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val accent = optimizeProfileAccent(profile)
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.color.copy(alpha = 0.12f) else PluviaTheme.colors.surfacePanel.copy(alpha = if (muted) 0.4f else 1f))
            .border(1.dp, if (selected) accent.color else PluviaTheme.colors.borderDefault.copy(alpha = 0.3f), shape)
            .focusRing(interaction, shape, width = 2.dp)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = if (muted) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon = accent.icon, tint = accent.color)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(profile.title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (muted) PluviaTheme.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                )
                if (suggested) {
                    OutcomePill(text = stringResource(R.string.optimize_badge_suggested), color = accent.color, dot = false)
                }
            }
            Text(
                text = stringResource(profile.description),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = accent.color,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The trial or membership line as a pill: free to try, games used, included, or used up. */
@Composable
fun OptimizeEntitlementPill(entitlement: Entitlement?, exhausted: Boolean = false, modifier: Modifier = Modifier) {
    val known = entitlement.orAppWide(OptimizeController.lastEntitlement)
    val line = OptimizeStatus.entitlementLine(known, exhausted, PrefManager.gameNativeSignedIn.value, Membership.isMember())
    val (text, color) = when (line) {
        null -> return
        EntitlementLine.Member -> stringResource(R.string.optimize_included) to PluviaTheme.colors.accentSuccess
        is EntitlementLine.Offer -> stringResource(R.string.optimize_badge_free_to_try) to PluviaTheme.colors.accentCyan
        is EntitlementLine.Trial ->
            pluralStringResource(R.plurals.optimize_trial_progress, line.limit, line.used, line.limit) to PluviaTheme.colors.accentCyan
        is EntitlementLine.Exhausted -> stringResource(R.string.optimize_trial_used_short) to PluviaTheme.colors.textMuted
    }
    OutcomePill(text = text, color = color, modifier = modifier)
}
