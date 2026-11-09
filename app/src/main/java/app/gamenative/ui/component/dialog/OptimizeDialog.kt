package app.gamenative.ui.component.dialog

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.AccountApi
import app.gamenative.billing.Membership
import app.gamenative.optimize.Entitlement
import app.gamenative.optimize.EntitlementLine
import app.gamenative.optimize.OptimizeController
import app.gamenative.optimize.OptimizeLadders
import app.gamenative.optimize.OptimizeLocalState
import app.gamenative.optimize.OptimizeProfile
import app.gamenative.optimize.OptimizeStatus
import app.gamenative.optimize.limitOrDefault
import app.gamenative.optimize.orAppWide
import app.gamenative.ui.component.BenefitRow
import app.gamenative.ui.component.GradientBorderSurface
import app.gamenative.ui.component.HeroBadge
import app.gamenative.ui.component.OutcomePill
import app.gamenative.ui.component.PremiumDialogButton
import app.gamenative.ui.component.PremiumTextButton
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.screen.support.requestFocusAfterLayout
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optimize needs an account: actions behind it wait for sign-in and run once it succeeds. */
class OptimizeGate {
    var pending by mutableStateOf<(() -> Unit)?>(null)

    /** [appId] names the game the action is for, so the sign-in prompt is counted for it. */
    suspend fun require(appId: String? = null, action: () -> Unit) {
        if (AccountApi.loadSignedInState()) {
            action()
        } else {
            appId?.let { OptimizeController.signInRequired(it) }
            pending = action
        }
    }
}

@Composable
fun rememberOptimizeGate(): OptimizeGate {
    val gate = remember { OptimizeGate() }
    val trialLimit = OptimizeController.lastEntitlement.limitOrDefault()
    AccountSignInDialog(
        visible = gate.pending != null,
        hero = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroBadge(icon = Icons.Filled.Speed, accent = PluviaTheme.colors.accentCyan)
                OutcomePill(
                    text = pluralStringResource(R.plurals.optimize_trial_offer, trialLimit, trialLimit),
                    color = PluviaTheme.colors.accentCyan,
                )
            }
        },
        benefits = {
            BenefitRow(
                title = stringResource(R.string.optimize_benefit_fps),
                icon = Icons.Filled.Speed,
                iconTint = PluviaTheme.colors.accentCyan,
                compact = true,
            )
            BenefitRow(
                title = stringResource(R.string.optimize_benefit_undo),
                icon = Icons.AutoMirrored.Filled.Undo,
                iconTint = PluviaTheme.colors.accentSuccess,
                compact = true,
            )
        },
        onSignedIn = {
            val action = gate.pending
            gate.pending = null
            action?.invoke()
        },
        onDismiss = { gate.pending = null },
    )
    return gate
}

class OptimizeActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gate: OptimizeGate,
    private val appId: String,
) {
    var busy by mutableStateOf(false)
        private set

    private fun perform(doneMessage: Int?, action: suspend () -> OptimizeController.ChangeResult) {
        busy = true
        scope.launch {
            when (withContext(NonCancellable) { action() }) {
                OptimizeController.ChangeResult.GAME_RUNNING -> SnackbarManager.show(context.getString(R.string.optimize_game_running))
                OptimizeController.ChangeResult.FAILED -> SnackbarManager.show(context.getString(R.string.optimize_undo_failed))
                OptimizeController.ChangeResult.DONE -> doneMessage?.let { SnackbarManager.show(context.getString(it)) }
                OptimizeController.ChangeResult.NO_CONTAINER -> Unit
            }
            busy = false
        }
    }

    private fun gated(action: () -> Unit) {
        scope.launch { gate.require(appId, action) }
    }

    fun switchTo(profile: OptimizeProfile) {
        val run = { perform(null) { OptimizeController.setProfile(context, appId, profile) } }
        if (profile == OptimizeProfile.OFF) run() else gated(run)
    }

    fun continueLadder() = gated { perform(null) { OptimizeController.continueLadder(context, appId) } }

    fun restore() = perform(R.string.optimize_restored) { OptimizeController.restoreOriginal(context, appId) }

    /** A profile row or chip was tapped while [active] is on with [status]. */
    fun tap(active: OptimizeProfile, tapped: OptimizeProfile, status: OptimizeStatus?) = when (OptimizeLadders.tap(active, tapped, status)) {
        OptimizeLadders.Tap.SWITCH -> switchTo(tapped)
        OptimizeLadders.Tap.CONTINUE -> continueLadder()
        OptimizeLadders.Tap.NONE -> Unit
    }
}

@Composable
fun rememberOptimizeActions(appId: String): OptimizeActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberOptimizeGate()
    return remember(appId, gate) { OptimizeActions(context, scope, gate, appId) }
}

@Composable
fun rememberOptimizeState(appId: String): OptimizeLocalState? {
    val context = LocalContext.current
    val revision by OptimizeController.revision.collectAsState()
    val state by produceState<OptimizeLocalState?>(null, appId, revision) {
        value = OptimizeController.readState(context, appId) ?: OptimizeLocalState()
    }
    return state
}

@Composable
fun optimizeStatusText(state: OptimizeLocalState): String? = when (val status = OptimizeStatus.of(state)) {
    OptimizeStatus.Off -> null
    OptimizeStatus.Never -> stringResource(R.string.optimize_card_never)
    OptimizeStatus.NotStarted -> stringResource(R.string.optimize_status_not_started)
    OptimizeStatus.Measuring -> stringResource(R.string.optimize_status_measuring)
    OptimizeStatus.TuningNext -> stringResource(R.string.optimize_status_tuning_next)
    OptimizeStatus.RetryNext -> stringResource(R.string.optimize_status_retry_next)
    is OptimizeStatus.Trying -> {
        val cost = OptimizeStatus.visualCostRes(status.visualCostId)?.let { stringResource(it) } ?: status.visualCostEn
        if (cost.isNullOrBlank()) {
            stringResource(R.string.optimize_status_trying_plain)
        } else {
            stringResource(R.string.optimize_status_trying, cost)
        }
    }
    OptimizeStatus.Paused -> stringResource(R.string.optimize_status_paused)
    OptimizeStatus.Done -> stringResource(R.string.optimize_status_done)
    OptimizeStatus.Exhausted -> stringResource(R.string.optimize_status_exhausted)
    OptimizeStatus.Waiting -> stringResource(R.string.optimize_status_waiting)
    OptimizeStatus.TrialUsed -> null
}

/** The one trial line for this game: the server's entitlement when known, otherwise the default offer. */
@Composable
fun optimizeEntitlementText(entitlement: Entitlement?, exhausted: Boolean = false, short: Boolean = false): String? {
    val known = entitlement.orAppWide(OptimizeController.lastEntitlement)
    val line = OptimizeStatus.entitlementLine(known, exhausted, PrefManager.gameNativeSignedIn.value, Membership.isMember())
    return when (line) {
        null -> null
        EntitlementLine.Member -> stringResource(R.string.optimize_included)
        is EntitlementLine.Offer -> pluralStringResource(R.plurals.optimize_trial_offer, line.limit, line.limit)
        is EntitlementLine.Trial -> pluralStringResource(R.plurals.optimize_trial_progress, line.limit, line.used, line.limit)
        is EntitlementLine.Exhausted -> if (short) {
            stringResource(R.string.optimize_trial_used_short)
        } else {
            pluralStringResource(R.plurals.optimize_trial_used, line.limit, line.limit)
        }
    }
}

/** The pill color for a status: in progress cyan, paused warning, done success, the rest muted. */
@Composable
fun optimizeStatusColor(status: OptimizeStatus): Color = when (status) {
    OptimizeStatus.Paused -> PluviaTheme.colors.accentWarning
    OptimizeStatus.Done -> PluviaTheme.colors.accentSuccess
    OptimizeStatus.Exhausted -> PluviaTheme.colors.accentPurple
    OptimizeStatus.Waiting, OptimizeStatus.Never, OptimizeStatus.TrialUsed, OptimizeStatus.Off -> PluviaTheme.colors.textMuted
    else -> PluviaTheme.colors.accentCyan
}

/** The Optimize row of the game options panel: the state as a pill and Continue when it is offered; the row opens the dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptimizeOptionsRow(appId: String) {
    val state = rememberOptimizeState(appId)
    val actions = rememberOptimizeActions(appId)
    val status = state?.let { OptimizeStatus.of(it) }
    val exhausted = status == OptimizeStatus.TrialUsed
    val keptCount = state?.let { OptimizeStatus.keptCount(it) } ?: 0
    Column(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val statusText = state?.let { optimizeStatusText(it) }
            if (status != null && statusText != null) {
                OutcomePill(text = statusText, color = optimizeStatusColor(status), modifier = Modifier.align(Alignment.CenterVertically))
            } else if (exhausted) {
                OptimizeEntitlementPill(state?.entitlement, exhausted = true, modifier = Modifier.align(Alignment.CenterVertically))
            }
            if (OptimizeLadders.canContinue(status)) {
                val interaction = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(50)
                FilledTonalButton(
                    onClick = { actions.continueLadder() },
                    enabled = !actions.busy,
                    interactionSource = interaction,
                    shape = shape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .focusRing(interaction, shape, width = 2.dp),
                ) {
                    Text(text = stringResource(R.string.optimize_continue), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        listOfNotNull(
            state?.let { OptimizeStatus.heldCapFps(it) }?.let { stringResource(R.string.optimize_status_cap_held, it) },
            if (keptCount > 0) pluralStringResource(R.plurals.optimize_status_kept, keptCount, keptCount) else null,
            if (exhausted) null else optimizeEntitlementText(state?.entitlement, short = true),
        ).forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun OptimizeDialog(
    appId: String,
    onDismiss: () -> Unit,
) {
    val state = rememberOptimizeState(appId)
    val actions = rememberOptimizeActions(appId)
    val profile = state?.activeProfile ?: OptimizeProfile.OFF
    val status = state?.let { OptimizeStatus.of(it) }
    val keptCount = state?.let { OptimizeStatus.keptCount(it) } ?: 0
    val activeLadder = state?.active
    val exhausted = status == OptimizeStatus.TrialUsed
    val context = LocalContext.current
    val revision by OptimizeController.revision.collectAsState()
    val suggestion by produceState<OptimizeController.Suggestion?>(null, appId, revision) {
        value = OptimizeController.suggestion(context, appId)
    }
    val hint = when {
        state == null -> null
        state.activeProfile == null -> suggestion?.let { optimizeSuggestedText(it.suggested.reason) }
        status == OptimizeStatus.Done && profile != OptimizeProfile.POWER && suggestion?.hot == true -> stringResource(R.string.optimize_hint_heat)
        else -> null
    }
    val suggested = when {
        state == null -> null
        state.activeProfile == null -> suggestion?.suggested?.profile
        status == OptimizeStatus.Done && profile != OptimizeProfile.POWER && suggestion?.hot == true -> OptimizeProfile.POWER
        else -> null
    }
    val canRestore = activeLadder != null && (activeLadder.kept.isNotEmpty() || activeLadder.pending != null) && !actions.busy
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val firstFocus = remember { FocusRequester() }

    val header: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroBadge(icon = Icons.Filled.Speed, accent = PluviaTheme.colors.accentCyan)
            Text(
                text = stringResource(R.string.option_optimize),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.optimize_choose_goal),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        OptimizeGoalCards(
            selected = profile,
            suggested = suggested,
            enabled = state != null && !actions.busy,
            onSelect = { actions.tap(profile, it, status) },
            includeOff = true,
            firstModifier = Modifier.focusRequester(firstFocus),
        )
    }

    val statusBlock: @Composable ColumnScope.() -> Unit = {
        val statusText = state?.let { optimizeStatusText(it) }
        if (status != null && statusText != null) {
            OutcomePill(text = statusText, color = optimizeStatusColor(status))
        }
        listOfNotNull(
            state?.let { OptimizeStatus.heldCapFps(it) }?.let { stringResource(R.string.optimize_status_cap_held, it) },
            hint,
            if (keptCount > 0 && status !is OptimizeStatus.Trying) pluralStringResource(R.plurals.optimize_status_kept, keptCount, keptCount) else null,
        ).forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        OptimizeEntitlementPill(state?.entitlement, exhausted = exhausted, modifier = Modifier.padding(top = 8.dp))
        if (exhausted) {
            optimizeEntitlementText(state?.entitlement, exhausted = true)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    val footer: @Composable ColumnScope.() -> Unit = {
        if (OptimizeLadders.canContinue(status)) {
            PremiumDialogButton(
                text = stringResource(R.string.optimize_continue),
                onClick = { actions.continueLadder() },
                enabled = !actions.busy,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (exhausted && !Membership.isMember()) {
            PremiumDialogButton(
                text = stringResource(R.string.optimize_see_membership),
                onClick = { Membership.plansRequested.value = true },
                primary = false,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        PremiumTextButton(
            text = stringResource(R.string.optimize_restore_original),
            onClick = { actions.restore() },
            enabled = canRestore,
            color = PluviaTheme.colors.accentDanger,
        )
        Text(
            text = stringResource(R.string.optimize_restore_original_body),
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        PremiumTextButton(text = stringResource(R.string.close), onClick = onDismiss)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
    ) {
        LaunchedEffect(Unit) { firstFocus.requestFocusAfterLayout() }

        GradientBorderSurface(
            modifier = Modifier
                .then(if (landscape) Modifier.widthIn(max = 760.dp).padding(16.dp) else Modifier.fillMaxWidth())
                .wrapContentHeight(),
            accent = PluviaTheme.colors.accentCyan,
        ) {
            if (landscape) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.3f)
                            .verticalScroll(rememberScrollState()),
                        content = header,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        statusBlock()
                        Spacer(modifier = Modifier.height(16.dp))
                        footer()
                    }
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
                    ) {
                        header()
                        Spacer(modifier = Modifier.height(12.dp))
                        statusBlock()
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    footer()
                }
            }
        }
    }
}
