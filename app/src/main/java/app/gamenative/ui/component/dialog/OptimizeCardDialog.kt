package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.billing.Membership
import app.gamenative.optimize.Entitlement
import app.gamenative.optimize.Metrics
import app.gamenative.optimize.OptimizeCard
import app.gamenative.optimize.OptimizeCards
import app.gamenative.optimize.OptimizeController
import app.gamenative.optimize.OptimizeEvidence
import app.gamenative.optimize.OptimizeProfile
import app.gamenative.optimize.OptimizeTarget
import app.gamenative.ui.component.BenefitRow
import app.gamenative.ui.component.GradientBorderSurface
import app.gamenative.ui.component.HeroBadge
import app.gamenative.ui.component.MetricDeltaTile
import app.gamenative.ui.component.PremiumDialogButton
import app.gamenative.ui.component.PremiumTextButton
import app.gamenative.ui.screen.support.requestFocusAfterLayout
import app.gamenative.ui.theme.PluviaTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Shows the waiting Optimize card once the user is back on the library or game screen. */
@Composable
fun OptimizeCardHost(gameRunning: Boolean, blocked: Boolean) {
    val request by OptimizeController.cardRequest.collectAsState()
    val current = request ?: return
    if (gameRunning || blocked) return
    OptimizeCardDialog(current)
}

/** The diagnosis; [afterMin] is how long the session ran before frames started dropping, when it did. */
@Composable
private fun firstCardTitle(cls: String?, name: String, afterMin: Int? = null): String = when (cls) {
    "gpu" -> afterMin?.let { stringResource(R.string.optimize_card_title_gpu_after, name, it) }
        ?: stringResource(R.string.optimize_card_title_gpu, name)
    "thermal_gpu" -> afterMin?.let { stringResource(R.string.optimize_card_title_thermal_after, name, it) }
        ?: stringResource(R.string.optimize_card_title_thermal, name)
    "memory" -> stringResource(R.string.optimize_card_title_memory, name)
    "present_locked" -> stringResource(R.string.optimize_card_title_present, name)
    else -> stringResource(R.string.optimize_card_title_cpu, name)
}

/** What the player saw in the session, under the diagnosis. */
@Composable
private fun evidenceText(line: OptimizeEvidence.Line?): String? = when (line) {
    null -> null
    is OptimizeEvidence.Line.Drop -> stringResource(R.string.optimize_evidence_drop, line.from, line.to)
    is OptimizeEvidence.Line.Stutter -> stringResource(R.string.optimize_evidence_stutter, line.pct)
    is OptimizeEvidence.Line.Fps -> line.dipsTo?.let { stringResource(R.string.optimize_evidence_fps_dips, line.fps, it) }
        ?: stringResource(R.string.optimize_evidence_fps, line.fps)
}

/** The outcome tuning is offered for. */
@Composable
private fun offerText(offer: OptimizeEvidence.Offer): String = when (offer) {
    OptimizeEvidence.Offer.Heat -> stringResource(R.string.optimize_offer_heat)
    OptimizeEvidence.Offer.Smoother -> stringResource(R.string.optimize_offer_fps)
    OptimizeEvidence.Offer.Stutter -> stringResource(R.string.optimize_offer_stutter)
    is OptimizeEvidence.Offer.Steady ->
        stringResource(if (offer.lessHeat) R.string.optimize_offer_steady_heat else R.string.optimize_offer_steady, offer.fps)
}

@Composable
private fun measuredText(card: OptimizeCard): String? {
    val fps = card.fpsGainPct?.let { stringResource(R.string.optimize_measured_fps, it) }
    val stutter = card.stutterDropPct?.let { stringResource(R.string.optimize_measured_stutter, it) }
    return when (card.outcome) {
        OptimizeCard.OUTCOME_FPS -> fps
        OptimizeCard.OUTCOME_STUTTER -> stutter
        else -> fps ?: stutter
    }
}

private data class CardBenefit(val text: String, val icon: ImageVector, val tint: Color)

private data class CardTile(
    val label: String,
    val before: String?,
    val after: String,
    val accent: Color,
    val delta: String? = null,
)

private data class CardAction(val text: String, val subtitle: String? = null, val onClick: () -> Unit)

/** Everything one card kind shows; [OptimizeCardLayout] lays it out the same way for every kind. */
private data class CardSpec(
    val title: String,
    val accent: OptimizeAccent,
    val subtitle: String? = null,
    /** The offer in one sentence, under the title and its evidence. */
    val body: String? = null,
    val statusPill: Pair<String, Color>? = null,
    val showEntitlement: Boolean = false,
    val benefits: List<CardBenefit> = emptyList(),
    val tiles: List<CardTile> = emptyList(),
    val primary: CardAction,
    val secondary: CardAction? = null,
    val tertiary: CardAction? = null,
    val premiumBorder: Boolean = false,
)

@Composable
private fun undoBenefit(): CardBenefit =
    CardBenefit(stringResource(R.string.optimize_benefit_undo), Icons.AutoMirrored.Filled.Undo, PluviaTheme.colors.accentSuccess)

@Composable
private fun fpsValue(fps: Double): String = stringResource(R.string.optimize_metric_fps_value, fps.roundToInt())

@Composable
private fun shareValue(share: Double): String = stringResource(R.string.optimize_metric_percent_value, (share * 100).roundToInt())

@Composable
private fun msValue(ms: Double): String = stringResource(R.string.optimize_metric_ms_value, ms.roundToInt())

@Composable
private fun minutesValue(minutes: Int): String = pluralStringResource(R.plurals.optimize_metric_minutes, minutes, minutes)

/** Minutes the session held its performance: until throttling set in, or all of it when it never did. */
private fun stableMinutes(metrics: Metrics): Int = ((metrics.throttleOnsetSec ?: metrics.gameplaySec) / 60).coerceAtLeast(1)

/**
 * Before and after tiles from the two judged sessions: frame rate, stutters (else the slowest frames) and how long it
 * stayed stable. A measure the session after did not record is left out.
 */
@Composable
private fun resultTiles(card: OptimizeCard): List<CardTile> {
    val after = card.metrics ?: return emptyList()
    val before = card.before
    val tiles = mutableListOf<CardTile>()
    after.steadyFps?.let { a ->
        tiles += CardTile(
            label = stringResource(R.string.optimize_metric_fps),
            before = before?.steadyFps?.let { stringResource(R.string.optimize_metric_before, fpsValue(it)) },
            after = fpsValue(a),
            accent = PluviaTheme.colors.accentCyan,
            delta = card.fpsGainPct?.let { stringResource(R.string.optimize_metric_delta_up, it) },
        )
    }
    val stutter = after.stutterShare
    val p99 = after.frameTimeP99Ms
    if (stutter != null) {
        tiles += CardTile(
            label = stringResource(R.string.optimize_metric_stutter),
            before = before?.stutterShare?.let { stringResource(R.string.optimize_metric_before, shareValue(it)) },
            after = shareValue(stutter),
            accent = PluviaTheme.colors.accentPurple,
            delta = card.stutterDropPct?.let { stringResource(R.string.optimize_metric_delta_down, it) },
        )
    } else if (p99 != null) {
        tiles += CardTile(
            label = stringResource(R.string.optimize_metric_frame_p99),
            before = before?.frameTimeP99Ms?.let { stringResource(R.string.optimize_metric_before, msValue(it)) },
            after = msValue(p99),
            accent = PluviaTheme.colors.accentPurple,
        )
    }
    if (after.throttleOnsetSec != null || before?.throttleOnsetSec != null) {
        tiles += CardTile(
            label = stringResource(R.string.optimize_metric_stable_for),
            before = before?.let { stringResource(R.string.optimize_metric_before, minutesValue(stableMinutes(it))) },
            after = minutesValue(stableMinutes(after)),
            accent = PluviaTheme.colors.accentSuccess,
        )
    }
    return tiles.take(3)
}

@Composable
private fun OptimizeCardDialog(request: OptimizeController.CardRequest) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberOptimizeGate()
    val card = request.card
    val name = card.gameName.orEmpty()
    LaunchedEffect(request.appId, request.card) { OptimizeController.cardShown(request) }
    // Only the done-at-baseline card (no step behind it) reads its session for heat.
    val heatHint = card.kind == OptimizeCard.DONE && card.rungId == null && card.profile != OptimizeProfile.POWER &&
        card.metrics?.let { OptimizeTarget.hot(it, null, card.capFps ?: 0) } == true
    var choosing by remember(request.appId, request.card) { mutableStateOf(heatHint) }
    var chosen by remember(request.appId, request.card) {
        mutableStateOf(if (heatHint) OptimizeProfile.POWER else card.profile.takeIf { card.kind == OptimizeCard.FIRST } ?: OptimizeProfile.BALANCED)
    }
    val suggested = if (heatHint) OptimizeProfile.POWER else card.profile.takeIf { card.kind == OptimizeCard.FIRST }

    fun answer(action: String, profile: OptimizeProfile? = null) =
        OptimizeController.answer(context, request.appId, card, action, profile)

    fun optimizeChosen() {
        val profile = chosen
        scope.launch { gate.require(request.appId) { answer(OptimizeCards.ACTION_OPTIMIZE, profile) } }
    }

    val ok = stringResource(R.string.ok)
    val notNow = CardAction(stringResource(R.string.optimize_card_not_now)) { answer(OptimizeCards.ACTION_NOT_NOW) }
    val never = CardAction(stringResource(R.string.optimize_card_never)) { answer(OptimizeCards.ACTION_NEVER) }
    val showEntitlement = card.kind != OptimizeCard.ENTITLEMENT && card.kind != OptimizeCard.NO_RULE
    val success = OptimizeAccent(Icons.Filled.CheckCircle, PluviaTheme.colors.accentSuccess)

    val spec = when (card.kind) {
        OptimizeCard.FIRST -> CardSpec(
            title = firstCardTitle(card.cls, name, OptimizeEvidence.droppedAfterMin(card.cls, card.metrics)),
            accent = optimizeAccentFor(card.cls),
            subtitle = evidenceText(OptimizeEvidence.line(card.cls, card.metrics)),
            body = offerText(OptimizeEvidence.offer(card.cls, card.metrics, card.expectedFps)),
            showEntitlement = showEntitlement,
            benefits = listOf(undoBenefit()),
            primary = CardAction(stringResource(R.string.option_optimize), subtitle = stringResource(chosen.title)) {
                if (!choosing) {
                    choosing = true
                } else {
                    optimizeChosen()
                }
            },
            secondary = notNow,
            tertiary = never,
        )
        OptimizeCard.CAP -> CardSpec(
            title = stringResource(R.string.optimize_cap_title, name, card.capFps ?: OptimizeCards.CAP_HEAT_FPS.toInt()),
            accent = optimizeAccentFor("thermal_gpu"),
            showEntitlement = showEntitlement,
            benefits = listOf(
                CardBenefit(stringResource(R.string.optimize_cap_body), Icons.Filled.Thermostat, PluviaTheme.colors.accentWarning),
                undoBenefit(),
            ),
            primary = CardAction(stringResource(R.string.optimize_cap_yes)) {
                val profile = card.profile
                scope.launch { gate.require(request.appId) { answer(OptimizeCards.ACTION_CAP, profile) } }
            },
            secondary = notNow,
        )
        OptimizeCard.HELPED -> {
            val tiles = resultTiles(card)
            val measured = if (tiles.isEmpty()) measuredText(card) else null
            // A step kept without a gain the player can see is not called a help.
            val visible = card.capFps != null || OptimizeCards.showsGain(card)
            CardSpec(
                title = card.capFps?.let { stringResource(R.string.optimize_after_cap_held, it) } ?: when {
                    !visible -> stringResource(R.string.optimize_after_kept_no_visible)
                    card.outcome == OptimizeCard.OUTCOME_FPS -> stringResource(R.string.optimize_after_helped_fps)
                    card.outcome == OptimizeCard.OUTCOME_STUTTER -> stringResource(R.string.optimize_after_helped_stutter)
                    else -> stringResource(R.string.optimize_after_helped_stable)
                },
                accent = if (visible) success else OptimizeAccent(Icons.Filled.Tune, PluviaTheme.colors.textMuted),
                showEntitlement = showEntitlement,
                tiles = tiles,
                benefits = if (visible) {
                    listOfNotNull(measured?.let { CardBenefit(it, Icons.Filled.Speed, PluviaTheme.colors.accentCyan) })
                } else {
                    listOf(CardBenefit(stringResource(R.string.optimize_after_kept_no_visible_body), Icons.Filled.Tune, PluviaTheme.colors.textMuted))
                },
                primary = CardAction(stringResource(R.string.optimize_keep_going)) { answer(OptimizeCards.ACTION_KEEP_GOING) },
                secondary = CardAction(stringResource(R.string.optimize_happy)) { answer(OptimizeCards.ACTION_HAPPY) },
                tertiary = CardAction(stringResource(R.string.optimize_undo)) { answer(OptimizeCards.ACTION_UNDO) },
            )
        }
        OptimizeCard.NO_CHANGE -> CardSpec(
            title = card.capFps?.let { stringResource(R.string.optimize_after_cap_removed, it) }
                ?: stringResource(R.string.optimize_after_no_change),
            accent = OptimizeAccent(Icons.Filled.Tune, PluviaTheme.colors.textMuted),
            showEntitlement = showEntitlement,
            primary = CardAction(stringResource(R.string.optimize_keep_going)) { answer(OptimizeCards.ACTION_KEEP_GOING) },
            secondary = CardAction(stringResource(R.string.optimize_stop_here)) { answer(OptimizeCards.ACTION_STOP) },
        )
        OptimizeCard.WORSE -> CardSpec(
            title = if (card.outcome == OptimizeCard.OUTCOME_CRASH) {
                stringResource(R.string.optimize_after_crash_undone, name)
            } else {
                card.capFps?.let { stringResource(R.string.optimize_after_cap_removed, it) }
                    ?: stringResource(R.string.optimize_after_worse)
            },
            accent = OptimizeAccent(Icons.AutoMirrored.Filled.Undo, PluviaTheme.colors.accentWarning),
            showEntitlement = showEntitlement,
            primary = CardAction(ok) { answer(OptimizeCards.ACTION_OK) },
        )
        OptimizeCard.EXHAUSTED -> CardSpec(
            title = stringResource(R.string.optimize_after_exhausted, name),
            accent = OptimizeAccent(Icons.Filled.Flag, PluviaTheme.colors.accentCyan),
            showEntitlement = showEntitlement,
            primary = CardAction(stringResource(R.string.optimize_keep_changes)) { answer(OptimizeCards.ACTION_KEEP_CHANGES) },
            tertiary = CardAction(stringResource(R.string.optimize_undo_all)) { answer(OptimizeCards.ACTION_UNDO_ALL) },
        )
        OptimizeCard.NO_RULE -> CardSpec(
            title = stringResource(R.string.optimize_after_no_rule, name),
            accent = OptimizeAccent(Icons.Filled.HourglassTop, PluviaTheme.colors.textMuted),
            primary = CardAction(ok) { answer(OptimizeCards.ACTION_OK) },
        )
        OptimizeCard.DONE -> {
            val body = if (heatHint) {
                CardBenefit(stringResource(R.string.optimize_hint_heat), Icons.Filled.Thermostat, PluviaTheme.colors.accentWarning)
            } else {
                card.capFps?.let {
                    CardBenefit(stringResource(R.string.optimize_after_cap_held, it), Icons.Filled.GraphicEq, PluviaTheme.colors.accentPurple)
                }
            }
            CardSpec(
                title = stringResource(R.string.optimize_after_done, name),
                accent = success,
                statusPill = stringResource(R.string.optimize_status_done) to PluviaTheme.colors.accentSuccess,
                showEntitlement = showEntitlement,
                // A done card after a step compares the two sessions like a helped card; done at the baseline has one.
                tiles = if (card.before != null) resultTiles(card) else emptyList(),
                benefits = listOfNotNull(body),
                primary = if (heatHint) {
                    CardAction(stringResource(R.string.option_optimize), subtitle = stringResource(chosen.title)) { optimizeChosen() }
                } else {
                    CardAction(ok) { answer(OptimizeCards.ACTION_OK) }
                },
                secondary = CardAction(stringResource(R.string.optimize_keep_tuning)) {
                    scope.launch { gate.require(request.appId) { answer(OptimizeCards.WIRE_CONTINUE) } }
                },
                tertiary = if (heatHint) CardAction(ok) { answer(OptimizeCards.ACTION_OK) } else null,
            )
        }
        else -> CardSpec(
            title = optimizeEntitlementText(request.entitlement, exhausted = true).orEmpty(),
            accent = OptimizeAccent(Icons.Filled.WorkspacePremium, PluviaTheme.colors.accentPurple),
            primary = CardAction(stringResource(R.string.optimize_see_membership)) {
                answer(OptimizeCards.ACTION_MEMBERSHIP)
                Membership.plansRequested.value = true
            },
            secondary = CardAction(stringResource(R.string.close)) { answer(OptimizeCards.ACTION_DISMISSED) },
            premiumBorder = true,
        )
    }

    OptimizeCardLayout(
        spec = spec,
        entitlement = request.entitlement,
        focusKey = request.card,
        onDismiss = { answer(OptimizeCards.ACTION_DISMISSED) },
        chooser = if (choosing) {
            {
                OptimizeChooser(
                    selected = chosen,
                    suggested = suggested,
                    onSelect = { chosen = it },
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun OptimizeChooser(
    selected: OptimizeProfile,
    suggested: OptimizeProfile?,
    onSelect: (OptimizeProfile) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.optimize_choose_goal),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OptimizeGoalCards(
            selected = selected,
            suggested = suggested,
            enabled = true,
            onSelect = onSelect,
        )
    }
}

/** The card on the premium surface: hero, status and plan lines, results and benefits, then the actions; side by side in landscape. */
@Composable
private fun OptimizeCardLayout(
    spec: CardSpec,
    entitlement: Entitlement?,
    focusKey: Any,
    onDismiss: () -> Unit,
    chooser: (@Composable () -> Unit)?,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val primaryFocus = remember { FocusRequester() }

    val content: @Composable ColumnScope.() -> Unit = {
        HeroBadge(icon = spec.accent.icon, accent = spec.accent.color)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = spec.title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        spec.subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        spec.body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        spec.statusPill?.let { (text, color) -> OptimizeLabel(text = text, color = color, modifier = Modifier.padding(top = 6.dp)) }
        if (spec.showEntitlement) OptimizePlanLine(entitlement, modifier = Modifier.padding(top = 4.dp))
        if (spec.tiles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                spec.tiles.forEach { tile ->
                    MetricDeltaTile(
                        label = tile.label,
                        before = tile.before,
                        after = tile.after,
                        accent = tile.accent,
                        delta = tile.delta,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (chooser != null) {
            Spacer(modifier = Modifier.height(16.dp))
            chooser()
        } else if (spec.benefits.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                spec.benefits.forEach { BenefitRow(title = it.text, icon = it.icon, iconTint = it.tint, compact = true) }
            }
        }
    }

    val actions: @Composable ColumnScope.() -> Unit = {
        PremiumDialogButton(
            text = spec.primary.text,
            subtitle = spec.primary.subtitle,
            onClick = spec.primary.onClick,
            modifier = Modifier.focusRequester(primaryFocus),
        )
        spec.secondary?.let {
            Spacer(modifier = Modifier.height(8.dp))
            PremiumDialogButton(text = it.text, subtitle = it.subtitle, onClick = it.onClick, primary = false)
        }
        spec.tertiary?.let {
            Spacer(modifier = Modifier.height(4.dp))
            PremiumTextButton(text = it.text, onClick = it.onClick)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
    ) {
        LaunchedEffect(focusKey) { primaryFocus.requestFocusAfterLayout() }

        GradientBorderSurface(
            modifier = Modifier
                .then(if (landscape) Modifier.widthIn(max = 720.dp).padding(16.dp) else Modifier.fillMaxWidth())
                .wrapContentHeight(),
            accent = spec.accent.color,
            border = if (spec.premiumBorder) SolidColor(PluviaTheme.colors.accentPurple.copy(alpha = 0.8f)) else null,
        ) {
            if (landscape) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .verticalScroll(rememberScrollState()),
                        content = content,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically)
                            .verticalScroll(rememberScrollState()),
                        content = actions,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        content = content,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    actions()
                }
            }
        }
    }
}
