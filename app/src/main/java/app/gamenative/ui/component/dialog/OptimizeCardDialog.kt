package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.billing.Membership
import app.gamenative.optimize.OptimizeCard
import app.gamenative.optimize.OptimizeCards
import app.gamenative.optimize.OptimizeController
import app.gamenative.optimize.OptimizeProfile
import app.gamenative.optimize.OptimizeTarget
import kotlinx.coroutines.launch

/** Shows the waiting Optimize card once the user is back on the library or game screen. */
@Composable
fun OptimizeCardHost(gameRunning: Boolean, blocked: Boolean) {
    val request by OptimizeController.cardRequest.collectAsState()
    val current = request ?: return
    if (gameRunning || blocked) return
    OptimizeCardDialog(current)
}

@Composable
private fun firstCardTitle(cls: String?, name: String): String = when (cls) {
    "gpu" -> stringResource(R.string.optimize_card_title_gpu, name)
    "thermal_gpu" -> stringResource(R.string.optimize_card_title_thermal, name)
    "cpu_single", "cpu_multi" -> stringResource(R.string.optimize_card_title_cpu, name)
    "memory" -> stringResource(R.string.optimize_card_title_memory, name)
    "present_locked" -> stringResource(R.string.optimize_card_title_present, name)
    else -> stringResource(R.string.optimize_card_title_sync_spin, name)
}

/** Why a profile is suggested, in outcome words; [reason] is an [OptimizeCards] REASON_ id. */
@Composable
fun optimizeSuggestedText(reason: String?): String = when (reason) {
    OptimizeCards.REASON_HOT -> stringResource(R.string.optimize_suggested_power)
    OptimizeCards.REASON_HEAT_DIPS -> stringResource(R.string.optimize_suggested_performance_heat)
    OptimizeCards.REASON_LOW_FPS -> stringResource(R.string.optimize_suggested_performance)
    OptimizeCards.REASON_STUTTER -> stringResource(R.string.optimize_suggested_stability)
    else -> stringResource(R.string.optimize_suggested_balanced)
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

@Composable
private fun OptimizeCardDialog(request: OptimizeController.CardRequest) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberOptimizeGate()
    val card = request.card
    val name = card.gameName.orEmpty()
    val heatHint = card.kind == OptimizeCard.DONE && card.profile != OptimizeProfile.POWER &&
        card.metrics?.let { OptimizeTarget.hot(it, null, card.capFps ?: 0) } == true
    var choosing by remember(request.appId, request.card) { mutableStateOf(heatHint) }
    var chosen by remember(request.appId, request.card) {
        mutableStateOf(if (heatHint) OptimizeProfile.POWER else card.profile.takeIf { card.kind == OptimizeCard.FIRST } ?: OptimizeProfile.BALANCED)
    }

    fun answer(action: String, profile: OptimizeProfile? = null) =
        OptimizeController.answer(context, request.appId, card, action, profile)

    val title: String
    var body: String? = null
    var detail: String? = null
    var reason: String? = null
    when (card.kind) {
        OptimizeCard.FIRST -> {
            title = firstCardTitle(card.cls, name)
            if (card.cls != OptimizeCards.SYNC_SPIN) {
                body = stringResource(R.string.optimize_card_body)
                reason = card.reason?.let { optimizeSuggestedText(it) }
            }
        }
        OptimizeCard.CAP -> {
            title = stringResource(R.string.optimize_cap_title, name, card.capFps ?: OptimizeCards.CAP_HEAT_FPS.toInt())
            body = stringResource(R.string.optimize_cap_body)
        }
        OptimizeCard.HELPED -> {
            title = card.capFps?.let { stringResource(R.string.optimize_after_cap_held, it) } ?: when (card.outcome) {
                OptimizeCard.OUTCOME_FPS -> stringResource(R.string.optimize_after_helped_fps)
                OptimizeCard.OUTCOME_STUTTER -> stringResource(R.string.optimize_after_helped_stutter)
                else -> stringResource(R.string.optimize_after_helped_stable)
            }
            body = measuredText(card)
        }
        OptimizeCard.NO_CHANGE -> title = card.capFps?.let { stringResource(R.string.optimize_after_cap_removed, it) }
            ?: stringResource(R.string.optimize_after_no_change)
        OptimizeCard.WORSE -> title = card.capFps?.let { stringResource(R.string.optimize_after_cap_removed, it) }
            ?: stringResource(R.string.optimize_after_worse)
        OptimizeCard.EXHAUSTED -> title = stringResource(R.string.optimize_after_exhausted, name)
        OptimizeCard.DONE -> {
            title = stringResource(R.string.optimize_after_done, name)
            body = if (heatHint) {
                stringResource(R.string.optimize_hint_heat)
            } else {
                card.capFps?.let { stringResource(R.string.optimize_after_cap_held, it) }
            }
        }
        else -> title = optimizeEntitlementText(request.entitlement, exhausted = true).orEmpty()
    }
    if (card.kind != OptimizeCard.ENTITLEMENT && card.cls != OptimizeCards.SYNC_SPIN) detail = optimizeEntitlementText(request.entitlement)

    AlertDialog(
        onDismissRequest = { answer(OptimizeCards.ACTION_DISMISSED) },
        title = { Text(text = title) },
        text = if (body == null && detail == null && !choosing) {
            null
        } else {
            {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    body?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                    reason?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                    if (choosing) {
                        OptimizeProfileChips(
                            selected = chosen,
                            profiles = OptimizeProfile.entries.filter { it != OptimizeProfile.OFF },
                            enabled = true,
                            onSelect = { chosen = it },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            text = stringResource(chosen.description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (card.kind) {
                OptimizeCard.FIRST -> if (card.cls == OptimizeCards.SYNC_SPIN) {
                    TextButton(onClick = { answer(OptimizeCards.ACTION_NOT_NOW) }) { Text(stringResource(R.string.optimize_card_not_now)) }
                } else {
                    Button(
                        onClick = {
                            if (!choosing) {
                                choosing = true
                            } else {
                                val profile = chosen
                                scope.launch { gate.require { answer(OptimizeCards.ACTION_OPTIMIZE, profile) } }
                            }
                        },
                    ) { Text(stringResource(R.string.option_optimize)) }
                }
                OptimizeCard.CAP -> Button(
                    onClick = {
                        val profile = card.profile
                        scope.launch { gate.require { answer(OptimizeCards.ACTION_CAP, profile) } }
                    },
                ) { Text(stringResource(R.string.optimize_cap_yes)) }
                OptimizeCard.HELPED, OptimizeCard.NO_CHANGE ->
                    Button(onClick = { answer(OptimizeCards.ACTION_KEEP_GOING) }) { Text(stringResource(R.string.optimize_keep_going)) }
                OptimizeCard.EXHAUSTED ->
                    Button(onClick = { answer(OptimizeCards.ACTION_KEEP_CHANGES) }) { Text(stringResource(R.string.optimize_keep_changes)) }
                OptimizeCard.DONE -> if (heatHint) {
                    Button(
                        onClick = {
                            val profile = chosen
                            scope.launch { gate.require { answer(OptimizeCards.ACTION_OPTIMIZE, profile) } }
                        },
                    ) { Text(stringResource(R.string.option_optimize)) }
                } else {
                    Button(onClick = { answer(OptimizeCards.ACTION_OK) }) { Text(stringResource(R.string.ok)) }
                }
                OptimizeCard.ENTITLEMENT -> Button(
                    onClick = {
                        answer(OptimizeCards.ACTION_MEMBERSHIP)
                        Membership.plansRequested.value = true
                    },
                ) { Text(stringResource(R.string.optimize_see_membership)) }
                else -> Button(onClick = { answer(OptimizeCards.ACTION_OK) }) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            when (card.kind) {
                OptimizeCard.FIRST -> Row {
                    if (card.cls != OptimizeCards.SYNC_SPIN) {
                        TextButton(onClick = { answer(OptimizeCards.ACTION_NOT_NOW) }) { Text(stringResource(R.string.optimize_card_not_now)) }
                    }
                    TextButton(onClick = { answer(OptimizeCards.ACTION_NEVER) }) { Text(stringResource(R.string.optimize_card_never)) }
                }
                OptimizeCard.CAP ->
                    TextButton(onClick = { answer(OptimizeCards.ACTION_NOT_NOW) }) { Text(stringResource(R.string.optimize_card_not_now)) }
                OptimizeCard.HELPED -> Row {
                    TextButton(onClick = { answer(OptimizeCards.ACTION_UNDO) }) { Text(stringResource(R.string.optimize_undo)) }
                    TextButton(onClick = { answer(OptimizeCards.ACTION_HAPPY) }) { Text(stringResource(R.string.optimize_happy)) }
                }
                OptimizeCard.NO_CHANGE ->
                    TextButton(onClick = { answer(OptimizeCards.ACTION_STOP) }) { Text(stringResource(R.string.optimize_stop_here)) }
                OptimizeCard.EXHAUSTED ->
                    TextButton(onClick = { answer(OptimizeCards.ACTION_UNDO_ALL) }) { Text(stringResource(R.string.optimize_undo_all)) }
                OptimizeCard.ENTITLEMENT ->
                    TextButton(onClick = { answer(OptimizeCards.ACTION_DISMISSED) }) { Text(stringResource(R.string.close)) }
                OptimizeCard.DONE -> Row {
                    TextButton(onClick = { scope.launch { gate.require { answer(OptimizeCards.WIRE_CONTINUE) } } }) {
                        Text(stringResource(R.string.optimize_keep_tuning))
                    }
                    if (heatHint) TextButton(onClick = { answer(OptimizeCards.ACTION_OK) }) { Text(stringResource(R.string.ok)) }
                }
                else -> Unit
            }
        },
    )
}
