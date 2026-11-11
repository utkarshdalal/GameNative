package app.gamenative.ui.component.dialog

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.api.AccountApi
import app.gamenative.optimize.Entitlement
import app.gamenative.optimize.EntitlementLine
import app.gamenative.optimize.OptimizeChange
import app.gamenative.optimize.OptimizeController
import app.gamenative.optimize.OptimizeLadders
import app.gamenative.optimize.OptimizeLocalState
import app.gamenative.optimize.OptimizeProfile
import app.gamenative.optimize.OptimizeStatus
import app.gamenative.optimize.limitOrDefault
import app.gamenative.ui.component.BenefitRow
import app.gamenative.ui.component.GradientBorderSurface
import app.gamenative.ui.component.HeroBadge
import app.gamenative.ui.component.PremiumDialogButton
import app.gamenative.ui.component.PremiumTextButton
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
                OptimizeLabel(
                    text = pluralStringResource(R.plurals.optimize_trial_offer, trialLimit, trialLimit),
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.weight(1f),
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

    /** The confirmation of the last action that finished, shown in the dialog, which covers the snackbar. */
    var confirmation by mutableStateOf<Int?>(null)
        private set

    private fun perform(doneMessage: Int?, action: suspend () -> OptimizeController.ChangeResult) {
        busy = true
        confirmation = null
        scope.launch {
            // The message is sent even when the dialog closed meanwhile, so the snackbar shows once it is gone.
            withContext(NonCancellable) {
                when (action()) {
                    OptimizeController.ChangeResult.GAME_RUNNING -> SnackbarManager.show(context.getString(R.string.optimize_game_running))
                    OptimizeController.ChangeResult.FAILED -> SnackbarManager.show(context.getString(R.string.optimize_undo_failed))
                    OptimizeController.ChangeResult.DONE -> doneMessage?.let {
                        confirmation = it
                        SnackbarManager.show(context.getString(it))
                    }
                    OptimizeController.ChangeResult.NO_CONTAINER -> Unit
                }
                busy = false
            }
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
fun optimizeStatusText(state: OptimizeLocalState): String? = when (val status = OptimizeStatus.of(state, OptimizeController.accountChangedAt)) {
    OptimizeStatus.Off -> null
    OptimizeStatus.Never -> stringResource(R.string.optimize_card_never)
    OptimizeStatus.NotStarted -> stringResource(R.string.optimize_status_not_started)
    OptimizeStatus.Measuring -> stringResource(R.string.optimize_status_measuring)
    OptimizeStatus.TuningNext -> stringResource(R.string.optimize_status_tuning_next)
    OptimizeStatus.RetryNext -> stringResource(R.string.optimize_status_retry_next)
    is OptimizeStatus.Trying -> stringResource(
        when (status.change) {
            OptimizeChange.SETTINGS -> R.string.optimize_status_trying_settings
            OptimizeChange.CORES -> R.string.optimize_status_trying_cores
            OptimizeChange.COMPONENTS -> R.string.optimize_status_trying_components
            OptimizeChange.FPS_HOLD -> R.string.optimize_status_trying_cap
            null -> R.string.optimize_status_trying_plain
        },
    )
    OptimizeStatus.Paused -> stringResource(R.string.optimize_status_paused)
    OptimizeStatus.Done -> stringResource(R.string.optimize_status_done)
    OptimizeStatus.Exhausted -> stringResource(R.string.optimize_status_exhausted)
    OptimizeStatus.Waiting -> stringResource(R.string.optimize_status_waiting)
    OptimizeStatus.TrialUsed -> null
}

/** The one plan line for this game as text: the server's entitlement when known, otherwise the default offer. */
@Composable
fun optimizeEntitlementText(entitlement: Entitlement?, exhausted: Boolean = false, short: Boolean = false): String? =
    when (val line = optimizeEntitlementLine(entitlement, exhausted)) {
        null -> null
        EntitlementLine.Unlimited -> stringResource(R.string.optimize_included)
        is EntitlementLine.Offer -> pluralStringResource(R.plurals.optimize_trial_offer, line.limit, line.limit)
        is EntitlementLine.Exhausted -> if (short) {
            planText(OptimizeStatus.entitlementPill(line))
        } else {
            pluralStringResource(R.plurals.optimize_trial_used, line.limit, line.limit)
        }
        is EntitlementLine.Monthly -> {
            val used = if (short) null else OptimizeStatus.upsellSubtitle(line)
            planText(used ?: OptimizeStatus.entitlementPill(line))
        }
        is EntitlementLine.Trial -> planText(OptimizeStatus.entitlementPill(line))
    }

/** The colour for a status: in progress cyan, paused warning, done success, the rest muted. */
@Composable
fun optimizeStatusColor(status: OptimizeStatus): Color = when (status) {
    OptimizeStatus.Paused -> PluviaTheme.colors.accentWarning
    OptimizeStatus.Done -> PluviaTheme.colors.accentSuccess
    OptimizeStatus.Exhausted -> PluviaTheme.colors.accentPurple
    OptimizeStatus.Waiting, OptimizeStatus.Never, OptimizeStatus.TrialUsed, OptimizeStatus.Off -> PluviaTheme.colors.textMuted
    else -> PluviaTheme.colors.accentCyan
}

@Composable
fun OptimizeDialog(
    appId: String,
    onDismiss: () -> Unit,
    gameName: String? = null,
) {
    val state = rememberOptimizeState(appId)
    val actions = rememberOptimizeActions(appId)
    val profile = state?.activeProfile ?: OptimizeProfile.OFF
    val status = state?.let { OptimizeStatus.of(it, OptimizeController.accountChangedAt) }
    val keptCount = state?.let { OptimizeStatus.keptCount(it) } ?: 0
    val activeLadder = state?.active
    val exhausted = status == OptimizeStatus.TrialUsed
    val entitlement = OptimizeController.shownEntitlement(state)
    val line = optimizeEntitlementLine(entitlement, exhausted)
    val context = LocalContext.current
    val revision by OptimizeController.revision.collectAsState()
    val suggestion by produceState<OptimizeController.Suggestion?>(null, appId, revision) {
        value = OptimizeController.suggestion(context, appId)
    }
    val hint = when {
        state == null -> null
        status == OptimizeStatus.Done && profile != OptimizeProfile.POWER && suggestion?.hot == true -> stringResource(R.string.optimize_hint_heat)
        else -> null
    }
    val suggested = state?.let { OptimizeStatus.suggestedProfile(it, suggestion?.profile, suggestion?.hot == true) }
    val showRestore = OptimizeLadders.hasRestorable(activeLadder)
    val canContinue = OptimizeLadders.canContinue(status)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val firstFocus = remember { FocusRequester() }
    // Hoisted so a rotation keeps where the goal list and the side column were scrolled to.
    val mainScroll = rememberScrollState()
    val sideScroll = rememberScrollState()

    // D-pad: Right from any goal lands on the first action; Left from an action returns to the goal focused last.
    val goalFocus = remember { (OPTIMIZE_GOAL_ORDER + OptimizeProfile.OFF).associateWith { FocusRequester() } }
    var lastGoal by remember { mutableStateOf<OptimizeProfile?>(null) }
    val continueFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    val confirmCancelFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var askedRestore by remember { mutableStateOf(false) }
    val confirmingRestore = askedRestore && showRestore
    var focusNext by remember { mutableStateOf<FocusRequester?>(null) }
    val lastGoalFocus = goalFocus.getValue(lastGoal ?: profile)
    val firstAction = when {
        canContinue -> continueFocus
        confirmingRestore -> confirmCancelFocus
        showRestore -> restoreFocus
        else -> closeFocus
    }
    val actionModifier: (FocusRequester) -> Modifier = { requester ->
        Modifier
            .focusRequester(requester)
            .focusProperties { left = lastGoalFocus }
    }

    val header: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroBadge(icon = Icons.Filled.Speed, accent = PluviaTheme.colors.accentCyan)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = gameName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.option_optimize),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!gameName.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.option_optimize),
                        style = MaterialTheme.typography.labelLarge,
                        color = PluviaTheme.colors.textMuted,
                    )
                }
                OptimizeStatusLine(status, line)
                OptimizePlanLine(entitlement, exhausted = exhausted)
            }
        }
        // The status sentence sits under the header in both orientations, and only when it says more than the header status.
        listOfNotNull(
            state?.takeIf { status != null && OptimizeStatus.showsSentence(status) }?.let { optimizeStatusText(it) },
            state?.let { OptimizeStatus.heldCapFps(it) }?.let { stringResource(R.string.optimize_status_cap_held, it) },
            if (keptCount > 0 && status !is OptimizeStatus.Trying) pluralStringResource(R.plurals.optimize_status_kept, keptCount, keptCount) else null,
        ).forEachIndexed { index, text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = if (index == 0) 12.dp else 6.dp),
            )
        }
    }

    val goals: @Composable ColumnScope.() -> Unit = {
        Text(
            text = stringResource(R.string.optimize_choose_goal),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        OptimizeGoalCards(
            selected = profile,
            suggested = suggested,
            enabled = state != null && !actions.busy,
            onSelect = { actions.tap(profile, it, status) },
            includeOff = true,
            modifier = Modifier.focusRestorer(firstFocus),
            firstModifier = Modifier.focusRequester(firstFocus),
            cardModifier = { goal ->
                Modifier
                    .focusRequester(goalFocus.getValue(goal))
                    .onFocusChanged { if (it.hasFocus) lastGoal = goal }
                    .focusProperties { right = firstAction }
            },
        )
    }

    val details: @Composable ColumnScope.() -> Unit = {
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (OptimizeStatus.showsUpsell(line)) {
            OptimizeUpsell(line, modifier = Modifier.padding(top = 6.dp))
        }
    }

    val footer: @Composable ColumnScope.() -> Unit = {
        if (canContinue) {
            PremiumDialogButton(
                text = stringResource(R.string.optimize_continue),
                onClick = { actions.continueLadder() },
                enabled = !actions.busy,
                modifier = actionModifier(continueFocus),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        actions.confirmation?.let {
            OptimizeLabel(
                text = stringResource(it),
                color = PluviaTheme.colors.accentSuccess,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
        if (confirmingRestore) {
            Text(
                text = stringResource(R.string.optimize_restore_confirm),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.optimize_restore_original_body),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PremiumTextButton(
                    text = stringResource(R.string.cancel),
                    onClick = {
                        askedRestore = false
                        focusNext = restoreFocus
                    },
                    modifier = Modifier.weight(1f).then(actionModifier(confirmCancelFocus)),
                )
                PremiumTextButton(
                    text = stringResource(R.string.yes),
                    onClick = {
                        askedRestore = false
                        focusNext = lastGoalFocus
                        actions.restore()
                    },
                    enabled = !actions.busy,
                    color = PluviaTheme.colors.accentDanger,
                    modifier = Modifier.weight(1f).focusProperties { left = confirmCancelFocus },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        } else if (showRestore) {
            PremiumTextButton(
                text = stringResource(R.string.optimize_restore_original),
                onClick = {
                    askedRestore = true
                    focusNext = confirmCancelFocus
                },
                enabled = !actions.busy,
                color = PluviaTheme.colors.accentDanger,
                modifier = Modifier.fillMaxWidth().then(actionModifier(restoreFocus)),
            )
            Text(
                text = stringResource(R.string.optimize_restore_original_body),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        PremiumTextButton(
            text = stringResource(R.string.close),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().then(actionModifier(closeFocus)),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The cards take focus only once the state has loaded, so the first focus waits for it: the selected goal,
        // or the first goal when none is chosen yet.
        val loaded = state != null
        LaunchedEffect(loaded) {
            if (loaded) (state?.activeProfile?.let { goalFocus.getValue(it) } ?: firstFocus).requestFocusAfterLayout()
        }
        LaunchedEffect(focusNext) {
            focusNext?.requestFocusAfterLayout()
            focusNext = null
        }

        GradientBorderSurface(
            modifier = Modifier
                .then(
                    if (landscape) {
                        Modifier.widthIn(max = 760.dp).padding(16.dp)
                    } else {
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
                    },
                )
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
                            .weight(1.2f)
                            .verticalScroll(mainScroll),
                    ) {
                        header()
                        goals()
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(sideScroll),
                    ) {
                        details()
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
                            .verticalScroll(mainScroll),
                    ) {
                        header()
                        goals()
                        Spacer(modifier = Modifier.height(16.dp))
                        details()
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    footer()
                }
            }
        }
    }
}
