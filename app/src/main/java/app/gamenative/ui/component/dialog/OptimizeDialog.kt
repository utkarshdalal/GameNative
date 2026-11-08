package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optimize needs an account: actions behind it wait for sign-in and run once it succeeds. */
class OptimizeGate {
    var pending by mutableStateOf<(() -> Unit)?>(null)

    suspend fun require(action: () -> Unit) {
        if (AccountApi.loadSignedInState()) action() else pending = action
    }
}

@Composable
fun rememberOptimizeGate(): OptimizeGate {
    val gate = remember { OptimizeGate() }
    AccountSignInDialog(
        visible = gate.pending != null,
        reason = OptimizeController.lastEntitlement.limitOrDefault().let { pluralStringResource(R.plurals.optimize_trial_offer, it, it) },
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
        scope.launch { gate.require(action) }
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OptimizeProfileChips(
    selected: OptimizeProfile,
    profiles: List<OptimizeProfile>,
    enabled: Boolean,
    onSelect: (OptimizeProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            FilterChip(
                selected = profile == selected,
                enabled = enabled,
                onClick = { onSelect(profile) },
                label = { Text(text = stringResource(profile.title)) },
            )
        }
    }
}

/** The Optimize row of the game options panel: the state in words and one chip per profile. */
@Composable
fun OptimizeOptionsRow(appId: String) {
    val state = rememberOptimizeState(appId)
    val actions = rememberOptimizeActions(appId)
    val active = state?.activeProfile ?: OptimizeProfile.OFF
    val status = state?.let { OptimizeStatus.of(it) }
    val exhausted = status == OptimizeStatus.TrialUsed
    val keptCount = state?.let { OptimizeStatus.keptCount(it) } ?: 0
    Column(modifier = Modifier.fillMaxWidth()) {
        listOfNotNull(
            state?.let { optimizeStatusText(it) },
            state?.let { OptimizeStatus.heldCapFps(it) }?.let { stringResource(R.string.optimize_status_cap_held, it) },
            if (keptCount > 0) pluralStringResource(R.plurals.optimize_status_kept, keptCount, keptCount) else null,
            optimizeEntitlementText(state?.entitlement, exhausted = exhausted, short = true),
        ).forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OptimizeProfileChips(
            selected = active,
            profiles = OptimizeProfile.entries,
            enabled = state != null && !actions.busy,
            onSelect = { actions.tap(active, it, status) },
        )
        if (OptimizeLadders.canContinue(status)) {
            TextButton(enabled = !actions.busy, onClick = { actions.continueLadder() }) {
                Text(text = stringResource(R.string.optimize_continue))
            }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.option_optimize)) },
        text = {
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                OptimizeProfile.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = option == profile,
                                enabled = state != null && !actions.busy,
                                onClick = { actions.tap(profile, option, status) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == profile, onClick = null)
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(text = stringResource(option.title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(option.description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state?.let { optimizeStatusText(it) }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                    )
                }
                state?.let { OptimizeStatus.heldCapFps(it) }?.let {
                    Text(
                        text = stringResource(R.string.optimize_status_cap_held, it),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                }
                hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                }
                if (keptCount > 0 && status !is OptimizeStatus.Trying) {
                    Text(
                        text = pluralStringResource(R.plurals.optimize_status_kept, keptCount, keptCount),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                }
                optimizeEntitlementText(state?.entitlement, exhausted = exhausted)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        },
        dismissButton = {
            Row {
                if (exhausted && !Membership.isMember()) {
                    TextButton(onClick = { Membership.plansRequested.value = true }) {
                        Text(text = stringResource(R.string.optimize_see_membership))
                    }
                }
                if (OptimizeLadders.canContinue(status)) {
                    TextButton(enabled = !actions.busy, onClick = { actions.continueLadder() }) {
                        Text(text = stringResource(R.string.optimize_continue))
                    }
                }
                TextButton(
                    enabled = activeLadder != null && (activeLadder.kept.isNotEmpty() || activeLadder.pending != null) && !actions.busy,
                    onClick = { actions.restore() },
                ) {
                    Text(text = stringResource(R.string.optimize_restore_original))
                }
            }
        },
    )
}
