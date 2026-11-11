package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.billing.Membership
import app.gamenative.optimize.Entitlement
import app.gamenative.optimize.EntitlementLine
import app.gamenative.optimize.OptimizeController
import app.gamenative.optimize.OptimizeProfile
import app.gamenative.optimize.OptimizeStatus
import app.gamenative.optimize.PlanName
import app.gamenative.optimize.PlanText
import app.gamenative.ui.component.BenefitRow
import app.gamenative.ui.component.GradientBorderSurface
import app.gamenative.ui.component.HeroBadge
import app.gamenative.ui.component.IconTile
import app.gamenative.ui.component.PremiumDialogButton
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme
import java.text.DateFormat
import java.util.Date

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
    OptimizeProfile.QUALITY -> OptimizeAccent(Icons.Filled.GraphicEq, PluviaTheme.colors.accentPurple)
    OptimizeProfile.POWER -> OptimizeAccent(Icons.Filled.Thermostat, PluviaTheme.colors.accentWarning)
    OptimizeProfile.POTATO -> OptimizeAccent(Icons.Filled.Grass, PluviaTheme.colors.accentPink)
    OptimizeProfile.OFF -> OptimizeAccent(Icons.Filled.PowerSettingsNew, PluviaTheme.colors.textMuted)
}

/** Goals in the order they are offered; Off is shown apart, below them. */
val OPTIMIZE_GOAL_ORDER = listOf(
    OptimizeProfile.BALANCED,
    OptimizeProfile.QUALITY,
    OptimizeProfile.PERFORMANCE,
    OptimizeProfile.POWER,
    OptimizeProfile.POTATO,
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
    cardModifier: (OptimizeProfile) -> Modifier = { Modifier },
) {
    Column(
        // Each card pads itself by 4.dp for its focus ring, which makes the 8.dp between cards.
        modifier = modifier.selectableGroup(),
    ) {
        OPTIMIZE_GOAL_ORDER.forEachIndexed { index, profile ->
            OptimizeGoalCard(
                profile = profile,
                selected = profile == selected,
                suggested = profile == suggested,
                enabled = enabled,
                onClick = { onSelect(profile) },
                modifier = (if (index == 0) firstModifier else Modifier).then(cardModifier(profile)),
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
                modifier = Modifier.padding(top = 8.dp).then(cardModifier(OptimizeProfile.OFF)),
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
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    // Focus has its own cue apart from the selection border: a bright border and the animated ring outside the card.
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.onSurface
        selected -> accent.color
        else -> PluviaTheme.colors.borderDefault.copy(alpha = 0.3f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRing(interaction, RoundedCornerShape(20.dp), width = 2.dp)
            .padding(4.dp)
            .clip(shape)
            .background(if (selected) accent.color.copy(alpha = 0.12f) else PluviaTheme.colors.surfacePanel.copy(alpha = if (muted) 0.4f else 1f))
            .border(if (focused) 2.dp else 1.dp, borderColor, shape)
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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(profile.title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (muted) PluviaTheme.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                )
                if (suggested) {
                    OptimizeTag(text = stringResource(R.string.optimize_badge_suggested), color = accent.color)
                }
                // The check sits on the title line, so the description below keeps the card's full width.
                if (selected) {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = accent.color,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // Every goal's description takes exactly two lines, so the goal cards are one height whether it wraps or not.
            Text(
                text = stringResource(profile.description),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                minLines = if (muted) 1 else 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** [text] resolved: its string or plural with the arguments, the reset date formatted for the locale last. */
@Composable
fun planText(text: PlanText): String {
    val date = text.date?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
    val basic = stringResource(R.string.gamenative_account_plan_basic)
    val pro = stringResource(R.string.gamenative_account_plan_pro)
    val named = text.args.map {
        when (it) {
            PlanName.BASIC -> basic
            PlanName.PRO -> pro
            else -> it
        }
    }
    val args: Array<Any> = (named + listOfNotNull(date)).toTypedArray()
    return if (text.quantity != null) pluralStringResource(text.res, text.quantity, *args) else stringResource(text.res, *args)
}

/** The plan line for an entitlement already chosen for the surface ([OptimizeController.shownEntitlement]). */
@Composable
fun optimizeEntitlementLine(entitlement: Entitlement?, exhausted: Boolean = false): EntitlementLine? =
    OptimizeStatus.entitlementLine(entitlement, exhausted, PrefManager.gameNativeSignedIn.value, Membership.isMember(), Membership.planKey())

/** A short status or plan line: plain label text in [color], no background. */
@Composable
fun OptimizeLabel(text: String, color: Color, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.3.sp),
        color = color,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/** A thin outlined tag in [color], used to mark the suggested goal. */
@Composable
fun OptimizeTag(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .border(1.dp, color, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The plan line in muted text: free games used, games this month, unlimited, or free to try with an account. */
@Composable
fun OptimizePlanLine(entitlement: Entitlement?, exhausted: Boolean = false, modifier: Modifier = Modifier) {
    val line = optimizeEntitlementLine(entitlement, exhausted) ?: return
    OptimizeLabel(text = planText(OptimizeStatus.entitlementPill(line)), color = PluviaTheme.colors.textMuted, modifier = modifier)
}

/**
 * The short status line the cards and the game options row show, in the state's colour; nothing while Optimize is off.
 * [line] is the game's plan line, which words a used-up plan.
 */
@Composable
fun OptimizeStatusLine(status: OptimizeStatus?, line: EntitlementLine? = null, modifier: Modifier = Modifier) {
    val res = status?.let { OptimizeStatus.pillRes(it, line) } ?: return
    OptimizeLabel(text = stringResource(res), color = optimizeStatusColor(status), modifier = modifier)
}

/**
 * The membership pitch when [line] has no games left to optimize, or the user is signed out: what a plan gives in
 * outcome words, the plan line from the quotas, See plans, and the quota that applies. Nothing otherwise.
 */
@Composable
fun OptimizeUpsell(line: EntitlementLine?, modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    if (!OptimizeStatus.showsUpsell(line)) return
    val accent = PluviaTheme.colors.accentPurple
    GradientBorderSurface(modifier = modifier.fillMaxWidth(), accent = accent) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroBadge(icon = Icons.Filled.WorkspacePremium, accent = accent, size = 44.dp, iconSize = 22.dp)
                Text(
                    text = stringResource(R.string.optimize_upsell_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            BenefitRow(
                title = stringResource(R.string.optimize_upsell_benefit_perf),
                icon = Icons.Filled.Speed,
                iconTint = PluviaTheme.colors.accentCyan,
                compact = true,
            )
            BenefitRow(
                title = stringResource(R.string.optimize_upsell_benefit_device),
                icon = Icons.AutoMirrored.Filled.Undo,
                iconTint = PluviaTheme.colors.accentSuccess,
                compact = true,
            )
            BenefitRow(
                title = planText(OptimizeStatus.upsellPlanLine(line)),
                icon = Icons.Filled.WorkspacePremium,
                iconTint = accent,
                compact = true,
            )
            PremiumDialogButton(
                text = stringResource(R.string.support_see_plans),
                onClick = { Membership.plansRequested.value = true },
                subtitle = OptimizeStatus.upsellSubtitle(line)?.let { planText(it) },
                modifier = buttonModifier,
            )
        }
    }
}
